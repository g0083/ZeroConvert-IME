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

const CHOICES: [Choice; 4] = [
    Choice {
        id: "nano_v4_2_iq4_emb8",
        name: "ZeroCon Model Nano v4.2 IQ4_emb8",
        filename: shared::runtime_assets::NANO_IQ4,
        legacy: "nano-v4-cap-nam-r6-iq4nl-emb8.gguf",
        description: "省メモリ · IQ4_NL / 入出力Q8_0 · 18.7 MiB · 強制",
    },
    Choice {
        id: "nano_v4_2_q8",
        name: "ZeroCon Model Nano v4.2 Q8",
        filename: shared::runtime_assets::NANO_Q8,
        legacy: "nano-v4-cap-nam-r6-q8_0.gguf",
        description: "高精度 · Q8_0 · 27.7 MiB",
    },
    Choice {
        id: "standard_v4_2_iq4_emb8",
        name: "ZeroCon Model Standard v4.2 IQ4_emb8",
        filename: shared::runtime_assets::STANDARD_IQ4,
        legacy: "standard-v4-cap-nam-r6-iq4nl-emb8.gguf",
        description: "中規模 · IQ4_NL / 入出力Q8_0 · 58.5 MiB",
    },
    Choice {
        id: "standard_v4_2_q8",
        name: "ZeroCon Model Standard v4.2 Q8",
        filename: shared::runtime_assets::STANDARD_Q8,
        legacy: "standard-v4-cap-nam-r6-q8_0.gguf",
        description: "中規模 · Q8_0 · 99.0 MiB",
    },
];

/// `available` の判定は `shared::runtime_assets::conversion_model_path` と同じ順序。
fn model_is_available(choice: &Choice, install_dir: &Path, cache_root: &Path) -> bool {
    cache_root.join(choice.filename).is_file()
        || install_dir.join("models").join(choice.filename).is_file()
}

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
    let id = shared::runtime_assets::canonical_model_id(id)
        .ok_or_else(|| format!("Unknown model_id: {id}"))?;
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
/// `available` は利用者表示用に「キャッシュ（ダウンロード先）またはインストール先の
/// `models\` に実ファイルがある」場合だけ true とする。開発用の artifacts / 試験環境
/// の追加フォルダで見つかるファイルは、一覧では「未取得」として扱う（削除後に
/// ダウンロード導線へ戻すため）。
pub fn list(
    active_path: &Path,
    install_dir: &Path,
    directories: &[PathBuf],
) -> io::Result<ModelListResponse> {
    list_with_cache_root(
        active_path,
        install_dir,
        directories,
        &shared::runtime_assets::model_download_root(),
    )
}

/// `cache_root` は取得済みモデルの保存先（`%LOCALAPPDATA%\ZeroConIME\models`）。
/// テストが実機キャッシュの有無に左右されないよう分離してある。
fn list_with_cache_root(
    active_path: &Path,
    install_dir: &Path,
    directories: &[PathBuf],
    cache_root: &Path,
) -> io::Result<ModelListResponse> {
    let active_size = match active_path.metadata() {
        Ok(metadata) => Some(metadata.len()),
        // 未取得・削除済みなど、ファイルが無い/開けない場合は「未選択」として扱う。
        Err(_) => None,
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
        let catalog_size = shared::runtime_assets::model_size_bytes(choice.id).unwrap_or(0);
        models.push(ModelInfo {
            id: choice.id.into(),
            name: choice.name.into(),
            filename: choice.filename.into(),
            size_mb: size
                .map(|s| s as f64 / (1024.0 * 1024.0))
                .unwrap_or(catalog_size as f64 / (1024.0 * 1024.0)),
            description: choice.description.into(),
            is_active,
            available: model_is_available(choice, install_dir, cache_root),
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
        let cache = dir.path().join("cache");
        std::fs::create_dir_all(&cache).unwrap();
        std::fs::write(dir.path().join(CHOICES[0].filename), b"nano-iq4").unwrap();
        std::fs::write(dir.path().join(CHOICES[1].filename), b"nano-q8").unwrap();
        std::fs::write(dir.path().join(CHOICES[2].filename), b"standard-iq4").unwrap();
        std::fs::write(dir.path().join(CHOICES[3].filename), b"standard-q8").unwrap();
        let active = dir.path().join("zenz.gguf");
        for choice in &CHOICES {
            std::fs::copy(source_for(choice.id, &dirs).unwrap(), &active).unwrap();
            let result = list_with_cache_root(&active, dir.path(), &dirs, &cache).unwrap();
            assert_eq!(result.active_id, choice.id);
            assert_eq!(result.models.iter().filter(|m| m.is_active).count(), 1);
        }
        std::fs::write(&active, b"retired!").unwrap();
        assert!(list_with_cache_root(&active, dir.path(), &dirs, &cache)
            .unwrap()
            .active_id
            .is_empty());

        // 開発用 artifacts のコピーが検索ディレクトリにあるだけでは available にしない。
        let artifact_only = list_with_cache_root(&active, dir.path(), &dirs, &cache).unwrap();
        assert!(artifact_only.models.iter().all(|m| !m.available));
        // キャッシュ（install=models）にあるファイルは available。
        std::fs::create_dir_all(dir.path().join("models")).unwrap();
        std::fs::write(
            dir.path().join("models").join(CHOICES[0].filename),
            b"cached",
        )
        .unwrap();
        let cached = list_with_cache_root(&active, dir.path(), &[], &cache).unwrap();
        assert!(cached.models[0].available && !cached.models[1].available);
        // 取得済みキャッシュ（download root）にあれば、インストール先に無くても available。
        std::fs::write(cache.join(CHOICES[2].filename), b"downloaded").unwrap();
        let downloaded = list_with_cache_root(&active, dir.path(), &[], &cache).unwrap();
        assert!(downloaded.models[2].available);
        std::fs::remove_dir_all(dir.path().join("models")).unwrap();
        assert!(source_for("iq4xs", &dirs).is_err());
    }
    #[test]
    fn missing_nano_is_unavailable_and_never_resolves_to_corrector() {
        let dir = tempfile::tempdir().unwrap();
        let dirs = vec![dir.path().to_path_buf()];
        let cache = dir.path().join("cache");
        std::fs::create_dir_all(&cache).unwrap();
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
        let result =
            list_with_cache_root(&dir.path().join("zenz.gguf"), &dirs[0], &dirs, &cache).unwrap();
        assert!(result.active_id.is_empty());
        assert_eq!(result.models.len(), 4);
        assert!(result.models.iter().all(|model| !model.available));
        assert!(source_for("nano_q4km", &dirs).is_err());
    }
}
