//! Asset names and locations shared by launcher and settings. No downloads here.
use std::path::{Path, PathBuf};

pub const NANO_IQ4: &str = "zerocon-nano-v4-iq4nl-emb8.gguf";
pub const NANO_Q8: &str = "zerocon-nano-v4-q8_0.gguf";
pub const CORRECTOR: &str = "zerocon-model-corrector.gguf";
pub const TRANSLATION_0_8B: &str = "plamo-3-translate-0.8b-Q4_K_M.gguf";
pub const TRANSLATION_2B: &str = "plamo-3-translate-2b-Q4_K_M.gguf";
pub const TRANSLATION: &str = TRANSLATION_0_8B;
pub const CUDA_VERSION: &str = "cuda-b12125-cu12.4";

pub fn default_translation_model() -> &'static str {
    "0.8b"
}

pub fn translation_model_filename(id: &str) -> &'static str {
    match id {
        "2b" | "plamo-3-translate-2b" => TRANSLATION_2B,
        _ => TRANSLATION_0_8B,
    }
}

pub fn default_model() -> String {
    "nano_v4_iq4_emb8".into()
}

pub fn canonical_model_id(id: &str) -> Option<&'static str> {
    match id {
        "nano_v4_iq4_emb8" | "nano" | "nano_q4km" => Some("nano_v4_iq4_emb8"),
        "nano_v4_q8" | "standard" | "standard_q4km" => Some("nano_v4_q8"),
        _ => None,
    }
}

pub fn model_filename(id: &str) -> Option<&'static str> {
    match canonical_model_id(id) {
        Some("nano_v4_iq4_emb8") => Some(NANO_IQ4),
        Some("nano_v4_q8") => Some(NANO_Q8),
        _ => None,
    }
}

pub fn converter_model(install: &Path, id: &str) -> PathBuf {
    // Migrate old selection IDs, but never silently load retired model weights.
    install
        .join("models")
        .join(model_filename(id).unwrap_or(NANO_IQ4))
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
        // Never mix a legacy b4846 DLL with the modern Swift ABI.
        return install.join("llama_cuda_b12125");
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
        assert_eq!(model_filename("nano_q4km"), Some(NANO_IQ4));
        assert_eq!(model_filename("standard"), Some(NANO_Q8));
        assert_eq!(model_filename("nano_v4_q8"), Some(NANO_Q8));
        assert_eq!(model_filename("corrector"), None);
        assert_eq!(model_filename("../arbitrary"), None);
        assert_ne!(NANO_IQ4, CORRECTOR);
    }

    #[test]
    fn canonical_models_are_selected_without_copying_the_active_weights() {
        let root = std::env::temp_dir().join(format!("zerocon-model-paths-{}", std::process::id()));
        std::fs::create_dir_all(root.join("models")).unwrap();
        for name in [NANO_Q8, NANO_IQ4, CORRECTOR] {
            std::fs::write(root.join("models").join(name), []).unwrap();
        }
        assert_eq!(
            converter_model(&root, "standard"),
            root.join("models").join(NANO_Q8)
        );
        assert_eq!(
            converter_model(&root, "nano"),
            root.join("models").join(NANO_IQ4)
        );
        assert_eq!(corrector_model(&root), root.join("models").join(CORRECTOR));
        std::fs::remove_file(root.join("models").join(NANO_IQ4)).unwrap();
        assert!(!converter_model(&root, "nano").is_file());
        for name in [NANO_Q8, CORRECTOR] {
            std::fs::remove_file(root.join("models").join(name)).unwrap();
        }
        std::fs::remove_dir(root.join("models")).unwrap();
        std::fs::remove_dir(root).unwrap();
    }

    #[test]
    fn settings_without_model_load_with_nano_v4_default() {
        let config: crate::ZenzaiConfig =
            serde_json::from_str(r#"{"enable":true,"profile":"","backend":"vulkan"}"#).unwrap();
        assert_eq!(config.model, "nano_v4_iq4_emb8");
    }
}
