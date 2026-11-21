use std::path::{Path, PathBuf};

pub fn backend_directory(_backend: &str) -> &'static str {
    // CPU and Vulkan share the unified `llama` directory; the difference is
    // only the `-ngl` layer count. Kept for callers that need the directory
    // name; `server_path` falls back to legacy `llama_cpu`/`llama_vulkan`
    // via `runtime_assets::backend` when `llama/` is absent.
    "llama"
}

pub fn find_server(roots: &[PathBuf], backend: &str) -> Option<PathBuf> {
    roots
        .iter()
        .map(|root| server_path(root, backend))
        .find(|p| p.is_file())
}

fn server_path(root: &Path, backend: &str) -> PathBuf {
    shared::runtime_assets::backend(root, backend).join("llama-server.exe")
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn backend_directory_maps_vulkan_and_cpu() {
        // Non-existent install root has no unified dir, so backend() falls
        // back to the legacy per-backend directory.
        assert_eq!(
            server_path(Path::new("install"), "vulkan"),
            Path::new("install/llama_vulkan/llama-server.exe")
        );
        assert_eq!(backend_directory("Vulkan"), "llama");
        assert_eq!(backend_directory("cuda"), "llama");
        assert_eq!(backend_directory("cpu"), "llama");
    }

    #[test]
    fn selected_backend_and_current_install_take_priority_over_legacy_root() {
        let root =
            std::env::temp_dir().join(format!("zerocon-benchmark-test-{}", std::process::id()));
        let current = root.join("current");
        let legacy = root.join("legacy");
        let vulkan = server_path(&current, "vulkan");
        let fallback_vulkan = server_path(&legacy, "vulkan");
        for path in [&vulkan, &fallback_vulkan] {
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, b"test fixture").unwrap();
        }
        assert_eq!(
            find_server(&[current.clone(), legacy.clone()], "vulkan"),
            Some(vulkan.clone())
        );
        assert_eq!(find_server(&[current.clone(), legacy.clone()], "cpu"), None);
        std::fs::remove_file(vulkan).unwrap();
        std::fs::remove_file(fallback_vulkan).unwrap();
    }
}
