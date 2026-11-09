use jni::objects::{JByteArray, JObject};
use jni::sys::jstring;
use jni::JNIEnv;
use std::ptr;

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}

fn inspect(bytes: &[u8]) -> String {
    const MAX_INPUT: usize = 96 * 1024 * 1024;
    if bytes.len() > MAX_INPUT {
        return error_json("AEP file exceeds the 96 MiB safety limit.");
    }
    let limits = aep_reader::Limits {
        max_input_bytes: MAX_INPUT,
        max_chunk_bytes: MAX_INPUT,
        max_chunks: 120_000,
        max_depth: 48,
        max_keyframes: 300_000,
        max_allocated_bytes: 96 * 1024 * 1024,
    };
    match std::panic::catch_unwind(|| {
        let document = aep_reader::parse_with_limits(bytes, &limits)
            .map_err(|error| error.to_string())?;
        serde_json::to_string(&document.project).map_err(|error| error.to_string())
    }) {
        Ok(Ok(json)) => json,
        Ok(Err(error)) => error_json(&error),
        Err(_) => error_json("AEP parser stopped safely after an unexpected decoding error."),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_sowz_aestudio_core_AepNativeReader_inspectAep(
    mut env: JNIEnv,
    _this: JObject,
    input: JByteArray,
) -> jstring {
    let response = match env.convert_byte_array(input) {
        Ok(bytes) => inspect(&bytes),
        Err(error) => error_json(&format!("Could not read AEP bytes from Android: {error}")),
    };
    match env.new_string(response) {
        Ok(value) => value.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_input_returns_json_error() {
        assert!(inspect(b"not an aep").contains("\"error\""));
    }

    #[test]
    fn minimal_valid_container_returns_project_json() {
        let result = inspect(b"RIFX\0\0\0\x04Egg!");
        assert!(result.contains("\"compositions\""));
    }
}
