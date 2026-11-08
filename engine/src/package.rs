use std::{
    collections::BTreeMap,
    fs::File,
    io::{Read, Write},
    path::Path,
};

use serde::{Deserialize, Serialize};
use thiserror::Error;
use zip::{CompressionMethod, ZipArchive, ZipWriter, write::SimpleFileOptions};

use crate::model::{is_safe_relative_path, Project, ProjectError, PROJECT_SCHEMA_VERSION};

const PACKAGE_FORMAT: &str = "SOWZAE";
const PACKAGE_VERSION: u32 = 1;
const MAX_ENTRIES: usize = 10_000;
const MAX_METADATA_BYTES: u64 = 16 * 1024 * 1024;
const MAX_SINGLE_FILE_BYTES: u64 = 64 * 1024 * 1024;
const MAX_TOTAL_BYTES: u64 = 256 * 1024 * 1024;

#[derive(Debug, Error)]
pub enum PackageError {
    #[error("Package I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("Invalid ZIP package: {0}")]
    Zip(#[from] zip::result::ZipError),
    #[error("Invalid JSON metadata: {0}")]
    Json(#[from] serde_json::Error),
    #[error(transparent)]
    Project(#[from] ProjectError),
    #[error("Package is invalid: {0}")]
    Invalid(String),
    #[error("Package exceeds a safety limit: {0}")]
    Limit(String),
}

#[derive(Clone, Debug, PartialEq)]
pub struct LoadedPackage {
    pub project: Project,
    /// Extra package files, keyed by safe relative paths such as assets/video/clip.mp4.
    /// Unknown files are retained so a load/save cycle does not silently discard them.
    pub files: BTreeMap<String, Vec<u8>>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
struct Manifest {
    format: String,
    package_version: u32,
    project_schema: u32,
}

pub fn save_package(path: impl AsRef<Path>, package: &LoadedPackage) -> Result<(), PackageError> {
    package.project.validate()?;
    if package.files.len() > MAX_ENTRIES {
        return Err(PackageError::Limit("too many package files".to_owned()));
    }

    let manifest = Manifest {
        format: PACKAGE_FORMAT.to_owned(),
        package_version: PACKAGE_VERSION,
        project_schema: PROJECT_SCHEMA_VERSION,
    };
    let manifest_bytes = serde_json::to_vec_pretty(&manifest)?;
    let project_bytes = serde_json::to_vec_pretty(&package.project)?;
    if manifest_bytes.len() as u64 > MAX_METADATA_BYTES || project_bytes.len() as u64 > MAX_METADATA_BYTES {
        return Err(PackageError::Limit("project metadata is too large".to_owned()));
    }

    let mut total_bytes = (manifest_bytes.len() + project_bytes.len()) as u64;
    for (name, bytes) in &package.files {
        validate_entry_name(name)?;
        if name == "manifest.json" || name == "project.json" {
            return Err(PackageError::Invalid("reserved package entry name".to_owned()));
        }
        if bytes.len() as u64 > MAX_SINGLE_FILE_BYTES {
            return Err(PackageError::Limit(format!("package file is too large: {name}")));
        }
        total_bytes = total_bytes.saturating_add(bytes.len() as u64);
        if total_bytes > MAX_TOTAL_BYTES {
            return Err(PackageError::Limit("total package content is too large".to_owned()));
        }
    }

    let file = File::create(path)?;
    let mut writer = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    writer.start_file("manifest.json", options)?;
    writer.write_all(&manifest_bytes)?;
    writer.start_file("project.json", options)?;
    writer.write_all(&project_bytes)?;
    for (name, bytes) in &package.files {
        writer.start_file(name, options)?;
        writer.write_all(bytes)?;
    }
    writer.finish()?;
    Ok(())
}

pub fn load_package(path: impl AsRef<Path>) -> Result<LoadedPackage, PackageError> {
    let file = File::open(path)?;
    let mut archive = ZipArchive::new(file)?;
    if archive.len() > MAX_ENTRIES.saturating_add(2) {
        return Err(PackageError::Limit("too many ZIP entries".to_owned()));
    }

    let mut manifest: Option<Manifest> = None;
    let mut project_bytes: Option<Vec<u8>> = None;
    let mut files = BTreeMap::new();
    let mut total_bytes = 0u64;

    for index in 0..archive.len() {
        let entry = archive.by_index(index)?;
        if entry.is_dir() {
            continue;
        }
        let name = entry.name().to_owned();
        validate_entry_name(&name)?;
        if entry.size() > MAX_SINGLE_FILE_BYTES {
            return Err(PackageError::Limit(format!("ZIP entry is too large: {name}")));
        }

        let metadata_entry = name == "manifest.json" || name == "project.json";
        let per_file_limit = if metadata_entry { MAX_METADATA_BYTES } else { MAX_SINGLE_FILE_BYTES };
        let bytes = read_limited(entry, per_file_limit)?;
        total_bytes = total_bytes.saturating_add(bytes.len() as u64);
        if total_bytes > MAX_TOTAL_BYTES {
            return Err(PackageError::Limit("total uncompressed package content is too large".to_owned()));
        }

        match name.as_str() {
            "manifest.json" => {
                if manifest.is_some() {
                    return Err(PackageError::Invalid("duplicate manifest.json".to_owned()));
                }
                manifest = Some(serde_json::from_slice(&bytes)?);
            }
            "project.json" => {
                if project_bytes.is_some() {
                    return Err(PackageError::Invalid("duplicate project.json".to_owned()));
                }
                project_bytes = Some(bytes);
            }
            _ => {
                if files.insert(name.clone(), bytes).is_some() {
                    return Err(PackageError::Invalid(format!("duplicate ZIP entry: {name}")));
                }
            }
        }
    }

    let manifest = manifest.ok_or_else(|| PackageError::Invalid("manifest.json is missing".to_owned()))?;
    if manifest.format != PACKAGE_FORMAT || manifest.package_version != PACKAGE_VERSION {
        return Err(PackageError::Invalid("unsupported SOWZ AE package format or version".to_owned()));
    }
    if manifest.project_schema != PROJECT_SCHEMA_VERSION {
        return Err(PackageError::Invalid("unsupported project schema in package manifest".to_owned()));
    }

    let bytes = project_bytes.ok_or_else(|| PackageError::Invalid("project.json is missing".to_owned()))?;
    let project: Project = serde_json::from_slice(&bytes)?;
    project.validate()?;
    Ok(LoadedPackage { project, files })
}

fn validate_entry_name(name: &str) -> Result<(), PackageError> {
    if !is_safe_relative_path(name) {
        return Err(PackageError::Invalid(format!("unsafe package path: {name}")));
    }
    Ok(())
}

fn read_limited<R: Read>(reader: R, limit: u64) -> Result<Vec<u8>, PackageError> {
    let mut limited = reader.take(limit.saturating_add(1));
    let mut bytes = Vec::new();
    limited.read_to_end(&mut bytes)?;
    if bytes.len() as u64 > limit {
        return Err(PackageError::Limit("entry exceeded its read limit".to_owned()));
    }
    Ok(bytes)
}
