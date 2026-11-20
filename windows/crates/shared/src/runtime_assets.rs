//! Asset names and locations shared by launcher and settings. No downloads here.
use std::path::{Path, PathBuf};

pub const NANO_IQ4: &str = "zerocon-nano-v4.1-iq4nl-emb8.gguf";
pub const NANO_Q8: &str = "zerocon-nano-v4.1-q8_0.gguf";
pub const STANDARD_IQ4: &str = "zerocon-standard-v4.1-iq4nl-emb8.gguf";
pub const STANDARD_Q8: &str = "zerocon-standard-v4.1-q8_0.gguf";
pub const TRANSLATION_0_8B: &str = "plamo-3-translate-0.8b-Q4_K_M.gguf";
pub const TRANSLATION_2B: &str = "plamo-3-translate-2b-Q4_K_M.gguf";
pub const TRANSLATION: &str = TRANSLATION_0_8B;

pub const TRANSLATION_0_8B_SHA256: &str =
    "923b2f28a9856de1b8b3b610a7e59ded0b14d007466fd5dd526e6a9ecaadafdd";
pub const TRANSLATION_2B_SHA256: &str =
    "1ffd9073afcc70e35ccc942428aa2b73d7aa2b9ea779bf504b775369e0276c13";

///同梱しない翻訳モデルのHugging Face配布URL（ピン留め版）。
pub const TRANSLATION_0_8B_URL: &str = "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/dbeb0856cb0b004b484f190bb71dd65876297013/plamo-3-translate-0.8b-Q4_K_M.gguf";
pub const TRANSLATION_2B_URL: &str = "https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/resolve/4f672106b847200bfdc0f6af6c321cd94eba1aed/plamo-3-translate-2b-Q4_K_M.gguf";

pub const NANO_IQ4_SHA256: &str =
    "6a99ca4e1c46b3f6fb86dcf884ea0652c967d86d606c12268b33d894d3853b24";
pub const NANO_Q8_SHA256: &str = "001d2cf3acd75343d2dd4ea11472e28258bc7429d7d46091b50a78776dead378";
pub const STANDARD_IQ4_SHA256: &str =
    "61af7b70aaf664a7c5b380bc373149fe0e0c67122c697f7127d7e8985fdbfb41";
pub const STANDARD_Q8_SHA256: &str =
    "5065b64d9d0cabdd41bef8cd748863adb84691426be24d2a9be95fa23269c8ca";

pub const NANO_IQ4_BYTES: u64 = 19_602_048;
pub const NANO_Q8_BYTES: u64 = 29_039_232;
pub const STANDARD_IQ4_BYTES: u64 = 61_399_072;
pub const STANDARD_Q8_BYTES: u64 = 103_866_400;

/// 変換モデルは配布物に同梱せず、GitHubリリースの固定アセットから取得する。
/// タグは `windows/deployment/model-catalog.json` の `releaseTag` と合わせる。
pub const MODEL_RELEASE_REPOSITORY: &str = "g0083/ZeroConvert-IME";
pub const MODEL_RELEASE_TAG: &str = "models-v4.1";

pub fn model_download_url(id: &str) -> Option<String> {
    let filename = model_filename(id)?;
    Some(format!(
        "https://github.com/{MODEL_RELEASE_REPOSITORY}/releases/download/{MODEL_RELEASE_TAG}/{filename}"
    ))
}

pub fn model_sha256(id: &str) -> Option<&'static str> {
    match canonical_model_id(id)? {
        "nano_v4_1_iq4_emb8" => Some(NANO_IQ4_SHA256),
        "nano_v4_1_q8" => Some(NANO_Q8_SHA256),
        "standard_v4_1_iq4_emb8" => Some(STANDARD_IQ4_SHA256),
        "standard_v4_1_q8" => Some(STANDARD_Q8_SHA256),
        _ => None,
    }
}

pub fn model_size_bytes(id: &str) -> Option<u64> {
    match canonical_model_id(id)? {
        "nano_v4_1_iq4_emb8" => Some(NANO_IQ4_BYTES),
        "nano_v4_1_q8" => Some(NANO_Q8_BYTES),
        "standard_v4_1_iq4_emb8" => Some(STANDARD_IQ4_BYTES),
        "standard_v4_1_q8" => Some(STANDARD_Q8_BYTES),
        _ => None,
    }
}

/// 変換モデルの取得先（`%LOCALAPPDATA%\ZeroConIME\models\`）。
/// 翻訳モデルのキャッシュと同一ルートに置き、配布物の `models\` とは区別する。
pub fn model_download_root() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("ZeroConIME")
        .join("models")
}

pub fn conversion_model_path(install: &Path, id: &str) -> PathBuf {
    let filename = model_filename(id).unwrap_or(NANO_IQ4);
    let cached = model_download_root().join(filename);
    if cached.is_file() {
        return cached;
    }
    install.join("models").join(filename)
}

/// 概算サイズ（Bytes）。UIの表示にのみ使用し、検証はSHA-256で行う。
pub const TRANSLATION_0_8B_SIZE_BYTES: u64 = 573_123_200;
pub const TRANSLATION_2B_SIZE_BYTES: u64 = 1_807_203_040;

pub fn translation_model_download_root() -> PathBuf {
    std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
        .join("ZeroConIME")
        .join("models")
}

pub fn translation_model_path(install: &Path, id: &str) -> PathBuf {
    let filename = translation_model_filename(id);
    let bundled = install.join("models").join(filename);
    if bundled.is_file() {
        return bundled;
    }
    translation_model_download_root().join(filename)
}

pub fn translation_model_sha256(id: &str) -> &'static str {
    match translation_model_filename(id) {
        TRANSLATION_0_8B => TRANSLATION_0_8B_SHA256,
        TRANSLATION_2B => TRANSLATION_2B_SHA256,
        _ => "",
    }
}

pub fn translation_model_url(id: &str) -> &'static str {
    match translation_model_filename(id) {
        TRANSLATION_0_8B => TRANSLATION_0_8B_URL,
        TRANSLATION_2B => TRANSLATION_2B_URL,
        _ => TRANSLATION_0_8B_URL,
    }
}

pub fn translation_model_size_hint(id: &str) -> Option<u64> {
    match translation_model_filename(id) {
        TRANSLATION_0_8B => Some(TRANSLATION_0_8B_SIZE_BYTES),
        TRANSLATION_2B => Some(TRANSLATION_2B_SIZE_BYTES),
        _ => None,
    }
}

pub fn translation_model_filename(id: &str) -> &'static str {
    match id {
        "2b" | "plamo-3-translate-2b" => TRANSLATION_2B,
        _ => TRANSLATION_0_8B,
    }
}

pub fn default_translation_model() -> &'static str {
    "0.8b"
}

pub fn default_model() -> String {
    "nano_v4_1_iq4_emb8".into()
}

pub fn canonical_model_id(id: &str) -> Option<&'static str> {
    match id {
        "nano_v4_1_iq4_emb8" | "nano_v4_iq4_emb8" | "nano" | "nano_q4km" => {
            Some("nano_v4_1_iq4_emb8")
        }
        "nano_v4_1_q8" | "nano_v4_q8" | "standard" | "standard_q4km" => Some("nano_v4_1_q8"),
        "standard_v4_1_iq4_emb8" | "standard_v4_iq4_emb8" => Some("standard_v4_1_iq4_emb8"),
        "standard_v4_1_q8" | "standard_v4_q8" | "standard_v4_1" => Some("standard_v4_1_q8"),
        _ => None,
    }
}

pub fn model_filename(id: &str) -> Option<&'static str> {
    match canonical_model_id(id) {
        Some("nano_v4_1_iq4_emb8") => Some(NANO_IQ4),
        Some("nano_v4_1_q8") => Some(NANO_Q8),
        Some("standard_v4_1_iq4_emb8") => Some(STANDARD_IQ4),
        Some("standard_v4_1_q8") => Some(STANDARD_Q8),
        _ => None,
    }
}

pub fn converter_model(install: &Path, id: &str) -> PathBuf {
    // 配布物への同梱はしない。取得済みキャッシュを優先し、旧配布物に残った
    // モデル（更新後も削除される前に存在するもの）だけを互換先として見る。
    // 設定IDの移行も併せて行うが、廃止モデルへは絶対にフォールバックしない。
    conversion_model_path(install, id)
}

pub fn backend(install: &Path, name: &str) -> PathBuf {
    install.join(
        if name.eq_ignore_ascii_case("vulkan") || name.eq_ignore_ascii_case("cuda") {
            "llama_vulkan"
        } else {
            "llama_cpu"
        },
    )
}

/// Selects the ZeroCon runtime backend directory. Both CPU and Vulkan runtimes
/// carry the ZeroCon compatibility patch; keep a `llama_zerocon` override if present.
pub fn zerocon_backend(install: &Path, name: &str) -> PathBuf {
    if !name.eq_ignore_ascii_case("vulkan") && !name.eq_ignore_ascii_case("cuda") {
        let dedicated = install.join("llama_zerocon");
        if dedicated.join("llama-server.exe").is_file() {
            return dedicated;
        }
    }
    backend(install, name)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roles_are_distinct_and_unknown_models_are_rejected() {
        assert_eq!(model_filename("nano_q4km"), Some(NANO_IQ4));
        assert_eq!(model_filename("nano_v4_iq4_emb8"), Some(NANO_IQ4));
        assert_eq!(model_filename("nano_v4_1_iq4_emb8"), Some(NANO_IQ4));
        assert_eq!(model_filename("standard"), Some(NANO_Q8));
        assert_eq!(model_filename("nano_v4_q8"), Some(NANO_Q8));
        assert_eq!(model_filename("nano_v4_1_q8"), Some(NANO_Q8));
        assert_eq!(model_filename("corrector"), None);
        assert_eq!(model_filename("../arbitrary"), None);
        assert_ne!(NANO_IQ4, NANO_Q8);
    }

    #[test]
    fn roles_include_standard_v4_1_and_pinned_download_metadata() {
        assert_eq!(model_filename("standard_v4_1_iq4_emb8"), Some(STANDARD_IQ4));
        assert_eq!(model_filename("standard_v4_1_q8"), Some(STANDARD_Q8));
        assert_ne!(NANO_IQ4, STANDARD_IQ4);
        assert_eq!(model_sha256("nano_v4_1_iq4_emb8"), Some(NANO_IQ4_SHA256));
        assert_eq!(model_sha256("standard_v4_1_q8"), Some(STANDARD_Q8_SHA256));
        assert_eq!(
            model_size_bytes("standard_v4_1_q8"),
            Some(STANDARD_Q8_BYTES)
        );
        let url = model_download_url("nano_v4_1_iq4_emb8").unwrap();
        assert!(url.ends_with("/zerocon-nano-v4.1-iq4nl-emb8.gguf"));
        assert!(url.starts_with(&format!(
            "https://github.com/{MODEL_RELEASE_REPOSITORY}/releases/download/{MODEL_RELEASE_TAG}/"
        )));
        assert!(model_download_url("corrector").is_none());
    }

    #[test]
    fn canonical_models_are_selected_without_copying_the_active_weights() {
        if model_download_root().join(NANO_IQ4).is_file() {
            // 取得済みキャッシュが存在する環境ではそちらが優先されるため検証対象外。
            return;
        }
        let root = std::env::temp_dir().join(format!("zerocon-model-paths-{}", std::process::id()));
        std::fs::create_dir_all(root.join("models")).unwrap();
        for name in [NANO_Q8, NANO_IQ4] {
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
        std::fs::remove_file(root.join("models").join(NANO_IQ4)).unwrap();
        assert_eq!(
            converter_model(&root, "standard_v4_1_iq4_emb8"),
            root.join("models").join(STANDARD_IQ4)
        );
        assert!(!converter_model(&root, "nano").is_file());
        std::fs::remove_file(root.join("models").join(NANO_Q8)).unwrap();
        std::fs::remove_dir(root.join("models")).unwrap();
        std::fs::remove_dir(root).unwrap();
    }

    #[test]
    fn settings_without_model_load_with_nano_v4_1_default() {
        // 共有エンジンの最小クレートでも ZenzaiConfig を前提にしない。
        assert_eq!(default_model(), "nano_v4_1_iq4_emb8");
        assert_eq!(
            canonical_model_id(&default_model()),
            Some("nano_v4_1_iq4_emb8")
        );
    }

    #[test]
    fn translation_model_prefers_bundled_copy_and_falls_back_to_user_cache() {
        let root =
            std::env::temp_dir().join(format!("zerocon-translation-model-{}", std::process::id()));
        std::fs::create_dir_all(root.join("models")).unwrap();
        let bundled = root.join("models").join(TRANSLATION_0_8B);
        std::fs::write(&bundled, []).unwrap();
        assert_eq!(translation_model_path(&root, "0.8b"), bundled);
        std::fs::remove_file(&bundled).unwrap();
        let download_root = std::env::var_os("LOCALAPPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(std::env::temp_dir)
            .join("ZeroConIME/models");
        std::fs::create_dir_all(&download_root).unwrap();
        let cached = download_root.join(TRANSLATION_0_8B);
        std::fs::write(&cached, []).unwrap();
        assert_eq!(translation_model_path(&root, "0.8b"), cached);
        std::fs::remove_file(&cached).unwrap();
        std::fs::remove_dir(root.join("models")).unwrap();
        std::fs::remove_dir(root).unwrap();
    }

    #[test]
    fn translation_sha256_and_urls_are_pinned_per_release_channel() {
        assert_eq!(translation_model_sha256("0.8b"), TRANSLATION_0_8B_SHA256);
        assert_eq!(translation_model_url("0.8b"), TRANSLATION_0_8B_URL);
        assert_eq!(translation_model_sha256("2b"), TRANSLATION_2B_SHA256);
        assert_eq!(translation_model_url("2b"), TRANSLATION_2B_URL);
    }

    #[test]
    fn zerocon_backend_prefers_dedicated_directory_while_translation_keeps_standard_backend() {
        let root = std::env::temp_dir().join(format!("zerocon-dual-llama-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(root.join("llama_cpu")).unwrap();
        std::fs::write(root.join("llama_cpu/llama-server.exe"), []).unwrap();

        assert_eq!(zerocon_backend(&root, "cpu"), root.join("llama_cpu"));
        assert_eq!(backend(&root, "cpu"), root.join("llama_cpu"));

        std::fs::create_dir_all(root.join("llama_zerocon")).unwrap();
        std::fs::write(root.join("llama_zerocon/llama-server.exe"), []).unwrap();

        assert_eq!(zerocon_backend(&root, "cpu"), root.join("llama_zerocon"));
        assert_eq!(backend(&root, "cpu"), root.join("llama_cpu"));
        assert_eq!(zerocon_backend(&root, "vulkan"), root.join("llama_vulkan"));
        assert_eq!(backend(&root, "cuda"), root.join("llama_vulkan"));
        assert_eq!(zerocon_backend(&root, "cuda"), root.join("llama_vulkan"));

        let _ = std::fs::remove_dir_all(&root);
    }
}
