//! Asset names and locations shared by launcher and settings. No downloads here.
use std::path::{Path, PathBuf};

pub const STANDARD: &str = "zerocon-model-standard.gguf";
pub const NANO: &str = "zerocon-model-nano.gguf";
pub const CORRECTOR: &str = "zerocon-model-corrector.gguf";
pub const CUDA_VERSION: &str = "cuda-b4846-cu12.4";

pub fn default_model() -> String {
    "standard".into()
}

pub fn model_filename(id: &str) -> Option<&'static str> {
    match id {
        "standard" | "standard_q4km" => Some(STANDARD),
        "nano" | "nano_q4km" => Some(NANO),
        _ => None,
    }
}

pub fn converter_model(install: &Path, id: &str) -> PathBuf {
    let filename = model_filename(id).unwrap_or(STANDARD);
    let canonical = install.join("models").join(filename);
    if canonical.is_file() {
        return canonical;
    }
    // Compatibility with older installations; new packages contain only canonical names.
    let legacy = install.join("models").join(if filename == NANO {
        "zenz-v3.2-xsmall-q4km.gguf"
    } else {
        "zenz-v3.2-small-q4km.gguf"
    });
    if legacy.is_file() || filename == NANO {
        return legacy;
    }
    install.join("zenz.gguf")
}

pub fn corrector_model(install: &Path) -> PathBuf {
    let canonical = install.join("models").join(CORRECTOR);
    if canonical.is_file() {
        canonical
    } else {
        install.join("models/typo-corrector-xsmall-q4km.gguf")
    }
}

pub fn cuda_cache() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("ZeroConIME/backends")
        .join(CUDA_VERSION)
}

pub fn backend(install: &Path, name: &str) -> PathBuf {
    if name.eq_ignore_ascii_case("cuda") {
        let cached = cuda_cache();
        if cached.join("installed.json").is_file() && cached.join("llama-server.exe").is_file() {
            return cached;
        }
        return install.join("llama_cuda");
    }
    install.join(if name.eq_ignore_ascii_case("vulkan") {
        "llama_vulkan"
    } else {
        "llama_cpu"
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roles_are_distinct_and_unknown_models_are_rejected() {
        assert_eq!(model_filename("nano_q4km"), Some(NANO));
        assert_eq!(model_filename("standard"), Some(STANDARD));
        assert_eq!(model_filename("corrector"), None);
        assert_eq!(model_filename("../arbitrary"), None);
        assert_ne!(NANO, CORRECTOR);
    }

    #[test]
    fn canonical_models_are_selected_without_copying_the_active_weights() {
        let root = std::env::temp_dir().join(format!("zerocon-model-paths-{}", std::process::id()));
        std::fs::create_dir_all(root.join("models")).unwrap();
        for name in [STANDARD, NANO, CORRECTOR] {
            std::fs::write(root.join("models").join(name), []).unwrap();
        }
        assert_eq!(
            converter_model(&root, "standard"),
            root.join("models").join(STANDARD)
        );
        assert_eq!(
            converter_model(&root, "nano"),
            root.join("models").join(NANO)
        );
        assert_eq!(corrector_model(&root), root.join("models").join(CORRECTOR));
        std::fs::remove_file(root.join("models").join(NANO)).unwrap();
        assert!(!converter_model(&root, "nano").is_file());
        for name in [STANDARD, CORRECTOR] {
            std::fs::remove_file(root.join("models").join(name)).unwrap();
        }
        std::fs::remove_dir(root.join("models")).unwrap();
        std::fs::remove_dir(root).unwrap();
    }

    #[test]
    fn old_settings_without_model_load_with_standard_default() {
        let config: crate::ZenzaiConfig =
            serde_json::from_str(r#"{"enable":true,"profile":"","backend":"vulkan"}"#).unwrap();
        assert_eq!(config.model, "standard");
    }
}
