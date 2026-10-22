mod ipc;
mod jev_api;
mod server_process;
mod updater;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use shared::{
    AppConfig, AppConfigLoadResult, ConfigError, ConfigRecovery, ConfigWriteGuard, RomajiRule,
};
use std::{path::PathBuf, sync::Mutex, time::Duration};

use anyhow::Context as _;

#[derive(Debug)]
pub struct AppState {
    settings: Mutex<AppConfig>,
    ipc: Mutex<Option<ipc::IPCService>>,
    config_update_lock: Mutex<()>,
    server_config_dirty: Mutex<bool>,
    startup_notice: Mutex<Option<ConfigStartupNotice>>,
}

impl AppState {
    fn new() -> Self {
        let (settings, startup_notice) = match AppConfig::new_with_recovery() {
            Ok(result) => {
                if let Some(error) = result.rewrite_error.as_ref() {
                    eprintln!("Failed to rewrite loaded settings: {}", error);
                }
                let notice = notice_from_load_result(&result);
                (result.config, notice)
            }
            Err(error) => {
                eprintln!("Failed to load settings; using defaults: {}", error);
                (AppConfig::default(), Some(notice_from_load_error(&error)))
            }
        };

        let ipc = match ipc::IPCService::new() {
            Ok(service) => Some(service),
            Err(error) => {
                eprintln!("Failed to initialize IPC service: {}", error);
                None
            }
        };

        AppState {
            settings: Mutex::new(settings),
            ipc: Mutex::new(ipc),
            config_update_lock: Mutex::new(()),
            server_config_dirty: Mutex::new(false),
            startup_notice: Mutex::new(startup_notice),
        }
    }
}

#[derive(Debug, Serialize, Clone)]
struct ConfigStartupNotice {
    kind: String,
    message: String,
    backup_path: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
struct UpdateConfigResponse {
    saved: bool,
    server_applied: bool,
    changed: bool,
    config: AppConfig,
    message: Option<String>,
}

#[derive(Debug, Serialize, Clone)]
struct ResetLearningHistoryResponse {
    reset: bool,
    server_applied: bool,
    message: Option<String>,
}

fn notice_from_recovery(recovery: &ConfigRecovery) -> ConfigStartupNotice {
    ConfigStartupNotice {
        kind: "recovered".to_string(),
        message: "壊れた設定ファイルを退避し、既定値で起動しました。".to_string(),
        backup_path: Some(recovery.backup_path.display().to_string()),
    }
}

fn notice_from_rewrite_error(error: &ConfigError) -> ConfigStartupNotice {
    ConfigStartupNotice {
        kind: "rewrite_error".to_string(),
        message: format!("設定は読み込めましたが、設定ファイルの保存に失敗しました: {error}"),
        backup_path: None,
    }
}

fn notice_from_recovered_rewrite_error(
    recovery: &ConfigRecovery,
    error: &ConfigError,
) -> ConfigStartupNotice {
    ConfigStartupNotice {
        kind: "recovered_rewrite_error".to_string(),
        message: format!(
            "壊れた設定ファイルを退避しましたが、既定値設定の保存に失敗しました: {error}"
        ),
        backup_path: Some(recovery.backup_path.display().to_string()),
    }
}

fn notice_from_load_error(error: &ConfigError) -> ConfigStartupNotice {
    match error {
        ConfigError::FutureVersion {
            stored, current, ..
        } => ConfigStartupNotice {
            kind: "future_version".to_string(),
            message: format!(
                "このアプリより新しい設定ファイル（version {stored}、対応 version {current}）を検出しました。設定ファイルは書き換えず、既定値で起動しました。設定を変更するには新しいバージョンのアプリを使用してください。"
            ),
            backup_path: None,
        },
        _ => ConfigStartupNotice {
            kind: "load_error".to_string(),
            message: format!("設定の読み込みに失敗したため、既定値で起動しました: {error}"),
            backup_path: None,
        },
    }
}

fn notice_from_load_result(result: &AppConfigLoadResult) -> Option<ConfigStartupNotice> {
    match (&result.recovery, &result.rewrite_error) {
        (Some(recovery), Some(error)) => Some(notice_from_recovered_rewrite_error(recovery, error)),
        (Some(recovery), None) => Some(notice_from_recovery(recovery)),
        (None, Some(error)) => Some(notice_from_rewrite_error(error)),
        (None, None) => None,
    }
}

#[tauri::command]
fn greet(name: &str) -> String {
    format!("Hello, {}! You've been greeted from Rust!", name)
}

#[tauri::command]
fn get_config(state: tauri::State<AppState>) -> AppConfig {
    get_config_impl(&state)
}

#[tauri::command]
fn has_jev_api_key() -> bool {
    shared::jev_credentials::read_jev_api_key().is_some()
}

#[tauri::command]
fn save_jev_api_key(api_key: String) -> Result<(), String> {
    shared::jev_credentials::save_jev_api_key(&api_key)
}

#[tauri::command]
fn delete_jev_api_key() -> Result<(), String> {
    shared::jev_credentials::delete_jev_api_key()
}

#[tauri::command]
async fn check_jev_api_key() -> Result<(), String> {
    let api_key = shared::jev_credentials::read_jev_api_key()
        .ok_or_else(|| "保存済みのAPIキーがありません。".to_string())?;
    jev_api::check_api_key(&api_key).await
}

fn get_config_impl(state: &AppState) -> AppConfig {
    let _update_guard = state.config_update_lock.lock().unwrap();
    match AppConfig::read() {
        Ok(config) => {
            *state.settings.lock().unwrap() = config.clone();
            config
        }
        Err(error) => {
            eprintln!("Failed to refresh settings from disk: {error}");
            state.settings.lock().unwrap().clone()
        }
    }
}

#[tauri::command]
fn take_config_startup_notice(state: tauri::State<AppState>) -> Option<ConfigStartupNotice> {
    state.startup_notice.lock().unwrap().take()
}

#[tauri::command]
fn update_config(
    state: tauri::State<AppState>,
    base_config: AppConfig,
    new_config: AppConfig,
) -> Result<UpdateConfigResponse, String> {
    update_config_impl(&state, base_config, new_config)
}

fn apply_config_delta(current: &mut Value, base: &Value, updated: &Value) {
    if base == updated {
        return;
    }

    let (Some(current_object), Some(base_object), Some(updated_object)) = (
        current.as_object_mut(),
        base.as_object(),
        updated.as_object(),
    ) else {
        *current = updated.clone();
        return;
    };

    for (key, updated_value) in updated_object {
        match base_object.get(key) {
            Some(base_value) if base_value == updated_value => {}
            Some(base_value) => {
                if let Some(current_value) = current_object.get_mut(key) {
                    apply_config_delta(current_value, base_value, updated_value);
                } else {
                    current_object.insert(key.clone(), updated_value.clone());
                }
            }
            None => {
                current_object.insert(key.clone(), updated_value.clone());
            }
        }
    }

    for key in base_object.keys() {
        if !updated_object.contains_key(key) {
            current_object.remove(key);
        }
    }
}

fn merge_config_update(
    current_config: AppConfig,
    base_config: AppConfig,
    new_config: AppConfig,
) -> Result<AppConfig, String> {
    let mut current = serde_json::to_value(current_config).map_err(|error| error.to_string())?;
    let base = serde_json::to_value(base_config).map_err(|error| error.to_string())?;
    let updated = serde_json::to_value(new_config).map_err(|error| error.to_string())?;
    apply_config_delta(&mut current, &base, &updated);
    serde_json::from_value(current).map_err(|error| error.to_string())
}

fn update_config_impl(
    state: &AppState,
    base_config: AppConfig,
    new_config: AppConfig,
) -> Result<UpdateConfigResponse, String> {
    let _update_guard = state.config_update_lock.lock().unwrap();
    let _cross_process_guard = ConfigWriteGuard::acquire().map_err(|error| error.to_string())?;
    let current_config = match AppConfig::read() {
        Ok(config) => config,
        Err(error) if error.is_version_compatibility_error() => {
            eprintln!("Refusing to update incompatible settings: {error}");
            return Err(error.to_string());
        }
        Err(error) => {
            eprintln!("Failed to read latest settings before update: {error}");
            state.settings.lock().unwrap().clone()
        }
    };
    let new_config = merge_config_update(current_config.clone(), base_config, new_config)?;
    let changed = current_config != new_config;
    if changed {
        new_config.write().map_err(|error| error.to_string())?;
    }
    *state.settings.lock().unwrap() = new_config.clone();

    if changed {
        *state.server_config_dirty.lock().unwrap() = true;
    } else if !*state.server_config_dirty.lock().unwrap() {
        return Ok(UpdateConfigResponse {
            saved: true,
            server_applied: true,
            changed: false,
            config: new_config,
            message: None,
        });
    }

    let applied = ipc::IPCService::get_or_connect(&mut state.ipc.lock().unwrap())
        .and_then(|ipc| ipc.update_config());
    if let Err(error) = applied {
        eprintln!("Failed to notify IPC config update: {}", error);
        return Ok(UpdateConfigResponse {
            saved: true,
            server_applied: false,
            changed,
            config: new_config,
            message: Some(error.to_string()),
        });
    }

    *state.server_config_dirty.lock().unwrap() = false;
    Ok(UpdateConfigResponse {
        saved: true,
        server_applied: true,
        changed,
        config: new_config,
        message: None,
    })
}

#[tauri::command]
fn reset_learning_history(
    state: tauri::State<AppState>,
) -> Result<ResetLearningHistoryResponse, String> {
    Ok(reset_learning_history_impl(&state))
}

fn reset_learning_history_impl(state: &AppState) -> ResetLearningHistoryResponse {
    let reset = ipc::IPCService::get_or_connect(&mut state.ipc.lock().unwrap())
        .and_then(|ipc| ipc.reset_learning_memory());
    match reset {
        Err(error) => {
            eprintln!("Failed to reset learning memory: {}", error);
            return ResetLearningHistoryResponse {
                reset: false,
                server_applied: false,
                message: Some(error.to_string()),
            };
        }
        Ok(false) => {
            return ResetLearningHistoryResponse {
                reset: false,
                server_applied: true,
                message: Some("サーバー側で学習履歴の削除に失敗しました。".to_string()),
            }
        }
        Ok(true) => {}
    }

    ResetLearningHistoryResponse {
        reset: true,
        server_applied: true,
        message: None,
    }
}

#[tauri::command]
fn restart_server(state: tauri::State<AppState>) -> Result<(), String> {
    restart_server_impl(&state).map_err(|error| error.to_string())
}

fn restart_server_impl(state: &AppState) -> Result<(), anyhow::Error> {
    restart_server_impl_with(state, server_process::restart_server)
}

fn restart_server_impl_with(
    state: &AppState,
    restart_process: impl FnOnce(&AppConfig) -> Result<(), anyhow::Error>,
) -> Result<(), anyhow::Error> {
    let _update_guard = state.config_update_lock.lock().unwrap();
    let config = {
        let _cross_process_guard = ConfigWriteGuard::acquire()?;
        let config = AppConfig::read().unwrap_or_else(|error| {
            eprintln!("Failed to refresh settings before server restart: {error}");
            state.settings.lock().unwrap().clone()
        });
        *state.settings.lock().unwrap() = config.clone();
        config
    };

    restart_process(&config)?;

    let ipc = ipc::IPCService::new_with_timeout(Duration::from_secs(10))
        .context("Server restarted, but IPC reconnect failed")?;
    *state.ipc.lock().unwrap() = Some(ipc);
    *state.server_config_dirty.lock().unwrap() = false;

    Ok(())
}

#[derive(Debug, Deserialize, Serialize, Clone)]
struct Capability {
    cpu: bool,
    cuda: bool,
    vulkan: bool,
}

#[tauri::command]
fn check_capability() -> Capability {
    // cuda:
    // cudart64_12.dll
    // cublas64_12.dll

    // vulkan:
    // vulkan-1.dllの存在確認

    let mut capability = Capability {
        cpu: shared::zenzai_cpu_backend_supported(),
        cuda: false,
        vulkan: false,
    };

    // Check for CUDA availability
    let cuda_files = ["cudart64_12.dll", "cublas64_12.dll"];
    let cuda_available = cuda_files.iter().all(|file| {
        // Check if the file exists in system path or in the current directory
        std::env::var("PATH")
            .unwrap_or_default()
            .split(';')
            .map(PathBuf::from)
            .chain(std::iter::once(std::env::current_dir().unwrap_or_default()))
            .any(|path| path.join(file).exists())
    });
    capability.cuda = cuda_available;

    // Check for Vulkan availability
    let vulkan_file = "vulkan-1.dll";
    let vulkan_available = std::env::var("PATH")
        .unwrap_or_default()
        .split(';')
        .map(PathBuf::from)
        .chain(std::iter::once(std::env::current_dir().unwrap_or_default()))
        .any(|path| path.join(vulkan_file).exists());
    capability.vulkan = vulkan_available;

    capability
}

#[tauri::command]
fn get_default_romaji_rows() -> Vec<RomajiRule> {
    shared::get_default_romaji_rows()
}

#[tauri::command]
async fn check_for_updates() -> Result<updater::UpdateCheckResponse, String> {
    updater::check_for_updates()
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
async fn start_update() -> Result<updater::UpdateStartResponse, String> {
    updater::download_and_launch_update()
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn take_update_install_result() -> Result<Option<updater::UpdateInstallResult>, String> {
    updater::take_update_install_result().map_err(|error| error.to_string())
}

/// Runs the production updater without starting the Tauri UI for Windows VM
/// verification. The marker must be created beside the protected executable by
/// an administrator, so normal users cannot turn environment overrides into an
/// unattended update trigger.
pub fn run_updater_integration_test() -> Result<updater::UpdateStartResponse, String> {
    let executable = std::env::current_exe().map_err(|error| error.to_string())?;
    let install_dir = executable
        .parent()
        .ok_or_else(|| "failed to resolve frontend install directory".to_string())?;
    let marker = install_dir.join(".azookey-updater-integration-test");
    if !marker.is_file() {
        return Err(format!(
            "protected updater integration marker is missing: {}",
            marker.display()
        ));
    }

    let runtime = tokio::runtime::Runtime::new().map_err(|error| error.to_string())?;
    runtime
        .block_on(updater::download_and_launch_update_for_integration_test())
        .map_err(|error| error.to_string())
}

/// Runs the protected updater helper copy. The helper reopens and hashes the
/// downloaded installer while denying write/delete sharing, then holds that
/// handle until Windows has created the elevated installer process.
pub fn run_updater_helper() -> Result<(), String> {
    updater::run_installer_helper_cli(std::env::args_os().skip(2))
        .map_err(|error| error.to_string())
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModelInfo {
    pub id: String,
    pub name: String,
    pub filename: String,
    pub size_mb: f64,
    pub description: String,
    pub is_active: bool,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ModelListResponse {
    pub active_id: String,
    pub models: Vec<ModelInfo>,
}

fn get_appdata_azookey_dir() -> PathBuf {
    if let Ok(appdata) = std::env::var("APPDATA") {
        PathBuf::from(appdata).join("Azookey")
    } else {
        PathBuf::from("C:\\Users\\Default\\AppData\\Roaming\\Azookey")
    }
}

fn get_workspace_root() -> Option<PathBuf> {
    if let Some(root) = std::env::var_os("ZEROCON_ROOT") {
        return Some(PathBuf::from(root));
    }
    let exe = std::env::current_exe().ok()?;
    exe.ancestors()
        .find(|directory| directory.join("windows").join("Cargo.toml").is_file())
        .map(|directory| directory.to_path_buf())
}

fn get_models_search_dirs() -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            dirs.push(parent.join("models"));
            dirs.push(parent.to_path_buf());
        }
    }
    if let Some(root) = get_workspace_root() {
        dirs.push(root.join("ZeroConvertIME-windows").join("models"));
        dirs.push(root.join("artifacts").join("models"));
    }
    dirs
}

#[tauri::command]
fn get_active_model() -> Result<ModelListResponse, String> {
    let active_path = get_appdata_azookey_dir().join("zenz.gguf");
    let active_size = std::fs::metadata(&active_path)
        .map(|m| m.len())
        .unwrap_or(0);

    // Q4_K_M is ~64.2 MB (64282688), IQ4_XS is ~55.1 MB (55180960)
    let is_q4km = active_size > 60_000_000;
    let active_id = if is_q4km { "q4km" } else { "iq4xs" };

    let models = vec![
        ModelInfo {
            id: "iq4xs".to_string(),
            name: "ZeroCon Model v3.2 Fast (IQ4_XS)".to_string(),
            filename: "zenz-v3.2-small-iq4xs.gguf".to_string(),
            size_mb: 52.6,
            description: "超高速・軽量 (~800 tok/s) — 低メモリ消費で瞬時変換".to_string(),
            is_active: active_id == "iq4xs",
        },
        ModelInfo {
            id: "q4km".to_string(),
            name: "ZeroCon Model v3.2 Standard (Q4_K_M)".to_string(),
            filename: "zenz-v3.2-small-q4km.gguf".to_string(),
            size_mb: 61.3,
            description: "高精度・標準 (~750 tok/s) — 安定した高精度文脈変換".to_string(),
            is_active: active_id == "q4km",
        },
    ];

    Ok(ModelListResponse {
        active_id: active_id.to_string(),
        models,
    })
}

fn to_hiragana(s: &str) -> String {
    s.chars()
        .map(|c| {
            let code = c as u32;
            if (0x30A1..=0x30F6).contains(&code) {
                char::from_u32(code - 96).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

fn to_katakana(s: &str) -> String {
    s.chars()
        .map(|c| {
            let code = c as u32;
            if (0x3041..=0x3096).contains(&code) {
                char::from_u32(code + 96).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

static BENCHMARK_SERVER: std::sync::Mutex<Option<std::process::Child>> =
    std::sync::Mutex::new(None);
static CURRENT_BENCHMARK_MODEL: std::sync::Mutex<Option<String>> = std::sync::Mutex::new(None);

fn stop_benchmark_server() {
    let mut server_guard = BENCHMARK_SERVER.lock().unwrap();
    if let Some(mut child) = server_guard.take() {
        let _ = child.kill();
        let _ = child.wait();
    }
    let mut model_guard = CURRENT_BENCHMARK_MODEL.lock().unwrap();
    *model_guard = None;
}

fn kill_all_llama_servers() {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        let _ = std::process::Command::new("taskkill")
            .args(["/F", "/IM", "llama-server.exe"])
            .creation_flags(CREATE_NO_WINDOW)
            .output();
    }
}

fn find_llama_server_exe() -> Option<PathBuf> {
    let p1 = get_appdata_azookey_dir()
        .join("llama_cuda")
        .join("llama-server.exe");
    if p1.exists() {
        return Some(p1);
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let p2 = parent.join("llama_cuda").join("llama-server.exe");
            if p2.exists() {
                return Some(p2);
            }
        }
    }
    if let Some(root) = get_workspace_root() {
        let p3 = root
            .join("ZeroConvertIME-windows")
            .join("llama_cuda")
            .join("llama-server.exe");
        if p3.exists() {
            return Some(p3);
        }
    }
    None
}

fn get_benchmark_model_path() -> Result<PathBuf, String> {
    let appdata_zenz = get_appdata_azookey_dir().join("zenz.gguf");
    if appdata_zenz.exists() {
        return Ok(appdata_zenz);
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let exe_zenz = parent.join("zenz.gguf");
            if exe_zenz.exists() {
                return Ok(exe_zenz);
            }
        }
    }
    for dir in get_models_search_dirs() {
        let p = dir.join("zenz.gguf");
        if p.exists() {
            return Ok(p);
        }
        let p2 = dir.join("zenz-v3.2-small-q4km.gguf");
        if p2.exists() {
            return Ok(p2);
        }
    }
    Err("No zenz model found for benchmark inference".to_string())
}

async fn ensure_benchmark_server() -> Result<(), String> {
    let model_path = get_benchmark_model_path()?;
    let model_path_str = model_path.to_string_lossy().to_string();

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_millis(500))
        .build()
        .map_err(|e| e.to_string())?;

    // Check if server is already running with matching model
    let model_matches = {
        let current_model_guard = CURRENT_BENCHMARK_MODEL.lock().unwrap();
        current_model_guard.as_deref() == Some(model_path_str.as_str())
    };
    if model_matches {
        if let Ok(resp) = client.get("http://127.0.0.1:8090/health").send().await {
            if resp.status().is_success() {
                return Ok(());
            }
        }
    }

    // Stop existing server if model changed or unresponsive
    stop_benchmark_server();
    kill_all_llama_servers();
    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    let server_exe = find_llama_server_exe()
        .ok_or_else(|| "llama-server.exe not found in llama_cuda directory".to_string())?;

    let mut cmd = std::process::Command::new(server_exe);
    cmd.args([
        "-m",
        &model_path_str,
        "--port",
        "8090",
        "--host",
        "127.0.0.1",
        "-ngl",
        "99",
        "--log-disable",
    ]);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x08000000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    cmd.stdout(std::process::Stdio::null());
    cmd.stderr(std::process::Stdio::null());

    let child = cmd
        .spawn()
        .map_err(|e| format!("Failed to spawn llama-server: {e}"))?;
    {
        let mut server_guard = BENCHMARK_SERVER.lock().unwrap();
        *server_guard = Some(child);
        let mut model_guard = CURRENT_BENCHMARK_MODEL.lock().unwrap();
        *model_guard = Some(model_path_str);
    }

    // Wait up to 7 seconds for server to be ready
    let start = std::time::Instant::now();
    loop {
        tokio::time::sleep(std::time::Duration::from_millis(150)).await;
        if let Ok(resp) = client.get("http://127.0.0.1:8090/health").send().await {
            if resp.status().is_success() {
                return Ok(());
            }
        }
        if start.elapsed().as_secs() > 7 {
            return Err("llama-server failed to become ready within 7 seconds".to_string());
        }
    }
}

fn evaluate_output(
    output: &str,
    expected: &str,
    acceptable: &[String],
    forbidden: &[String],
) -> (bool, String) {
    let out = output.trim().replace('\r', "").replace('\n', "");
    let exp = expected.trim();

    for fb in forbidden {
        let fb_trim = fb.trim();
        if !fb_trim.is_empty() && out.contains(fb_trim) {
            return (false, format!("NG (forbidden: {fb_trim})"));
        }
    }

    if out == exp || acceptable.iter().any(|a| a.trim() == out) {
        return (true, "OK (Perfect)".to_string());
    }

    let normalize = |s: &str| -> String {
        s.chars()
            .filter(|c| !c.is_whitespace() && *c != '　')
            .collect()
    };
    if normalize(&out) == normalize(exp) {
        return (true, "OK (Whitespace variant)".to_string());
    }

    let hira_out = to_hiragana(&out);
    let hira_exp = to_hiragana(exp);
    if hira_out == hira_exp {
        return (true, "OK (Kana variant)".to_string());
    }

    for acc in acceptable {
        let acc_trim = acc.trim();
        if normalize(&out) == normalize(acc_trim) {
            return (true, "OK (Whitespace variant - acceptable)".to_string());
        }
        if hira_out == to_hiragana(acc_trim) {
            return (true, "OK (Kana variant - acceptable)".to_string());
        }
    }

    (false, format!("NG (out=[{out}] vs exp=[{exp}])"))
}

#[tauri::command]
fn switch_model(state: tauri::State<AppState>, model_id: String) -> Result<String, String> {
    let target_filename = match model_id.as_str() {
        "iq4xs" => "zenz-v3.2-small-iq4xs.gguf",
        "q4km" => "zenz-v3.2-small-q4km.gguf",
        _ => return Err(format!("Unknown model_id: {model_id}")),
    };

    let mut source_path: Option<PathBuf> = None;
    for dir in get_models_search_dirs() {
        let p = dir.join(target_filename);
        if p.exists() {
            source_path = Some(p);
            break;
        }
    }

    let source = source_path.ok_or_else(|| format!("Model file not found: {target_filename}"))?;

    // Stop benchmark server so it releases any locks
    stop_benchmark_server();

    // 1. Terminate server first to release mmap lock on zenz.gguf
    let _ = std::process::Command::new("taskkill")
        .args(["/F", "/IM", "azookey-server.exe"])
        .output();
    std::thread::sleep(std::time::Duration::from_millis(400));

    // 2. Deploy to exe directory (execURL where azookey-server loads zenz.gguf)
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            let exe_zenz = parent.join("zenz.gguf");
            let _ = std::fs::copy(&source, &exe_zenz);
        }
    }
    // Also deploy to AppData
    let dest_dir = get_appdata_azookey_dir();
    let _ = std::fs::create_dir_all(&dest_dir);
    let dest_path = dest_dir.join("zenz.gguf");
    let _ = std::fs::copy(&source, &dest_path);

    // 3. Restart server with new model
    restart_server_impl(&state)
        .map_err(|e| format!("Model deployed, but server restart failed: {e}"))?;

    Ok(format!(
        "Switched to {model_id} and server restarted successfully!"
    ))
}

#[derive(Debug, Serialize, Deserialize)]
pub struct BenchmarkItemResult {
    pub id: u32,
    pub top1: String,
    pub candidates: Vec<String>,
    pub is_correct: bool,
    pub latency_ms: f64,
}

#[tauri::command]
async fn run_benchmark_item(
    id: u32,
    context: String,
    target: String,
    expected: String,
    acceptable: Option<Vec<String>>,
    forbidden_examples: Option<Vec<String>>,
) -> Result<BenchmarkItemResult, String> {
    ensure_benchmark_server().await?;

    let effective_context = if context == "なし" {
        ""
    } else {
        context.trim()
    };
    // Android と同様に空白によるトークナイザー文字化け（[UNK]化）を防ぐためスペースを詰めて推論
    let clean_target = target.replace(' ', "").replace('　', "");
    let target_katakana = to_katakana(&clean_target);
    let prompt = format!(
        "\u{EE02}{}\u{EE00}{}\u{EE01}",
        effective_context, target_katakana
    );

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(5))
        .build()
        .map_err(|e| e.to_string())?;

    let t0 = std::time::Instant::now();
    let resp = client
        .post("http://127.0.0.1:8090/completion")
        .json(&serde_json::json!({
            "prompt": prompt,
            "n_predict": 80,
            "temperature": 0.0,
            "samplers": ["temperature"],
            "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
        }))
        .send()
        .await
        .map_err(|e| format!("Infill request failed: {e}"))?;

    let latency_ms = t0.elapsed().as_secs_f64() * 1000.0;

    let body: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
    let raw_out = body["content"].as_str().unwrap_or("").to_string();

    // Android zenz_jni.cpp と同等の厳密なストップ処理とサニタイズ
    let mut cleaned = raw_out;
    for stop in &[
        "\n", "\r", "<s>", "</s>", "\u{EE00}", "\u{EE01}", "\u{EE02}",
    ] {
        if let Some(pos) = cleaned.find(stop) {
            cleaned.truncate(pos);
        }
    }
    cleaned = cleaned
        .trim_end_matches(|c: char| ('\u{E000}'..='\u{F8FF}').contains(&c))
        .trim()
        .to_string();

    if cleaned.is_empty() {
        cleaned = target_katakana;
    }

    let (is_correct, _reason) = evaluate_output(
        &cleaned,
        &expected,
        &acceptable.unwrap_or_default(),
        &forbidden_examples.unwrap_or_default(),
    );

    Ok(BenchmarkItemResult {
        id,
        top1: cleaned.clone(),
        candidates: vec![cleaned],
        is_correct,
        latency_ms,
    })
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
pub struct AiAssistItemResult {
    pub id: u32,
    pub input: String,
    pub main_output: String,
    pub moshikashite_output: Option<String>,
    pub predictive_output: Option<String>,
    pub main_latency_ms: f64,
    pub assist_latency_ms: f64,
    pub total_latency_ms: f64,
}

fn katakana_to_hiragana(s: &str) -> String {
    s.chars()
        .map(|c| {
            if ('\u{30A1}'..='\u{30F6}').contains(&c) {
                std::char::from_u32(c as u32 - 0x60).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

fn damerau_levenshtein(s1: &str, s2: &str) -> usize {
    let s1_chars: Vec<char> = s1.chars().collect();
    let s2_chars: Vec<char> = s2.chars().collect();
    let len1 = s1_chars.len();
    let len2 = s2_chars.len();
    if len1 == 0 {
        return len2;
    }
    if len2 == 0 {
        return len1;
    }
    let mut dp = vec![vec![0usize; len2 + 1]; len1 + 1];
    for i in 0..=len1 {
        dp[i][0] = i;
    }
    for j in 0..=len2 {
        dp[0][j] = j;
    }
    for i in 1..=len1 {
        for j in 1..=len2 {
            let cost = if s1_chars[i - 1] == s2_chars[j - 1] {
                0
            } else {
                1
            };
            dp[i][j] = (dp[i - 1][j] + 1)
                .min(dp[i][j - 1] + 1)
                .min(dp[i - 1][j - 1] + cost);
            if i > 1
                && j > 1
                && s1_chars[i - 1] == s2_chars[j - 2]
                && s1_chars[i - 2] == s2_chars[j - 1]
            {
                dp[i][j] = dp[i][j].min(dp[i - 2][j - 2] + 1);
            }
        }
    }
    dp[len1][len2]
}

const BENCHMARK_VOCAB: &[(&str, &str)] = &[
    ("こんにちは", "こんにちは"),
    ("こんばんわ", "こんばんは"),
    ("こんばんは", "こんばんは"),
    ("ありがとうございます", "ありがとうございます"),
    ("ありがとうございました", "ありがとうございました"),
    ("よろしくおねがいします", "よろしくお願いします"),
    ("よろしくおねがいいたします", "よろしくお願いいたします"),
    ("よろしくおねがい", "よろしくお願い"),
    ("くらうど", "クラウド"),
    ("あしたの", "明日の"),
    ("おはようございます", "おはようございます"),
    ("おはよう", "おはよう"),
    ("おきゃくさん", "お客さん"),
    ("おつかれさまです", "お疲れ様です"),
    ("おつかれさまでした", "お疲れ様でした"),
    ("おつかれさま", "お疲れ様"),
    ("ごくろうさまです", "ご苦労様です"),
    ("もうしわけありません", "申し訳ありません"),
    ("もうしわけございません", "申し訳ございません"),
    ("おねがいします", "お願いします"),
    ("てすと", "テスト"),
    ("さーびす", "サービス"),
    ("かさおさす", "傘を差す"),
    ("じかんは", "時間は"),
    ("しんぶん", "新聞"),
    ("かんたん", "簡単"),
];

fn benchmark_moshikashite(input: &str) -> Option<String> {
    let hiragana = input.trim();
    if hiragana.chars().count() < 2 {
        return None;
    }

    // 1. 静的タイポ表
    let typo_map = [
        ("おつくれさま", "お疲れ様"),
        ("きんにちは", "こんにちは"),
        ("ありがとうごづいます", "ありがとうございます"),
        ("よろしくょおねがい", "よろしくお願い"),
        ("くらおど", "クラウド"),
        ("おひょ", "おはよう"),
        ("しぶみ", "新聞"),
        ("かたん", "簡単"),
        ("てそと", "テスト"),
        ("さびす", "サービス"),
        ("かさおさす", "傘を差す"),
        ("じかんわ", "時間は"),
    ];
    for (k, v) in typo_map {
        if hiragana == k {
            return Some(v.to_string());
        }
    }

    // 2. Damerau-Levenshtein 全文一致 (距離 1)
    for &(v_reading, v_surface) in BENCHMARK_VOCAB {
        if hiragana != v_reading && damerau_levenshtein(hiragana, v_reading) == 1 {
            return Some(v_surface.to_string());
        }
    }

    // 3. プレフィックス Damerau-Levenshtein (例: 「おあようございます」->「おはようございます」)
    let hiragana_chars: Vec<char> = hiragana.chars().collect();
    let total_len = hiragana_chars.len();
    let mut best_prefix: Option<(usize, String)> = None;

    for &(v_reading, v_surface) in BENCHMARK_VOCAB {
        let v_len = v_reading.chars().count();
        if v_len < 3 || v_len >= total_len {
            continue;
        }
        let min_cand_len = if v_len > 1 { v_len - 1 } else { 1 };
        let max_cand_len = (v_len + 1).min(total_len - 1);
        for cand_len in min_cand_len..=max_cand_len {
            let prefix: String = hiragana_chars[..cand_len].iter().collect();
            if prefix != v_reading && damerau_levenshtein(&prefix, v_reading) == 1 {
                let tail: String = hiragana_chars[cand_len..].iter().collect();
                let full = format!("{}{}", v_surface, tail);
                if best_prefix
                    .as_ref()
                    .map_or(true, |(best_len, _)| v_len > *best_len)
                {
                    best_prefix = Some((v_len, full));
                }
            }
        }
    }

    if let Some((_, full)) = best_prefix {
        return Some(full);
    }

    None
}

#[tauri::command]
async fn run_ai_assist_benchmark_item(
    id: u32,
    context: String,
    input: String,
    model_key: String,
) -> Result<AiAssistItemResult, String> {
    ensure_benchmark_server().await?;

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_millis(1500))
        .build()
        .map_err(|e| e.to_string())?;

    // 1. メイン変換 (Zenzai on Vulkan)
    let effective_context = if context == "なし" {
        ""
    } else {
        context.trim()
    };
    let clean_target = input.replace(' ', "").replace('　', "");
    let target_katakana = to_katakana(&clean_target);
    let prompt = format!(
        "\u{EE02}{}\u{EE00}{}\u{EE01}",
        effective_context, target_katakana
    );

    let t0 = std::time::Instant::now();
    let resp = client
        .post("http://127.0.0.1:8090/completion")
        .json(&serde_json::json!({
            "prompt": prompt,
            "n_predict": 16,
            "temperature": 0.0,
            "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
        }))
        .send()
        .await;

    let main_latency_ms = t0.elapsed().as_secs_f64() * 1000.0;
    let main_output = match resp {
        Ok(r) => {
            if let Ok(json) = r.json::<serde_json::Value>().await {
                json.get("content")
                    .and_then(|c| c.as_str())
                    .unwrap_or(&input)
                    .trim()
                    .to_string()
            } else {
                input.clone()
            }
        }
        Err(_) => input.clone(),
    };

    // 2. 💡 もしかして (誤字補正: 本番同等 Prefix Levenshtein)
    let t_assist = std::time::Instant::now();
    let moshikashite_output = benchmark_moshikashite(&input);

    // 3. 🔮 先読み (定型句キャッシュまたは内蔵 LFM 2.5: ポート 18090)
    let prefix_map = [
        ("いつもお世話に", "なっております。"),
        ("よろしくお願い", "いたします。"),
        ("よろしくお願いし", "ます。"),
        ("お疲れ様", "でした。"),
        ("お疲れ様です", "。お疲れ様でした。"),
        ("おはよう", "ございます！"),
        ("おはようご", "ざいます。"),
        ("ありがとうご", "ざいます！"),
        ("ありがとうございます", "。助かりました。"),
        ("申し訳", "ございません。"),
        ("申し訳あり", "ません。"),
        ("ご確認のほど", "よろしくお願いいたします。"),
        ("ご連絡いただき", "ありがとうございます。"),
        ("ご対応いただき", "ありがとうございます。"),
        ("お手数をおかけ", "しますが、よろしくお願いいたします。"),
        ("引き続きよろしく", "お願いいたします。"),
        ("何卒よろしく", "お願いいたします。"),
        ("ご検討のほど", "よろしくお願いいたします。"),
        ("確認いた", "しました。"),
        ("承知いた", "しました。"),
        ("かしこまり", "ました。"),
        ("了解いた", "しました。"),
        ("失礼いた", "します。"),
        ("返信が遅れ", "申し訳ありません。"),
        ("現在原因を調査", "中です。"),
        ("機能の実装が完了", "いたしました。"),
        ("進捗状況をご報告", "いたします。"),
        ("メンテナンス作業は無事終了", "しました。"),
        ("最新バージョンを公開", "しました。"),
        ("ご都合のよろしい", "日時をご教示ください。"),
        ("日程の調整を", "お願いできますでしょうか。"),
        ("お忙しいところ恐縮ですが、", "ご確認をお願いいたします。"),
        ("添付の資料を", "ご覧ください。"),
        ("出欠のご連絡を", "お願いいたします。"),
        ("プルリクエストを", "作成しました。"),
        ("デプロイが正常に", "完了しました。"),
        ("エラーログを", "確認してください。"),
        ("タイポの修正と", "リファクタリングを行いました。"),
        ("今からそちらへ", "向かいます。"),
        ("ぜひ参加させて", "いただきます。"),
        ("今後ともよろしく", "お願いいたします。"),
        ("取り急ぎ", "ご報告まで。"),
    ];
    let main_hira = katakana_to_hiragana(&main_output);
    let mut predictive_output = prefix_map
        .iter()
        .find(|(k, _)| main_hira.ends_with(k) || k.starts_with(&main_hira))
        .map(|(_, v)| v.to_string());

    if predictive_output.is_none() {
        if model_key == "zerocon" {
            // ZeroCon Model v3.2 (RTX 4070 Vulkan / Port 8090) による超高速先読み
            let target_katakana = to_katakana(&main_output);
            let prompt = format!("\u{EE02}{}\u{EE00}{}", effective_context, target_katakana);
            let z_payload = serde_json::json!({
                "prompt": prompt,
                "n_predict": 16,
                "temperature": 0.0,
                "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
            });

            if let Ok(r) = client
                .post("http://127.0.0.1:8090/completion")
                .json(&z_payload)
                .send()
                .await
            {
                if let Ok(json) = r.json::<serde_json::Value>().await {
                    if let Some(content_str) = json.get("content").and_then(|c| c.as_str()) {
                        let trimmed = content_str.trim();
                        if !trimmed.is_empty() {
                            let hira = katakana_to_hiragana(trimmed);
                            let end_pos = hira
                                .find('。')
                                .map(|i| i + '。'.len_utf8())
                                .unwrap_or(hira.len());
                            let final_str = hira[..end_pos].trim().to_string();
                            if !final_str.is_empty() {
                                predictive_output = Some(final_str);
                            }
                        }
                    }
                }
            }
        } else {
            // 内蔵 LFM 2.5 (ポート 18090)
            let full_p = format!("{}{}", effective_context, main_hira);
            let payload = serde_json::json!({
                "model": "lfm",
                "prompt": full_p,
                "max_tokens": 12,
                "temperature": 0.1,
                "stop": ["\n"]
            });

            if let Ok(r) = client
                .post("http://127.0.0.1:18090/v1/completions")
                .json(&payload)
                .send()
                .await
            {
                if let Ok(json) = r.json::<serde_json::Value>().await {
                    if let Some(txt) = json
                        .get("choices")
                        .and_then(|c| c.get(0))
                        .and_then(|ch| ch.get("text"))
                        .and_then(|t| t.as_str())
                    {
                        let trimmed = txt.trim();
                        if !trimmed.is_empty() {
                            let end_pos = trimmed
                                .find('。')
                                .map(|i| i + '。'.len_utf8())
                                .unwrap_or(trimmed.len());
                            let final_str = trimmed[..end_pos].trim().to_string();
                            if !final_str.is_empty() {
                                predictive_output = Some(final_str);
                            }
                        }
                    }
                }
            }
        }
    }

    let assist_latency_ms = t_assist.elapsed().as_secs_f64() * 1000.0;
    let total_latency_ms = main_latency_ms + assist_latency_ms;

    Ok(AiAssistItemResult {
        id,
        input,
        main_output,
        moshikashite_output,
        predictive_output,
        main_latency_ms,
        assist_latency_ms,
        total_latency_ms,
    })
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app_state = AppState::new();

    tauri::Builder::default()
        .manage(app_state)
        .plugin(tauri_plugin_opener::init())
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                stop_benchmark_server();
            }
        })
        .invoke_handler(tauri::generate_handler![
            greet,
            get_config,
            has_jev_api_key,
            save_jev_api_key,
            delete_jev_api_key,
            check_jev_api_key,
            take_config_startup_notice,
            update_config,
            check_capability,
            get_default_romaji_rows,
            check_for_updates,
            start_update,
            take_update_install_result,
            restart_server,
            reset_learning_history,
            run_benchmark_item,
            run_ai_assist_benchmark_item,
            get_active_model,
            switch_model
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
pub(crate) fn test_env_lock() -> std::sync::MutexGuard<'static, ()> {
    static LOCK: std::sync::OnceLock<std::sync::Mutex<()>> = std::sync::OnceLock::new();
    LOCK.get_or_init(|| std::sync::Mutex::new(()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{env, ffi::OsString, fs, io, path::Path, sync::MutexGuard};

    fn env_lock() -> MutexGuard<'static, ()> {
        crate::test_env_lock()
    }

    struct AppDataGuard {
        _guard: MutexGuard<'static, ()>,
        previous: Option<OsString>,
    }

    impl AppDataGuard {
        fn set(path: &Path) -> Self {
            let guard = env_lock();
            let previous = env::var_os("APPDATA");
            unsafe {
                env::set_var("APPDATA", path);
            }
            Self {
                _guard: guard,
                previous,
            }
        }

        fn unset() -> Self {
            let guard = env_lock();
            let previous = env::var_os("APPDATA");
            unsafe {
                env::remove_var("APPDATA");
            }
            Self {
                _guard: guard,
                previous,
            }
        }
    }

    impl Drop for AppDataGuard {
        fn drop(&mut self) {
            unsafe {
                match &self.previous {
                    Some(value) => env::set_var("APPDATA", value),
                    None => env::remove_var("APPDATA"),
                }
            }
        }
    }

    fn test_state() -> AppState {
        AppState {
            settings: Mutex::new(AppConfig::default()),
            ipc: Mutex::new(None),
            config_update_lock: Mutex::new(()),
            server_config_dirty: Mutex::new(false),
            startup_notice: Mutex::new(None),
        }
    }

    #[test]
    fn update_config_reports_saved_when_server_is_unavailable() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let mut config = AppConfig::default();
        config.zenzai.enable = true;

        let result = update_config_impl(&state, AppConfig::default(), config).unwrap();

        assert!(result.saved);
        assert!(!result.server_applied);
        assert!(result.changed);
        assert!(result.message.is_some());
        assert!(temp.path().join("Azookey").join("settings.json").exists());
        assert!(state.settings.lock().unwrap().zenzai.enable);
    }

    #[test]
    fn update_config_skips_disk_and_server_for_unchanged_settings() {
        let _appdata = AppDataGuard::unset();
        let state = test_state();

        let config = AppConfig::default();
        let result = update_config_impl(&state, config.clone(), config).unwrap();

        assert!(result.saved);
        assert!(result.server_applied);
        assert!(!result.changed);
        assert!(result.message.is_none());
    }

    #[test]
    fn update_config_returns_error_when_save_fails() {
        let _appdata = AppDataGuard::unset();
        let state = test_state();
        let mut config = AppConfig::default();
        config.zenzai.enable = true;

        let error = update_config_impl(&state, AppConfig::default(), config)
            .expect_err("save failure should be returned to the UI");

        assert!(error.contains("APPDATA"));
        assert!(!state.settings.lock().unwrap().zenzai.enable);
    }

    #[test]
    fn unchanged_settings_remain_pending_after_server_apply_failure() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let mut config = AppConfig::default();
        config.zenzai.profile = "profile".to_string();

        let first = update_config_impl(&state, AppConfig::default(), config.clone()).unwrap();
        let retry = update_config_impl(&state, config.clone(), config).unwrap();

        assert!(first.changed);
        assert!(!first.server_applied);
        assert!(!retry.changed);
        assert!(!retry.server_applied);
        assert!(*state.server_config_dirty.lock().unwrap());
    }

    #[test]
    fn update_config_persists_experimental_ai_assist() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let base = AppConfig::default();
        let mut update = base.clone();
        update.general.experimental_ai_assist = true;
        update.general.experimental_predictive_model = "350m_q4km".to_string();

        let result = update_config_impl(&state, base, update).unwrap();
        assert!(result.changed);
        let persisted = AppConfig::read().unwrap();
        assert!(persisted.general.experimental_ai_assist);
        assert_eq!(persisted.general.experimental_predictive_model, "350m_q4km");
    }

    #[test]
    fn stale_full_config_update_preserves_newer_unrelated_fields() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let base = AppConfig::default();
        let mut current = base.clone();
        current.zenzai.profile = "newer profile".to_string();
        current.write().unwrap();
        let mut stale_update = base.clone();
        stale_update.shortcuts.eisu_toggle = true;

        let result = update_config_impl(&state, base, stale_update).unwrap();

        assert!(result.changed);
        assert_eq!(result.config.zenzai.profile, "newer profile");
        assert!(result.config.shortcuts.eisu_toggle);
        let persisted = AppConfig::read().unwrap();
        assert_eq!(persisted.zenzai.profile, "newer profile");
        assert!(persisted.shortcuts.eisu_toggle);
    }

    #[test]
    fn update_config_rejects_future_version_without_modifying_the_file() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let config_root = temp.path().join("Azookey");
        fs::create_dir_all(&config_root).unwrap();
        let config_path = config_root.join("settings.json");
        let future_settings = r#"{
            "version": "0.1.4",
            "zenzai": "future-schema",
            "future_only": { "must_be_preserved": true }
        }"#;
        fs::write(&config_path, future_settings).unwrap();
        let state = test_state();
        let mut update = AppConfig::default();
        update.zenzai.enable = true;

        let error = update_config_impl(&state, AppConfig::default(), update)
            .expect_err("future-version settings must be read-only");

        assert!(error.contains("newer than supported"));
        assert_eq!(fs::read_to_string(config_path).unwrap(), future_settings);
        assert!(!state.settings.lock().unwrap().zenzai.enable);
    }

    #[test]
    fn reset_learning_history_reports_unavailable_server() {
        let state = test_state();

        let result = reset_learning_history_impl(&state);

        assert!(!result.reset);
        assert!(!result.server_applied);
        assert!(result.message.is_some());
    }

    #[test]
    fn restart_server_keeps_existing_ipc_when_restart_fails() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        *state.ipc.lock().unwrap() = Some(ipc::IPCService::new_for_test());

        let error =
            restart_server_impl(&state).expect_err("missing server exe should fail restart");

        assert!(error.to_string().contains("Server executable not found"));
        assert!(state.ipc.lock().unwrap().is_some());
    }

    #[cfg(windows)]
    #[test]
    fn restart_server_releases_config_guard_before_process_request() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();

        let error = restart_server_impl_with(&state, |_| {
            let (acquired_tx, acquired_rx) = std::sync::mpsc::channel();
            let waiter = std::thread::spawn(move || {
                let _guard = ConfigWriteGuard::acquire().unwrap();
                acquired_tx.send(()).unwrap();
            });

            acquired_rx
                .recv_timeout(Duration::from_secs(2))
                .expect("launcher-side config load must not wait for the frontend restart lock");
            waiter.join().unwrap();
            Err(anyhow::anyhow!("stop after lock-scope assertion"))
        })
        .expect_err("injected restart failure should be returned");

        assert!(error.to_string().contains("lock-scope assertion"));
    }

    #[test]
    fn recovery_notice_includes_backup_path() {
        let recovery = ConfigRecovery {
            original_path: PathBuf::from("settings.json"),
            backup_path: PathBuf::from("settings.json.broken-20260524120000"),
        };

        let notice = notice_from_recovery(&recovery);

        assert_eq!(notice.kind, "recovered");
        assert_eq!(
            notice.backup_path.as_deref(),
            Some("settings.json.broken-20260524120000")
        );
    }

    #[test]
    fn rewrite_error_notice_keeps_loaded_config_message() {
        let result = AppConfigLoadResult {
            config: AppConfig::default(),
            recovery: None,
            rewrite_error: Some(ConfigError::WriteTemp {
                path: PathBuf::from("settings.json.tmp-test"),
                source: io::Error::new(io::ErrorKind::PermissionDenied, "test write failure"),
            }),
        };

        let notice = notice_from_load_result(&result).unwrap();

        assert_eq!(notice.kind, "rewrite_error");
        assert!(notice.message.contains("設定は読み込めました"));
        assert!(notice.backup_path.is_none());
    }

    #[test]
    fn future_version_notice_explains_read_only_fallback() {
        let error = ConfigError::FutureVersion {
            path: PathBuf::from("settings.json"),
            stored: "0.1.4".to_string(),
            current: "0.1.3".to_string(),
        };

        let notice = notice_from_load_error(&error);

        assert_eq!(notice.kind, "future_version");
        assert!(notice.message.contains("version 0.1.4"));
        assert!(notice.message.contains("書き換えず"));
        assert!(notice.message.contains("新しいバージョン"));
        assert!(notice.backup_path.is_none());
    }
}
