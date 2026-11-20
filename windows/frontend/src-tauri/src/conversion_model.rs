//! 変換モデル（ZeroCon Model Nano / Standard v4.1）のダウンロードと状態管理。
//!
//! モデルはインストーラー・APKに同梱しない。設定画面で必要に応じて
//! GitHubリリースのピン留めアセットから取得し、`%LOCALAPPDATA%\ZeroConIME\models\`
//! に置く。英字辞書・翻訳モデルと同じ方式（サイズ＋SHA-256検証、進捗イベント）。
use anyhow::{bail, Context};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tauri::Emitter;

static DOWNLOADING: AtomicBool = AtomicBool::new(false);
static CANCEL_REQUESTED: AtomicBool = AtomicBool::new(false);
struct DownloadGuard;
impl Drop for DownloadGuard {
    fn drop(&mut self) {
        DOWNLOADING.store(false, Ordering::Release);
        CANCEL_REQUESTED.store(false, Ordering::Release);
    }
}

/// 取得中のダウンロードを中断する（部分ファイルは残し、次回は再開できる）。
pub fn cancel() {
    CANCEL_REQUESTED.store(true, Ordering::Release);
}

/// 取得済みキャッシュモデルを削除する。必須モデル（Nano v4.1 IQ4_emb8）は削除できない。
pub fn delete(install_dir: &std::path::Path, model_id: &str) -> Result<Status, String> {
    let canonical = shared::runtime_assets::canonical_model_id(model_id)
        .ok_or_else(|| format!("Unknown model_id: {model_id}"))?;
    if canonical == "nano_v4_1_iq4_emb8" {
        return Err("必須モデル（Nano v4.1 IQ4_emb8）は削除できません".to_string());
    }
    let filename = shared::runtime_assets::model_filename(canonical)
        .ok_or_else(|| "モデルファイル名が解決できません".to_string())?;
    let cached = shared::runtime_assets::model_download_root().join(filename);
    if cached.is_file() {
        std::fs::remove_file(&cached)
            .map_err(|error| format!("モデルの削除に失敗しました: {error}"))?;
        let part = cached.with_extension("gguf.part");
        let _ = std::fs::remove_file(part);
    }
    // 試験環境など旧配布物に残った `models\<filename>` も表示側のフォールバックに
    // なるため、表示が元に戻らないよう合わせて削除する（本配布物には元々ない）。
    let fallback = install_dir.join("models").join(&filename);
    if fallback.is_file() {
        std::fs::remove_file(&fallback)
            .map_err(|error| format!("モデルの削除に失敗しました: {error}"))?;
    }
    status(install_dir, canonical)
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

/// 設定画面のダウンロード対象。インストーラー同梱物と取得済みキャッシュの
/// 両方を解決する順序は `shared::runtime_assets::conversion_model_path` と合わせる。
pub fn status(install_dir: &std::path::Path, model_id: &str) -> Result<Status, String> {
    let canonical = shared::runtime_assets::canonical_model_id(model_id)
        .ok_or_else(|| format!("Unknown model_id: {model_id}"))?;
    let filename = shared::runtime_assets::model_filename(canonical)
        .ok_or("モデルファイル名が解決できません")
        .map(str::to_string)?;
    let path = shared::runtime_assets::conversion_model_path(install_dir, canonical);
    Ok(Status {
        installed: path.is_file(),
        filename,
        path: path.to_str().map(str::to_string),
        download_url: shared::runtime_assets::model_download_url(canonical).unwrap_or_default(),
        size_bytes: shared::runtime_assets::model_size_bytes(canonical),
        sha256: shared::runtime_assets::model_sha256(canonical)
            .unwrap_or_default()
            .to_string(),
    })
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
        .context("変換モデルの保存先が不正です")?;
    std::fs::create_dir_all(parent)?;
    let temporary = destination.with_extension("gguf.part");

    let mut response = client.get(url).send().await?.error_for_status()?;
    let mut file = std::fs::File::create(&temporary)?;
    let mut hash = Sha256::new();
    let mut received = 0u64;
    let mut last_emit = std::time::Instant::now() - Duration::from_millis(1);
    while let Some(chunk) = response.chunk().await? {
        if CANCEL_REQUESTED.load(Ordering::Acquire) {
            bail!("ダウンロードを中断しました（次回再開できます）");
        }
        received += chunk.len() as u64;
        if received > size_hint.saturating_mul(2).max(1_000_000) {
            let _ = std::fs::remove_file(&temporary);
            bail!("ダウンロードサイズが想定を超えました; 破損した可能性があります");
        }
        file.write_all(&chunk)?;
        hash.update(&chunk);
        if last_emit.elapsed() >= Duration::from_millis(200) {
            let _ = app.emit(
                "conversion-model-download-progress",
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
        "conversion-model-download-progress",
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
    let canonical = shared::runtime_assets::canonical_model_id(model_id)
        .ok_or_else(|| format!("Unknown model_id: {model_id}"))?;
    if DOWNLOADING
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        return Err("変換モデルのダウンロードは既に実行中です".to_string());
    }
    let _guard = DownloadGuard;
    let expected_sha256 = shared::runtime_assets::model_sha256(canonical)
        .ok_or_else(|| "モデルの検証情報が不明です".to_string())?;
    let size_hint = shared::runtime_assets::model_size_bytes(canonical)
        .ok_or_else(|| "モデルサイズが不明です".to_string())?;
    let current = status(install_dir, canonical)?;
    if current.installed {
        return Ok(current);
    }
    let filename = shared::runtime_assets::model_filename(canonical)
        .ok_or_else(|| "モデルファイル名が解決できません".to_string())?;
    let destination = shared::runtime_assets::model_download_root().join(filename);
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(1800))
        .build()
        .map_err(|error| error.to_string())?;
    download_to(
        &client,
        &app,
        &shared::runtime_assets::model_download_url(canonical)
            .ok_or_else(|| "ダウンロードURLが解決できません".to_string())?,
        expected_sha256,
        size_hint,
        &destination,
    )
    .await
    .map_err(|error| format!("{error:#}"))?;
    status(install_dir, canonical)
}
