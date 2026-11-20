//! English completion dictionary download.
//!
//! The word list is not bundled: the settings app fetches the pinned upstream
//! `large_en.msgpack.gz` (1.4 MiB) and filters it on this machine, so no
//! CC BY-SA data is redistributed with the installer.
use anyhow::{bail, Context};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tauri::Emitter;

/// Upper bound for the decompressed payload; the real file is ~2.6 MiB.
const MAX_DECOMPRESSED_BYTES: u64 = 32 * 1024 * 1024;

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
    pub path: Option<String>,
    pub words: usize,
    pub download_url: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub license: String,
    pub attribution: String,
}

#[derive(Clone, Serialize)]
struct Progress {
    received: u64,
    total: u64,
}

pub fn cache_file() -> PathBuf {
    shared::english_dictionary::cache_path(&shared::english_dictionary::windows_dictionary_root())
}

pub fn status() -> Status {
    let path = cache_file();
    let words = shared::english_dictionary::load(&path)
        .map(|catalog| catalog.len())
        .unwrap_or(0);
    Status {
        installed: words > 0,
        path: path.to_str().map(str::to_string),
        words,
        download_url: shared::english_dictionary::SOURCE_URL.to_string(),
        size_bytes: shared::english_dictionary::SOURCE_BYTES,
        sha256: shared::english_dictionary::SOURCE_SHA256.to_string(),
        license: shared::english_dictionary::SOURCE_LICENSE.to_string(),
        attribution: shared::english_dictionary::SOURCE_ATTRIBUTION.to_string(),
    }
}

pub async fn install(app: tauri::AppHandle) -> Result<Status, String> {
    if DOWNLOADING
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        return Err("英語辞書のダウンロードは既に実行中です".to_string());
    }
    let _guard = DownloadGuard;
    if status().installed {
        return Ok(status());
    }
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(600))
        .build()
        .map_err(|error| error.to_string())?;
    let payload = fetch(&client, &app)
        .await
        .map_err(|error| format!("{error:#}"))?;
    let raw = gunzip(&payload).map_err(|error| format!("{error:#}"))?;
    let path = cache_file();
    let words = shared::english_dictionary::import(
        &raw,
        &path,
        shared::english_dictionary::DEFAULT_WORD_LIMIT,
    )?;
    let _ = app.emit(
        "english-dictionary-download-progress",
        Progress {
            received: shared::english_dictionary::SOURCE_BYTES,
            total: shared::english_dictionary::SOURCE_BYTES,
        },
    );
    let mut status = status();
    status.words = words;
    status.installed = words > 0;
    Ok(status)
}

async fn fetch(client: &reqwest::Client, app: &tauri::AppHandle) -> anyhow::Result<Vec<u8>> {
    let expected = shared::english_dictionary::SOURCE_BYTES;
    let mut response = client
        .get(shared::english_dictionary::SOURCE_URL)
        .send()
        .await?
        .error_for_status()?;
    let total = response.content_length().unwrap_or(expected);
    let mut payload = Vec::with_capacity(expected as usize);
    let mut hash = Sha256::new();
    let mut received = 0u64;
    let mut last_emit = Instant::now() - Duration::from_millis(1);
    while let Some(chunk) = response.chunk().await? {
        received += chunk.len() as u64;
        if received > expected.saturating_mul(2).max(1_000_000) {
            bail!("ダウンロードサイズが想定を超えました; 破損した可能性があります");
        }
        payload.extend_from_slice(&chunk);
        hash.update(&chunk);
        if last_emit.elapsed() >= Duration::from_millis(120) {
            let _ = app.emit(
                "english-dictionary-download-progress",
                Progress { received, total },
            );
            last_emit = Instant::now();
        }
    }
    let actual = format!("{:x}", hash.finalize());
    if !actual.eq_ignore_ascii_case(shared::english_dictionary::SOURCE_SHA256) {
        bail!(
            "チェックサム不一致 (期待 {}, 実際 {actual})",
            shared::english_dictionary::SOURCE_SHA256
        );
    }
    Ok(payload)
}

fn gunzip(payload: &[u8]) -> anyhow::Result<Vec<u8>> {
    let decoder = flate2::read::GzDecoder::new(payload);
    let mut decoded = Vec::with_capacity(4 * 1024 * 1024);
    decoder
        .take(MAX_DECOMPRESSED_BYTES + 1)
        .read_to_end(&mut decoded)
        .context("英語辞書の展開に失敗しました")?;
    if decoded.len() as u64 > MAX_DECOMPRESSED_BYTES {
        bail!("英語辞書の展開サイズが想定を超えました");
    }
    Ok(decoded)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_reports_uninstalled_when_cache_is_absent() {
        // The cache file is per machine; this only asserts the shape of the
        // payload used by the settings UI.
        let status = status();
        assert_eq!(status.download_url, shared::english_dictionary::SOURCE_URL);
        assert_eq!(status.sha256, shared::english_dictionary::SOURCE_SHA256);
        assert_eq!(status.size_bytes, shared::english_dictionary::SOURCE_BYTES);
        assert_eq!(status.installed, status.words > 0);
        assert!(status.path.is_some());
    }

    #[test]
    fn gunzip_rejects_corrupt_payloads() {
        assert!(gunzip(b"not gzip").is_err());
    }
}
