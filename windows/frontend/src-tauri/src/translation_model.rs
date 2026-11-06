use anyhow::{bail, Context};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tauri::Emitter;

static DOWNLOADING: AtomicBool = AtomicBool::new(false);
struct DownloadGuard;
impl Drop for DownloadGuard {
    fn drop(&mut self) {
        DOWNLOADING.store(false, Ordering::Release);
    }
}

#[derive(Serialize)]
pub struct Status {
    pub installed: bool,
    pub filename: String,
    pub path: Option<String>,
    pub download_url: String,
    pub size_bytes: Option<u64>,
    pub sha256: String,
}

/// 配布パッケージに同梱されたモデルと、ユーザー領域にダウンロードされた
/// モデルの両方を解決する。launcher と同じ優先順位になるよう shared を通す。
pub fn status(install_dir: &std::path::Path, model_id: &str) -> Status {
    let filename = shared::runtime_assets::translation_model_filename(model_id);
    let path = shared::runtime_assets::translation_model_path(install_dir, model_id);
    Status {
        installed: path.is_file(),
        filename: filename.to_string(),
        path: path.to_str().map(str::to_string),
        download_url: shared::runtime_assets::translation_model_url(model_id).to_string(),
        size_bytes: shared::runtime_assets::translation_model_size_hint(model_id),
        sha256: shared::runtime_assets::translation_model_sha256(model_id).to_string(),
    }
}

#[derive(Clone, Serialize)]
struct Progress {
    received: u64,
    total: u64,
}

async fn download_to(
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    url: &str,
    expected_sha256: &str,
    size_hint: u64,
    destination: &PathBuf,
) -> anyhow::Result<()> {
    let parent = destination
        .parent()
        .context("モデル保存先の親ディレクトリが不正です")?;
    std::fs::create_dir_all(parent)?;
    let temporary = destination.with_extension("gguf.part");

    let mut response = client.get(url).send().await?.error_for_status()?;
    let mut file = std::fs::File::create(&temporary)?;
    let mut hash = Sha256::new();
    let mut received = 0u64;
    let mut last_emit = std::time::Instant::now() - Duration::from_millis(1);
    while let Some(chunk) = response.chunk().await? {
        received += chunk.len() as u64;
        if received > size_hint.saturating_mul(2).max(1_000_000_000) {
            let _ = std::fs::remove_file(&temporary);
            bail!("ダウンロードサイズが想定を超えました; 破損した可能性があります");
        }
        file.write_all(&chunk)?;
        hash.update(&chunk);
        if last_emit.elapsed() >= Duration::from_millis(200) {
            let _ = app.emit(
                "translation-model-download-progress",
                Progress {
                    received,
                    total: size_hint,
                },
            );
            last_emit = std::time::Instant::now();
        }
    }
    let actual = format!("{:x}", hash.finalize());
    if !actual.eq_ignore_ascii_case(expected_sha256) {
        let _ = std::fs::remove_file(&temporary);
        bail!("チェックサム不一致 (期待 {expected_sha256}, 実際 {actual})");
    }
    let _ = app.emit(
        "translation-model-download-progress",
        Progress {
            received,
            total: size_hint,
        },
    );
    std::fs::rename(&temporary, destination)
        .with_context(|| format!("モデルを配置できませんでした: {}", destination.display()))?;
    Ok(())
}

pub async fn install(
    install_dir: &std::path::Path,
    app: tauri::AppHandle,
    model_id: &str,
) -> Result<Status, String> {
    if DOWNLOADING
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        return Err("翻訳モデルのダウンロードは既に実行中です".to_string());
    }
    let _guard = DownloadGuard;
    let expected_sha256 = shared::runtime_assets::translation_model_sha256(model_id);
    let size_hint = shared::runtime_assets::translation_model_size_hint(model_id)
        .ok_or_else(|| "モデルサイズが不明です".to_string())?;
    let current = status(install_dir, model_id);
    if current.installed {
        return Ok(current);
    }
    let destination = shared::runtime_assets::translation_model_path(install_dir, model_id);
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(1800))
        .build()
        .map_err(|error| error.to_string())?;
    download_to(
        &client,
        &app,
        shared::runtime_assets::translation_model_url(model_id),
        expected_sha256,
        size_hint,
        &destination,
    )
    .await
    .map_err(|error| format!("{error:#}"))?;
    Ok(status(install_dir, model_id))
}
