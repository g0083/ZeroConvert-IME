use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs::File,
    io::{self, Read},
    path::{Path, PathBuf},
};

struct Choice {
    id: &'static str,
    name: &'static str,
    filename: &'static str,
    legacy: &'static str,
    description: &'static str,
}

const CHOICES: [Choice; 2] = [
    Choice {
        id: "standard",
        name: "ZeroCon Model Standard",
        filename: shared::runtime_assets::STANDARD,
        legacy: "zenz-v3.2-small-q4km.gguf",
        description: "標準モデル · Q4_K_M",
    },
    Choice {
        id: "nano",
        name: "ZeroCon Model Nano",
        filename: shared::runtime_assets::NANO,
        legacy: "zenz-v3.2-xsmall-q4km.gguf",
        description: "軽量モデル · Q4_K_M",
    },
];

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModelInfo {
    pub id: String,
    pub name: String,
    pub filename: String,
    pub size_mb: f64,
    pub description: String,
    pub is_active: bool,
    pub available: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModelListResponse {
    pub active_id: String,
    pub models: Vec<ModelInfo>,
}

pub fn source_for(id: &str, directories: &[PathBuf]) -> Result<PathBuf, String> {
    let id = match id {
        "standard_q4km" => "standard",
        "nano_q4km" => "nano",
        other => other,
    };
    let choice = CHOICES
        .iter()
        .find(|choice| choice.id == id)
        .ok_or_else(|| format!("Unknown model_id: {id}"))?;
    locate(choice, directories).ok_or_else(|| format!("Model file not found: {}", choice.filename))
}

fn locate(choice: &Choice, directories: &[PathBuf]) -> Option<PathBuf> {
    directories
        .iter()
        .flat_map(|dir| [dir.join(choice.filename), dir.join(choice.legacy)])
        .find(|path| path.is_file())
}

fn hash(path: &Path) -> io::Result<Vec<u8>> {
    let mut file = File::open(path)?;
    let mut digest = Sha256::new();
    let mut buffer = [0; 64 * 1024];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        digest.update(&buffer[..count]);
    }
    Ok(digest.finalize().to_vec())
}

// Compare the actual converter weights, not the size of an AppData mirror.
// A retired IQ4_XS or an unrelated small model must not be reported as Nano.
pub fn list(active_path: &Path, directories: &[PathBuf]) -> io::Result<ModelListResponse> {
    let active_size = match active_path.metadata() {
        Ok(metadata) => Some(metadata.len()),
        Err(error) if error.kind() == io::ErrorKind::NotFound => None,
        Err(error) => return Err(error),
    };
    let active_hash = active_size.map(|_| hash(active_path)).transpose()?;
    let mut active_id = String::new();
    let mut models = Vec::new();
    for choice in &CHOICES {
        let source = locate(choice, directories);
        let size = source
            .as_ref()
            .map(|path| path.metadata().map(|m| m.len()))
            .transpose()?;
        let is_active = if size.is_some() && size == active_size {
            Some(hash(source.as_ref().unwrap())?) == active_hash
        } else {
            false
        };
        if is_active {
            active_id = choice.id.into();
        }
        models.push(ModelInfo {
            id: choice.id.into(),
            name: choice.name.into(),
            filename: choice.filename.into(),
            size_mb: size.unwrap_or(0) as f64 / (1024.0 * 1024.0),
            description: choice.description.into(),
            is_active,
            available: source.is_some(),
        });
    }
    Ok(ModelListResponse { active_id, models })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn identifies_both_models_by_content_even_when_sizes_match() {
        let dir = tempfile::tempdir().unwrap();
        let dirs = vec![dir.path().to_path_buf()];
        std::fs::write(dir.path().join(CHOICES[0].filename), b"standard").unwrap();
        std::fs::write(dir.path().join(CHOICES[1].filename), b"nano-q4k").unwrap();
        let active = dir.path().join("zenz.gguf");
        for choice in &CHOICES {
            std::fs::copy(source_for(choice.id, &dirs).unwrap(), &active).unwrap();
            let result = list(&active, &dirs).unwrap();
            assert_eq!(result.active_id, choice.id);
            assert_eq!(result.models.iter().filter(|m| m.is_active).count(), 1);
        }
        std::fs::write(&active, b"retired!").unwrap();
        assert!(list(&active, &dirs).unwrap().active_id.is_empty());
        assert!(source_for("iq4xs", &dirs).is_err());
    }
    #[test]
    fn missing_nano_is_unavailable_and_never_resolves_to_corrector() {
        let dir = tempfile::tempdir().unwrap();
        let dirs = vec![dir.path().to_path_buf()];
        std::fs::write(
            dir.path().join("typo-corrector-xsmall-q4km.gguf"),
            b"corrector",
        )
        .unwrap();
        // An old experimental Nano v2 must never stand in for the original v3.2 xsmall.
        std::fs::write(
            dir.path().join("zerocon-model-nano-v2-q4km.gguf"),
            b"nano-v2",
        )
        .unwrap();
        let result = list(&dir.path().join("zenz.gguf"), &dirs).unwrap();
        assert!(result.active_id.is_empty());
        assert_eq!(result.models.len(), 2);
        assert!(result.models.iter().all(|model| !model.available));
        assert!(source_for("nano_q4km", &dirs).is_err());
    }
}
