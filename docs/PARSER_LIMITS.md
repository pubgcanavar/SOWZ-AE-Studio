# AEP Parser Limits and Validation

## Binary AEP

The binary parser recognizes the RIFX container header and Egg! form signature, reads big-endian chunk framing, recursively scans LIST / RIFF / RIFX blocks with depth and chunk caps, and lists known readable-string chunks. A small heuristic looks for plausible dimensions in cdta payloads described by some community reverse-engineering references.

That heuristic is not a stable Adobe file-format contract. It is labeled as a possibility and must not be treated as verified metadata. This code does not reconstruct all composition, layer, property, effect, expression, footage, or keyframe objects from binary AEP.

## AEPX and AET

XML parsing disables DOCTYPE declarations, external general/parameter entities, XInclude and entity expansion. Recognized item detection uses tag-name heuristics and can miss records whose schema differs by After Effects version.

## ZIP

The ZIP scanner does not extract archive entries to disk. It looks for a project file and reports a sample of archive paths and likely media assets. Asset relinking is not implemented.

## Required next validation

- Use projects the operator owns or is authorized to inspect, from multiple After Effects versions.
- Compare counts, composition dimensions, frame rates, layer names, keyframes and footage references against After Effects.
- Add binary fixtures to tests only when permitted to distribute.
- Never claim exact AE rendering based only on a parsed chunk tree.
