//! 日本語辞書一式（azooKey標準辞書＋追加辞書）のオンデマンド取得。
//!
//! 辞書はインストーラーに同梱しない。初回起動時に `dictionary-catalog.json` の
//! 固定アセット（zip）を取得し、`%LOCALAPPDATA%\ZeroConIME\dictionary\` へ
//! 展開する。取得済みキャッシュのSHA-256が旧版なら再取得する。
//!
//! 展開は「ステージング→置き換え→`.complete`」の順で行い、変換サーバーが
//! 半端な辞書ディレクトリを読むことがないようにする。
use anyhow::{bail, Context};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tauri::Emitter;

/// 破損アセットの無限展開を防ぐ上限。
const MAX_EXTRACTED_BYTES: u64 = 512 * 1024 * 1024;

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

#[derive(Serialize)]
pub struct Status {
    pub installed: bool,
    pub path: Option<String>,
    pub download_url: String,
    pub size_bytes: u64,
    pub files: u64,
    pub sha256: String,
}

/// 試験ビルドの辞書か、現行の配布アセットを取得済みなら準備済みと判定する。
pub fn status(install_dir: &Path) -> Status {
    let dictionary = shared::runtime_assets::current_dictionary_dir(install_dir);
    Status {
        installed: dictionary.is_some(),
        path: dictionary.map(|path| path.to_string_lossy().into_owned()),
        download_url: shared::runtime_assets::dictionary_download_url(),
        size_bytes: shared::runtime_assets::DICTIONARY_ASSET_BYTES,
        files: shared::runtime_assets::DICTIONARY_ASSET_FILES,
        sha256: shared::runtime_assets::DICTIONARY_ASSET_SHA256.to_string(),
    }
}

#[derive(Clone, Serialize)]
struct Progress {
    received: u64,
    total: u64,
    phase: &'static str,
}

fn emit_progress(app: &tauri::AppHandle, received: u64, total: u64, phase: &'static str) {
    let _ = app.emit(
        "dictionary-download-progress",
        Progress {
            received,
            total,
            phase,
        },
    );
}

pub async fn install(install_dir: &Path, app: tauri::AppHandle) -> Result<Status, String> {
    if DOWNLOADING
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        return Err("辞書のダウンロードは既に実行中です".to_string());
    }
    let _guard = DownloadGuard;
    let current = status(install_dir);
    if current.installed {
        return Ok(current);
    }

    let root = shared::runtime_assets::dictionary_download_root();
    std::fs::create_dir_all(&root)
        .map_err(|error| format!("辞書の保存先を作成できません: {error}"))?;
    let archive = root.join("dictionary.zip.part");
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(1800))
        .build()
        .map_err(|error| error.to_string())?;

    fetch(&client, &app, &archive)
        .await
        .map_err(|error| format!("{error:#}"))?;
    emit_progress(
        &app,
        shared::runtime_assets::DICTIONARY_ASSET_BYTES,
        shared::runtime_assets::DICTIONARY_ASSET_BYTES,
        "extract",
    );

    let root_for_extract = root.clone();
    let archive_for_extract = archive.clone();
    let app_for_extract = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        extract(&archive_for_extract, &root_for_extract, &app_for_extract)
    })
    .await
    .map_err(|error| format!("辞書の展開に失敗しました: {error}"))?
    .map_err(|error| format!("{error:#}"))?;

    let _ = std::fs::remove_file(&archive);
    let result = status(install_dir);
    if !result.installed {
        return Err("辞書を展開しましたが、必要なファイルが見つかりません".to_string());
    }
    emit_progress(
        &app,
        shared::runtime_assets::DICTIONARY_ASSET_BYTES,
        shared::runtime_assets::DICTIONARY_ASSET_BYTES,
        "done",
    );
    Ok(result)
}

async fn fetch(
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    destination: &Path,
) -> anyhow::Result<()> {
    fetch_with(client, destination, |received, total, phase| {
        emit_progress(app, received, total, phase)
    })
    .await
}

async fn fetch_with<F>(
    client: &reqwest::Client,
    destination: &Path,
    mut progress: F,
) -> anyhow::Result<()>
where
    F: FnMut(u64, u64, &'static str),
{
    let expected_bytes = shared::runtime_assets::DICTIONARY_ASSET_BYTES;
    let expected_sha256 = shared::runtime_assets::DICTIONARY_ASSET_SHA256;
    let temporary = destination.with_extension("zip.download");
    let mut response = client
        .get(shared::runtime_assets::dictionary_download_url())
        .send()
        .await?
        .error_for_status()?;
    let mut file = std::fs::File::create(&temporary)?;
    let mut hash = Sha256::new();
    let mut received = 0u64;
    let mut last_emit = Instant::now() - Duration::from_millis(1);
    while let Some(chunk) = response.chunk().await? {
        if CANCEL_REQUESTED.load(Ordering::Acquire) {
            drop(file);
            bail!("ダウンロードを中断しました（次回再開できます）");
        }
        received += chunk.len() as u64;
        if received > expected_bytes.saturating_mul(2).max(1_000_000) {
            drop(file);
            let _ = std::fs::remove_file(&temporary);
            bail!("ダウンロードサイズが想定を超えました; 破損した可能性があります");
        }
        file.write_all(&chunk)?;
        hash.update(&chunk);
        if last_emit.elapsed() >= Duration::from_millis(200) {
            progress(received, expected_bytes, "download");
            last_emit = Instant::now();
        }
    }
    file.flush()?;
    drop(file);
    let actual = format!("{:x}", hash.finalize());
    if !actual.eq_ignore_ascii_case(expected_sha256) {
        let _ = std::fs::remove_file(&temporary);
        bail!("チェックサム不一致 (期待 {expected_sha256}, 実際 {actual})");
    }
    std::fs::rename(&temporary, destination)
        .with_context(|| format!("辞書を保存できませんでした: {}", destination.display()))?;
    Ok(())
}

/// zip 内のエントリが `Dictionary/` 配下に収まる相対パスか検証する。
fn relative_entry_path(name: &str) -> anyhow::Result<PathBuf> {
    let normalized = name.replace('\\', "/");
    let mut parts = normalized.split('/');
    if parts.next() != Some(shared::runtime_assets::DICTIONARY_DIR) {
        bail!("辞書アセットに想定外のエントリがあります: {name}");
    }
    let mut relative = PathBuf::new();
    for part in parts {
        if part.is_empty() || part == "." {
            continue;
        }
        if part == ".." {
            bail!("辞書アセットに不正なパスがあります: {name}");
        }
        relative.push(part);
    }
    if relative.as_os_str().is_empty()
        || relative
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        bail!("辞書アセットに不正なパスがあります: {name}");
    }
    Ok(relative)
}

fn extract(archive: &Path, root: &Path, app: &tauri::AppHandle) -> anyhow::Result<()> {
    extract_with(
        archive,
        root,
        shared::runtime_assets::DICTIONARY_ASSET_FILES,
        |extracted, total| emit_progress(app, extracted, total, "extract"),
    )
}

fn extract_with(
    archive: &Path,
    root: &Path,
    expected_files: u64,
    mut report: impl FnMut(u64, u64),
) -> anyhow::Result<()> {
    let staging = root.join("Dictionary.staging");
    let target = root.join(shared::runtime_assets::DICTIONARY_DIR);
    let complete = root.join(".complete");
    if staging.exists() {
        std::fs::remove_dir_all(&staging).context("前回の展開途中のデータを削除できません")?;
    }
    std::fs::create_dir_all(&staging).context("辞書の展開先を作成できません")?;

    let file = std::fs::File::open(archive).context("辞書アセットを開けません")?;
    let mut zip = zip::ZipArchive::new(file).context("辞書アセットが壊れています")?;
    let mut extracted_bytes = 0u64;
    let mut extracted_files = 0u64;
    for index in 0..zip.len() {
        if CANCEL_REQUESTED.load(Ordering::Acquire) {
            let _ = std::fs::remove_dir_all(&staging);
            bail!("辞書の展開を中断しました");
        }
        let mut entry = zip.by_index(index)?;
        if entry.is_dir() {
            continue;
        }
        let relative = relative_entry_path(entry.name())?;
        extracted_bytes += entry.size();
        if extracted_bytes > MAX_EXTRACTED_BYTES {
            let _ = std::fs::remove_dir_all(&staging);
            bail!("辞書の展開サイズが想定を超えました");
        }
        let destination = staging.join(&relative);
        if let Some(parent) = destination.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut output = std::fs::File::create(&destination)?;
        std::io::copy(&mut entry, &mut output)?;
        extracted_files += 1;
        if extracted_files % 256 == 0 {
            report(extracted_files, expected_files);
        }
    }
    if extracted_files != expected_files {
        let _ = std::fs::remove_dir_all(&staging);
        bail!("辞書のファイル数が一致しません (期待 {expected_files}, 実際 {extracted_files})");
    }
    if !staging.join("louds").join("charID.chid").is_file() {
        let _ = std::fs::remove_dir_all(&staging);
        bail!("辞書アセットに louds/charID.chid がありません");
    }

    let previous = root.join("Dictionary.previous");
    if previous.is_dir() {
        if target.exists() {
            std::fs::remove_dir_all(&previous).context("前回の辞書更新の退避を削除できません")?;
        } else {
            std::fs::rename(&previous, &target).context("前回の辞書を復元できません")?;
        }
    }

    let marker = root.join(".complete.staging");
    std::fs::write(&marker, shared::runtime_assets::DICTIONARY_ASSET_SHA256)
        .context("辞書の完了マーカーを書き込めません")?;
    if target.exists() {
        std::fs::rename(&target, &previous).context("既存の辞書を退避できません")?;
    }
    if let Err(error) = std::fs::rename(&staging, &target) {
        if previous.is_dir() {
            std::fs::rename(&previous, &target).context("旧辞書を復元できません")?;
        }
        return Err(error).context("辞書を配置できませんでした");
    }
    if let Err(error) = std::fs::rename(&marker, &complete) {
        std::fs::rename(&target, &staging).context("辞書更新を取り消せません")?;
        if previous.is_dir() {
            std::fs::rename(&previous, &target).context("旧辞書を復元できません")?;
        }
        return Err(error).context("辞書の完了マーカーを更新できません");
    }
    if previous.is_dir() {
        let _ = std::fs::remove_dir_all(&previous);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entry_paths_stay_inside_the_dictionary_directory() {
        assert_eq!(
            relative_entry_path("Dictionary/louds/charID.chid").unwrap(),
            PathBuf::from("louds/charID.chid")
        );
        assert_eq!(
            relative_entry_path("Dictionary\\Reverse\\reverse-v2.bin").unwrap(),
            PathBuf::from("Reverse/reverse-v2.bin")
        );
        assert!(relative_entry_path("Dictionary/../escape.bin").is_err());
        assert!(relative_entry_path("Dictionary").is_err());
        assert!(relative_entry_path("Other/louds/charID.chid").is_err());
        assert!(relative_entry_path("/Dictionary/louds/charID.chid").is_err());
    }

    #[test]
    fn status_reports_missing_dictionary_for_an_empty_install() {
        let install =
            std::env::temp_dir().join(format!("zerocon-dict-status-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&install);
        std::fs::create_dir_all(&install).unwrap();
        // キャッシュが実在する環境ではそちらが優先されるため、未取得時だけ検証する。
        if shared::runtime_assets::cached_dictionary_dir().is_none() {
            assert!(!status(&install).installed);
        }
        let _ = std::fs::remove_dir_all(&install);
    }

    fn write_archive(path: &Path, entries: &[(&str, &[u8])]) {
        let file = std::fs::File::create(path).unwrap();
        let mut writer = zip::ZipWriter::new(file);
        for (name, body) in entries {
            writer
                .start_file::<_, ()>(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            writer.write_all(body).unwrap();
        }
        writer.finish().unwrap();
    }

    /// 公開済みアセットの取得確認。`ZEROCON_DICTIONARY_TEST_NETWORK=1` の時だけ実行する。
    #[test]
    fn real_dictionary_asset_downloads_and_verifies() {
        if std::env::var("ZEROCON_DICTIONARY_TEST_NETWORK").as_deref() != Ok("1") {
            return;
        }
        let root = std::env::temp_dir().join(format!("zerocon-dict-net-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let destination = root.join(shared::runtime_assets::DICTIONARY_ASSET_FILENAME);
        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(600))
            .build()
            .unwrap();
        tauri::async_runtime::block_on(fetch_with(&client, &destination, |_, _, _| {})).unwrap();
        assert_eq!(
            std::fs::metadata(&destination).unwrap().len(),
            shared::runtime_assets::DICTIONARY_ASSET_BYTES
        );
        let _ = std::fs::remove_dir_all(&root);
    }

    /// 実アセットの展開確認。`ZEROCON_DICTIONARY_TEST_ZIP` を設定した時だけ実行する
    /// （配布zipはGit対象外のため、通常のテストでは対象外）。
    #[test]
    fn real_dictionary_asset_extracts_to_the_expected_tree() {
        let Ok(archive) = std::env::var("ZEROCON_DICTIONARY_TEST_ZIP") else {
            return;
        };
        let archive = PathBuf::from(archive);
        if !archive.is_file() {
            return;
        }
        let root = std::env::temp_dir().join(format!("zerocon-dict-real-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        extract_with(
            &archive,
            &root,
            shared::runtime_assets::DICTIONARY_ASSET_FILES,
            |_, _| {},
        )
        .unwrap();
        let dictionary = shared::runtime_assets::cached_dictionary_dir_at(&root).unwrap();
        assert!(dictionary.join("louds/charID.chid").is_file());
        assert!(dictionary.join("Reverse/reverse-v2.bin").is_file());
        assert!(dictionary
            .join("AdditionalDictionaryNotices/LEGAL")
            .is_file());
        assert!(dictionary.join("mm.binary").is_file());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn extraction_replaces_the_previous_dictionary_and_requires_the_completion_marker() {
        let root =
            std::env::temp_dir().join(format!("zerocon-dict-extract-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let archive = root.join("asset.zip");
        write_archive(
            &archive,
            &[
                ("Dictionary/louds/charID.chid", b"chid"),
                ("Dictionary/mm.binary", b"mm"),
                ("Dictionary/Reverse/reverse-v2.bin", b"AZR2"),
            ],
        );

        // A stale dictionary from a previous version must not survive the swap.
        let stale = root.join("Dictionary").join("stale.bin");
        std::fs::create_dir_all(stale.parent().unwrap()).unwrap();
        std::fs::write(&stale, b"old").unwrap();
        std::fs::write(root.join(".complete"), "old-sha256").unwrap();

        let mut reports = 0;
        extract_with(&archive, &root, 3, |_, _| reports += 1).unwrap();
        assert!(!stale.exists());
        assert_eq!(
            std::fs::read(root.join("Dictionary/louds/charID.chid")).unwrap(),
            b"chid"
        );
        assert_eq!(
            std::fs::read(root.join("Dictionary/mm.binary")).unwrap(),
            b"mm"
        );
        assert!(!root.join("Dictionary.staging").exists());
        assert!(!root.join("Dictionary.previous").exists());
        assert!(shared::runtime_assets::cached_dictionary_dir_at(&root).is_some());
        assert_eq!(
            std::fs::read_to_string(root.join(".complete")).unwrap(),
            shared::runtime_assets::DICTIONARY_ASSET_SHA256
        );
        assert_eq!(
            reports, 0,
            "fewer than 256 files report no intermediate progress"
        );

        // A rejected archive leaves the installed dictionary and its marker usable.
        assert!(extract_with(&archive, &root, 4, |_, _| {}).is_err());
        assert!(!root.join("Dictionary.staging").exists());
        assert_eq!(
            std::fs::read_to_string(root.join(".complete")).unwrap(),
            shared::runtime_assets::DICTIONARY_ASSET_SHA256
        );
        assert_eq!(
            std::fs::read(root.join("Dictionary/mm.binary")).unwrap(),
            b"mm"
        );

        // Entries that escape Dictionary/ are rejected before anything is written.
        write_archive(&archive, &[("Dictionary/../../escape.bin", b"x")]);
        assert!(extract_with(&archive, &root, 1, |_, _| {}).is_err());
        assert!(!root.join("escape.bin").exists());

        let _ = std::fs::remove_dir_all(&root);
    }
}
