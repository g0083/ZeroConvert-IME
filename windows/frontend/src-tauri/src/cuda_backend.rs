use anyhow::{bail, Context, Result};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::Path,
    sync::atomic::{AtomicBool, Ordering},
    time::Duration,
};
use tauri::Emitter;

const ENGINE_URL: &str = "https://github.com/ggml-org/llama.cpp/releases/download/b12125/llama-b12125-bin-win-cuda-12.4-x64.zip";
const ENGINE_HASH: &str = "9f8b5810501216f7e67e3d9d2b6cf3cd53a75ea8ad1fe23f78e663ed3b5aeabd";
const RUNTIME_URL: &str = "https://github.com/ggml-org/llama.cpp/releases/download/b12125/cudart-llama-bin-win-cuda-12.4-x64.zip";
const RUNTIME_HASH: &str = "8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6";
const DOWNLOAD_BYTES: u64 = 288_916_929 + 391_443_627;
const REQUIRED: &[&str] = &[
    "llama-server.exe",
    "llama.dll",
    "ggml.dll",
    "ggml-base.dll",
    "ggml-cpu-x64.dll",
    "ggml-cuda.dll",
    "ggml-rpc.dll",
    "libomp.dll",
    "cudart64_12.dll",
    "cublas64_12.dll",
    "cublasLt64_12.dll",
];
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
    pub download_bytes: u64,
}
pub fn status() -> Status {
    let path = shared::runtime_assets::cuda_cache();
    Status {
        installed: path.join("installed.json").is_file()
            && REQUIRED.iter().all(|name| path.join(name).is_file()),
        download_bytes: DOWNLOAD_BYTES,
    }
}

#[derive(Clone, Serialize)]
struct Progress {
    received: u64,
    total: u64,
}

async fn download(
    client: &reqwest::Client,
    app: &tauri::AppHandle,
    url: &str,
    expected: &str,
    path: &Path,
    offset: u64,
    limit: u64,
) -> Result<()> {
    let mut response = client.get(url).send().await?.error_for_status()?;
    let mut file = fs::File::create(path)?;
    let mut hash = Sha256::new();
    let mut received = 0u64;
    let mut last = std::time::Instant::now();
    while let Some(chunk) = response.chunk().await? {
        received += chunk.len() as u64;
        if received > limit {
            bail!("CUDA download exceeded expected size");
        }
        file.write_all(&chunk)?;
        hash.update(&chunk);
        if last.elapsed() > Duration::from_millis(200) {
            let _ = app.emit(
                "cuda-download-progress",
                Progress {
                    received: offset + received,
                    total: DOWNLOAD_BYTES,
                },
            );
            last = std::time::Instant::now();
        }
    }
    if received != limit || format!("{:x}", hash.finalize()) != expected {
        bail!("CUDA download checksum mismatch; nothing installed");
    }
    let _ = app.emit(
        "cuda-download-progress",
        Progress {
            received: offset + received,
            total: DOWNLOAD_BYTES,
        },
    );
    Ok(())
}

// Extract only the runtime files we use; no archive-provided paths or executables are run.
fn extract(archive: &Path, destination: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(fs::File::open(archive)?)?;
    for index in 0..zip.len() {
        let mut entry = zip.by_index(index)?;
        if !REQUIRED.contains(&entry.name()) {
            continue;
        }
        if entry.size() > 600_000_000 {
            bail!("CUDA archive entry too large");
        }
        let mut output = fs::File::create(destination.join(entry.name()))?;
        // Bounded streaming, including for the large CUDA BLAS DLLs.
        let mut buffer = [0u8; 64 * 1024];
        loop {
            let count = entry.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            output.write_all(&buffer[..count])?;
        }
    }
    Ok(())
}

pub async fn install(app: tauri::AppHandle, confirmed: bool) -> Result<Status> {
    if !confirmed {
        bail!("CUDA download requires confirmation");
    }
    if DOWNLOADING
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        bail!("CUDA download already running");
    }
    let _guard = DownloadGuard;
    if status().installed {
        return Ok(status());
    }
    let destination = shared::runtime_assets::cuda_cache();
    let root = destination
        .parent()
        .context("CUDA cache directory missing")?;
    fs::create_dir_all(root)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cuda-download-")
        .tempdir_in(root)?;
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(20))
        .timeout(Duration::from_secs(1800))
        .build()?;
    let engine = temporary.path().join("engine.zip");
    let runtime = temporary.path().join("runtime.zip");
    download(
        &client,
        &app,
        ENGINE_URL,
        ENGINE_HASH,
        &engine,
        0,
        192_592_769,
    )
    .await?;
    download(
        &client,
        &app,
        RUNTIME_URL,
        RUNTIME_HASH,
        &runtime,
        192_592_769,
        391_443_627,
    )
    .await?;
    tauri::async_runtime::spawn_blocking(move || -> Result<()> {
        let stage = temporary.path().join("runtime");
        fs::create_dir(&stage)?;
        extract(&engine, &stage)?;
        extract(&runtime, &stage)?;
        for name in REQUIRED {
            if !stage.join(name).is_file() {
                bail!("CUDA runtime file missing: {name}");
            }
        }
        fs::write(
            stage.join("installed.json"),
            format!(
                "{{\"version\":\"{}\",\"engine_sha256\":\"{}\",\"runtime_sha256\":\"{}\"}}",
                shared::runtime_assets::CUDA_VERSION,
                ENGINE_HASH,
                RUNTIME_HASH
            ),
        )?;
        fs::rename(&stage, &destination)
            .context("Cannot publish CUDA backend; existing files were preserved")?;
        Ok(())
    })
    .await??;
    Ok(status())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn archive_cannot_write_arbitrary_paths_or_publish_partial_runtime() {
        let temp = tempfile::tempdir().unwrap();
        let path = temp.path().join("test.zip");
        let mut writer = zip::ZipWriter::new(fs::File::create(&path).unwrap());
        for name in ["../escape.dll", "unrequested.exe", "llama-server.exe"] {
            writer
                .start_file(name, zip::write::SimpleFileOptions::default())
                .unwrap();
            writer.write_all(b"fixture").unwrap();
        }
        writer.finish().unwrap();
        let dest = temp.path().join("stage");
        fs::create_dir(&dest).unwrap();
        extract(&path, &dest).unwrap();
        assert!(dest.join("llama-server.exe").is_file());
        assert!(!temp.path().join("escape.dll").exists());
        assert!(!dest.join("unrequested.exe").exists());
        assert!(!dest.join("installed.json").exists());
    }

    #[test]
    #[ignore = "requires the two pinned archives in .local/downloads"]
    fn pinned_archives_extract_and_server_starts() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let stage_root = root.join("windows/target/installer-tests");
        fs::create_dir_all(&stage_root).unwrap();
        let stage = tempfile::tempdir_in(&stage_root).unwrap();
        for (file, expected) in [
            ("llama-b12125-cuda.zip", ENGINE_HASH),
            ("cudart-b12125.zip", RUNTIME_HASH),
        ] {
            let path = root.join(".local/downloads").join(file);
            let mut input = fs::File::open(&path).unwrap();
            let mut hash = Sha256::new();
            std::io::copy(&mut input, &mut hash).unwrap();
            assert_eq!(format!("{:x}", hash.finalize()), expected);
            extract(&path, stage.path()).unwrap();
        }
        assert!(REQUIRED
            .iter()
            .all(|name| stage.path().join(name).is_file()));
        let output = std::process::Command::new(stage.path().join("llama-server.exe"))
            .arg("--version")
            .current_dir(stage.path())
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
}
