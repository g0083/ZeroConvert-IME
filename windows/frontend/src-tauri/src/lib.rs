mod benchmark_worker;
mod conversion_model;
mod dictionary;
mod english_dictionary;
mod ipc;
mod jev_api;
mod model_catalog;
mod quick_benchmark;
mod server_process;
mod translation_model;
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
    runtime_config_dirty: Mutex<bool>,
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
            runtime_config_dirty: Mutex::new(false),
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
    /// Stores the server confirmed it cleared, as scope names for the UI.
    applied: Vec<String>,
    /// Stores the server could not clear.
    failed: Vec<String>,
    stats: Option<LearningStatsView>,
}

#[derive(Debug, Serialize, Clone)]
struct LearningStatsView {
    selection_count: u32,
    post_composition_bigram_count: u32,
    selection_override_count: u32,
    typo_count: u32,
    english_count: u32,
    history_present: bool,
    history_paused: bool,
    learning_mode: String,
}

impl From<shared::proto::LearningStatsResponse> for LearningStatsView {
    fn from(stats: shared::proto::LearningStatsResponse) -> Self {
        Self {
            selection_count: stats.selection_count,
            post_composition_bigram_count: stats.post_composition_bigram_count,
            selection_override_count: stats.selection_override_count,
            typo_count: stats.typo_count,
            english_count: stats.english_count,
            history_present: stats.history_present,
            history_paused: stats.history_paused,
            learning_mode: stats.learning_mode,
        }
    }
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
    update_config_impl_with(state, base_config, new_config, |state, config| {
        restart_server_connection(state, config, server_process::restart_server)
    })
}

fn update_config_impl_with(
    state: &AppState,
    base_config: AppConfig,
    new_config: AppConfig,
    restart_runtime: impl FnOnce(&AppState, &AppConfig) -> anyhow::Result<()>,
) -> Result<UpdateConfigResponse, String> {
    let _update_guard = state.config_update_lock.lock().unwrap();
    let _cross_process_guard = ConfigWriteGuard::acquire().map_err(|error| error.to_string())?;
    let current_config = match AppConfig::read() {
        Ok(config) => config,
        Err(error)
            if error.is_version_compatibility_error() || error.is_user_dictionary_error() =>
        {
            eprintln!("Refusing to update incompatible settings: {error}");
            return Err(error.to_string());
        }
        Err(error) => {
            eprintln!("Failed to read latest settings before update: {error}");
            state.settings.lock().unwrap().clone()
        }
    };
    let mut new_config = merge_config_update(current_config.clone(), base_config, new_config)?;
    new_config
        .user_dictionary
        .entries
        .truncate(shared::MAX_USER_DICTIONARY_ENTRIES);
    let changed = current_config != new_config;
    // Appearance is consumed directly by the settings app and floating UI.
    // Saving colors must also work while the conversion server is unavailable.
    let mut server_config = current_config.clone();
    server_config.appearance = new_config.appearance.clone();
    let server_changed = server_config != new_config;
    if current_config.user_dictionary != new_config.user_dictionary {
        AppConfig::write_user_dictionary(&new_config.user_dictionary.entries)
            .map_err(|error| error.to_string())?;
    }
    let mut settings_only_change = new_config.clone();
    settings_only_change.user_dictionary = current_config.user_dictionary.clone();
    if settings_only_change != current_config {
        new_config.write().map_err(|error| error.to_string())?;
    }
    *state.settings.lock().unwrap() = new_config.clone();
    // The replacement launcher child reads/writes settings during startup.
    // Release the cross-process lock before waiting for that child.
    drop(_cross_process_guard);

    if current_config.zenzai.backend != new_config.zenzai.backend
        || current_config.zenzai.effective_gpu_layers() != new_config.zenzai.effective_gpu_layers()
    {
        *state.runtime_config_dirty.lock().unwrap() = true;
    }

    if server_changed {
        *state.server_config_dirty.lock().unwrap() = true;
    }
    if !*state.server_config_dirty.lock().unwrap() {
        return Ok(UpdateConfigResponse {
            saved: true,
            server_applied: true,
            changed,
            config: new_config,
            message: None,
        });
    }

    let applied = if *state.runtime_config_dirty.lock().unwrap() {
        // A running process cannot replace its already-loaded llama DLLs by
        // changing PATH. Start a fresh server with the selected backend instead.
        restart_runtime(state, &new_config)
    } else {
        ipc::IPCService::get_or_connect(&mut state.ipc.lock().unwrap())
            .and_then(|ipc| ipc.update_config())
    };
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
    *state.runtime_config_dirty.lock().unwrap() = false;
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
    scopes: Option<Vec<String>>,
) -> Result<ResetLearningHistoryResponse, String> {
    Ok(reset_learning_history_scoped(&state, scopes.as_deref()))
}

#[cfg(test)]
fn reset_learning_history_impl(state: &AppState) -> ResetLearningHistoryResponse {
    reset_learning_history_scoped(state, None)
}

fn learning_scopes_from_names(names: Option<&[String]>) -> Vec<shared::proto::LearningMemoryScope> {
    let Some(names) = names else {
        return Vec::new();
    };
    let mut scopes = Vec::new();
    for name in names {
        let scope = match name.as_str() {
            "conversion" => shared::proto::LearningMemoryScope::Conversion,
            "typo" => shared::proto::LearningMemoryScope::Typo,
            "english" => shared::proto::LearningMemoryScope::English,
            // An unknown name must not silently widen the reset to every store.
            other => {
                eprintln!("Ignoring unknown learning scope: {other}");
                continue;
            }
        };
        if !scopes.contains(&scope) {
            scopes.push(scope);
        }
    }
    scopes
}

fn scope_name(scope: i32) -> &'static str {
    match shared::proto::LearningMemoryScope::try_from(scope) {
        Ok(shared::proto::LearningMemoryScope::Conversion) => "conversion",
        Ok(shared::proto::LearningMemoryScope::Typo) => "typo",
        Ok(shared::proto::LearningMemoryScope::English) => "english",
        _ => "unknown",
    }
}

fn reset_learning_history_scoped(
    state: &AppState,
    scope_names: Option<&[String]>,
) -> ResetLearningHistoryResponse {
    let scopes = learning_scopes_from_names(scope_names);
    let response = ipc::IPCService::get_or_connect(&mut state.ipc.lock().unwrap())
        .and_then(|ipc| ipc.reset_learning_scopes(&scopes));
    match response {
        Err(error) => {
            eprintln!("Failed to reset learning memory: {}", error);
            ResetLearningHistoryResponse {
                reset: false,
                server_applied: false,
                message: Some(error.to_string()),
                applied: Vec::new(),
                failed: Vec::new(),
                stats: None,
            }
        }
        Ok(response) => {
            let applied: Vec<String> = response
                .applied
                .iter()
                .map(|s| scope_name(*s).to_string())
                .collect();
            let failed: Vec<String> = response
                .failed
                .iter()
                .map(|s| scope_name(*s).to_string())
                .collect();
            ResetLearningHistoryResponse {
                reset: response.reset,
                server_applied: true,
                message: if response.reset {
                    None
                } else {
                    Some("サーバー側で学習履歴の削除に失敗しました。".to_string())
                },
                applied,
                failed,
                stats: response.stats.map(LearningStatsView::from),
            }
        }
    }
}

#[tauri::command]
fn get_learning_stats(state: tauri::State<AppState>) -> Result<LearningStatsView, String> {
    ipc::IPCService::get_or_connect(&mut state.ipc.lock().unwrap())
        .and_then(|ipc| ipc.learning_stats())
        .map(LearningStatsView::from)
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn forget_learning_candidate(
    state: tauri::State<AppState>,
    candidate_id: u64,
    surface: Option<String>,
    typo_reading: Option<String>,
) -> Result<bool, String> {
    ipc::IPCService::get_or_connect(&mut state.ipc.lock().unwrap())
        .and_then(|ipc| {
            ipc.forget_learning_candidate(
                candidate_id,
                surface.as_deref().unwrap_or_default(),
                typo_reading.as_deref().unwrap_or_default(),
            )
        })
        .map_err(|error| error.to_string())
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

    restart_server_connection(state, &config, restart_process)?;
    *state.server_config_dirty.lock().unwrap() = false;
    *state.runtime_config_dirty.lock().unwrap() = false;
    Ok(())
}

fn restart_server_connection(
    state: &AppState,
    config: &AppConfig,
    restart_process: impl FnOnce(&AppConfig) -> Result<(), anyhow::Error>,
) -> Result<(), anyhow::Error> {
    restart_process(config)?;

    *state.ipc.lock().unwrap() = None;
    let ipc = ipc::IPCService::new_with_timeout(Duration::from_secs(10))
        .context("Server restarted, but IPC reconnect failed")?;
    *state.ipc.lock().unwrap() = Some(ipc);

    Ok(())
}

#[derive(Debug, Deserialize, Serialize, Clone)]
struct Capability {
    cpu: bool,
    vulkan: bool,
}

#[tauri::command]
fn check_capability() -> Capability {
    // vulkan:
    // vulkan-1.dllの存在確認

    let mut capability = Capability {
        cpu: shared::zenzai_cpu_backend_supported(),
        vulkan: false,
    };

    // Check for Vulkan availability
    capability.vulkan = vulkan_available();

    capability
}

/// vulkan-1.dll はGraphicsドライバーのディレクトリに置かれるため、
/// PATH とカレントディレクトリから解決できるかで判定する。
fn vulkan_available() -> bool {
    let vulkan_file = "vulkan-1.dll";
    std::env::var("PATH")
        .unwrap_or_default()
        .split(';')
        .map(PathBuf::from)
        .chain(std::iter::once(std::env::current_dir().unwrap_or_default()))
        .any(|path| path.join(vulkan_file).exists())
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
    dirs.push(shared::runtime_assets::model_download_root());
    if let Some(root) = get_workspace_root() {
        dirs.push(root.join("ZeroConvertIME-windows").join("models"));
        dirs.push(root.join("artifacts/models/zerocon"));
        dirs.push(root.join("artifacts").join("models"));
    }
    dirs
}

#[tauri::command]
fn get_active_model(
    state: tauri::State<AppState>,
) -> Result<model_catalog::ModelListResponse, String> {
    let config = state.settings.lock().map_err(|e| e.to_string())?.clone();
    // 現在選択中のモデルファイルがまだ無い（削除済み・未取得）場合でも一覧は返す。
    // `list()` は active が無いとき active_id を空にして処理を続ける。
    let active = get_benchmark_model_path(&config).unwrap_or_else(|_| {
        shared::runtime_assets::model_download_root().join("active-model.gguf")
    });
    let install_dir = install_dir_from_current_exe();
    model_catalog::list(&active, &install_dir, &get_models_search_dirs())
        .map_err(|error| error.to_string())
}

fn install_dir_from_current_exe() -> PathBuf {
    std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(PathBuf::from))
        .unwrap_or_else(|| PathBuf::from("."))
}

#[tauri::command]
fn translation_model_status(
    state: tauri::State<AppState>,
    model_id: Option<String>,
) -> translation_model::Status {
    let config = state
        .settings
        .lock()
        .map_err(|e| e.to_string())
        .ok()
        .map(|guard| guard.clone())
        .unwrap_or_default();
    let model_id = model_id.unwrap_or_else(|| config.general.translation_model.clone());
    let install_dir = install_dir_from_current_exe();
    translation_model::status(&install_dir, &model_id)
}

#[tauri::command]
async fn translation_model_download(
    app: tauri::AppHandle,
    state: tauri::State<'_, AppState>,
    model_id: Option<String>,
) -> Result<translation_model::Status, String> {
    let config = state.settings.lock().map_err(|e| e.to_string())?.clone();
    let model_id = model_id.unwrap_or_else(|| config.general.translation_model.clone());
    let install_dir = install_dir_from_current_exe();
    translation_model::install(&install_dir, app, &model_id)
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn conversion_model_status(model_id: String) -> Result<conversion_model::Status, String> {
    let install_dir = install_dir_from_current_exe();
    conversion_model::status(&install_dir, &model_id)
}

#[tauri::command]
async fn conversion_model_download(
    app: tauri::AppHandle,
    model_id: String,
) -> Result<conversion_model::Status, String> {
    let install_dir = install_dir_from_current_exe();
    conversion_model::install(&install_dir, app, &model_id).await
}

#[tauri::command]
fn conversion_model_cancel() {
    conversion_model::cancel();
}

#[tauri::command]
fn conversion_model_delete(model_id: String) -> Result<conversion_model::Status, String> {
    let install_dir = install_dir_from_current_exe();
    conversion_model::delete(&install_dir, &model_id)
}

#[tauri::command]
fn english_dictionary_status() -> english_dictionary::Status {
    english_dictionary::status()
}

#[tauri::command]
async fn english_dictionary_download(
    app: tauri::AppHandle,
) -> Result<english_dictionary::Status, String> {
    english_dictionary::install(app).await
}

#[tauri::command]
fn dictionary_status() -> dictionary::Status {
    dictionary::status(&install_dir_from_current_exe())
}

#[tauri::command]
async fn dictionary_download(app: tauri::AppHandle) -> Result<dictionary::Status, String> {
    let install_dir = install_dir_from_current_exe();
    dictionary::install(&install_dir, app).await
}

#[tauri::command]
fn dictionary_cancel() {
    dictionary::cancel();
}

/// 初回起動の準備ダイアログが一度に問い合わせる3点セット。
/// 変換モデル（必須）・日本語辞書・英字辞書を同じ判定順で返す。
#[derive(Serialize)]
struct SetupAssets {
    model: conversion_model::Status,
    dictionary: dictionary::Status,
    english: english_dictionary::Status,
}

#[tauri::command]
fn setup_assets_status() -> Result<SetupAssets, String> {
    let install_dir = install_dir_from_current_exe();
    // 必須モデルは shared の既定（Nano v4.2 IQ4_emb8）と同じ解決を使う。
    let mandatory_model = shared::runtime_assets::default_model();
    Ok(SetupAssets {
        model: conversion_model::status(&install_dir, &mandatory_model)?,
        dictionary: dictionary::status(&install_dir),
        english: english_dictionary::status(),
    })
}

fn benchmark_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(parent) = exe.parent() {
            roots.push(parent.to_path_buf());
        }
    }
    if let Some(root) = get_workspace_root() {
        roots.push(root.join("ZeroConvertIME-windows"));
    }
    roots.push(get_appdata_azookey_dir());
    roots
}

fn get_benchmark_model_path(config: &AppConfig) -> Result<PathBuf, String> {
    benchmark_roots()
        .iter()
        .map(|root| shared::runtime_assets::converter_model(root, &config.zenzai.model))
        .find(|p| p.is_file())
        .ok_or_else(|| "ZeroCon model not found".to_string())
}

#[tauri::command]
fn switch_model(state: tauri::State<AppState>, model_id: String) -> Result<String, String> {
    model_catalog::source_for(&model_id, &get_models_search_dirs())?;
    let model_update = shared::model_update::ModelUpdateGuard::begin(Duration::from_secs(5))
        .map_err(|error| error.to_string())?;
    let base = state.settings.lock().map_err(|e| e.to_string())?.clone();
    let mut updated = base.clone();
    updated.zenzai.model = shared::runtime_assets::canonical_model_id(&model_id)
        .ok_or_else(|| format!("Unknown model_id: {model_id}"))?
        .into();
    update_config_impl(&state, base, updated)?;
    drop(model_update);
    restart_server_impl(&state)
        .map_err(|e| format!("Model saved, but server restart failed: {e}"))?;
    Ok(format!("Switched to {model_id}"))
}

fn benchmark_worker_paths(
    backend: &str,
    scope: azookey_server::ai_assist::benchmark::ConversionScope,
) -> Result<(PathBuf, PathBuf, PathBuf), String> {
    use azookey_server::ai_assist::benchmark::ConversionScope;
    benchmark_roots()
        .into_iter()
        .find_map(|root| {
            let runtime = shared::runtime_assets::backend(&root, backend);
            let worker = root.join(match scope {
                ConversionScope::Model => "keyboard-prior.exe",
                ConversionScope::Candidates => "azookey-server.exe",
                ConversionScope::Typo => "azookey-server.exe",
            });
            (worker.is_file() && runtime.join("llama.dll").is_file()).then(|| {
                let dictionary = shared::runtime_assets::dictionary_dir(&root);
                (worker, runtime, dictionary)
            })
        })
        .ok_or_else(|| "ベンチマーク用の実行ファイルが見つかりません。".into())
}

fn persist_cpu_runtime_tuning(state: &AppState, tuned: shared::cpu_tuning::CpuRuntimeTuningConfig) {
    let Ok(_update_guard) = state.config_update_lock.lock() else {
        return;
    };
    let Ok(_cross_process_guard) = ConfigWriteGuard::acquire() else {
        return;
    };
    let mut current = match AppConfig::read() {
        Ok(config) => config,
        Err(_) => state.settings.lock().unwrap().clone(),
    };
    let current_model = crate::quick_benchmark::active_canonical_model_id(&current);
    let tuned_model =
        shared::runtime_assets::canonical_model_id(&tuned.model_id).unwrap_or(&tuned.model_id);
    if current.zenzai.backend != "cpu" || current_model != tuned_model {
        return;
    }
    if current.zenzai.cpu_runtime_tuning.as_ref() == Some(&tuned) {
        return;
    }
    current.zenzai.cpu_runtime_tuning = Some(tuned);
    if current.write().is_err() {
        return;
    }
    *state.settings.lock().unwrap() = current;
    drop(_cross_process_guard);
    let _ = ipc::IPCService::get_or_connect(&mut state.ipc.lock().unwrap())
        .and_then(|ipc| ipc.update_config());
}

#[tauri::command]
async fn start_benchmark(
    state: tauri::State<'_, AppState>,
    scope: Option<azookey_server::ai_assist::benchmark::ConversionScope>,
    backend: Option<String>,
) -> Result<Value, String> {
    use azookey_server::ai_assist::benchmark::{ConversionScope, WindowsTypoOptions};
    let scope = scope.unwrap_or_default();
    let config = state.settings.lock().map_err(|e| e.to_string())?.clone();
    let model_id = crate::quick_benchmark::active_canonical_model_id(&config);
    let (backend, gpu_layers) = benchmark_backend(&config, backend.as_deref())?;
    let (mut session, newly_tuned) = tauri::async_runtime::spawn_blocking(move || {
        let model = get_benchmark_model_path(&config)?;
        let (worker, runtime, dictionary) = benchmark_worker_paths(backend, scope)?;
        let typo_options = (scope == ConversionScope::Typo).then(|| WindowsTypoOptions {
            enabled: config.general.experimental_typo_correction,
            engine: if config.general.experimental_ai_engine == "lm_studio" {
                "lm_studio"
            } else {
                "embedded"
            }
            .into(),
        });
        let (session, newly_tuned) = benchmark_worker::start_with_cpu_tuning(
            worker,
            runtime,
            model,
            dictionary,
            gpu_layers,
            scope,
            typo_options.as_ref(),
            Some((&config, backend, &model_id)),
        )?;
        Ok::<_, String>((session, newly_tuned))
    })
    .await
    .map_err(|e| e.to_string())??;
    if let Some(tuned) = newly_tuned {
        persist_cpu_runtime_tuning(&state, tuned);
    }
    session["backend"] = backend.into();
    session["gpu_layers"] = gpu_layers.into();
    Ok(session)
}

fn benchmark_backend(
    config: &AppConfig,
    override_backend: Option<&str>,
) -> Result<(&'static str, u32), String> {
    match override_backend.unwrap_or(&config.zenzai.backend) {
        "cpu" => Ok(("cpu", 0)),
        "vulkan" => Ok(("vulkan", config.zenzai.gpu_layers.min(99))),
        _ => Err("ベンチマーク用のバックエンドを選択してください。".into()),
    }
}

#[tauri::command]
async fn run_quick_conversion_benchmark(
    state: tauri::State<'_, AppState>,
    backend: Option<String>,
    webview: tauri::Webview,
    progress: Option<tauri::ipc::JavaScriptChannelId>,
) -> Result<quick_benchmark::SingleResult, String> {
    let config = state.settings.lock().map_err(|e| e.to_string())?.clone();
    let (backend, gpu_layers) = benchmark_backend(&config, backend.as_deref())?;
    let progress = progress.map(|id| id.channel_on(webview));
    let mut result = tauri::async_runtime::spawn_blocking(move || {
        quick_benchmark::run_single(&config, backend, gpu_layers, |update| {
            if let Some(channel) = &progress {
                let _ = channel.send(update);
            }
        })
    })
    .await
    .map_err(|e| e.to_string())??;
    if let Some(tuned) = result.cpu_tuning.take() {
        persist_cpu_runtime_tuning(&state, tuned);
    }
    Ok(result)
}

#[tauri::command]
async fn run_benchmark_batch(
    id: u64,
    cases: Vec<azookey_server::ai_assist::benchmark::Case>,
) -> Result<Vec<Value>, String> {
    tauri::async_runtime::spawn_blocking(move || benchmark_worker::run(id, cases))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn finish_benchmark(id: u64) -> Result<f64, String> {
    tauri::async_runtime::spawn_blocking(move || benchmark_worker::finish(id))
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
async fn run_quick_benchmark(
    state: tauri::State<'_, AppState>,
    webview: tauri::Webview,
    progress: Option<tauri::ipc::JavaScriptChannelId>,
) -> Result<quick_benchmark::ResultSummary, String> {
    let config = state.settings.lock().map_err(|e| e.to_string())?.clone();
    let progress = progress.map(|id| id.channel_on(webview));
    tauri::async_runtime::spawn_blocking(move || {
        quick_benchmark::run(&config, |update| {
            if let Some(channel) = &progress {
                let _ = channel.send(update);
            }
        })
    })
    .await
    .map_err(|e| e.to_string())?
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app_state = AppState::new();

    tauri::Builder::default()
        .manage(app_state)
        .plugin(tauri_plugin_opener::init())
        .on_window_event(|_window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                tauri::async_runtime::spawn_blocking(benchmark_worker::close);
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
            get_learning_stats,
            forget_learning_candidate,
            start_benchmark,
            run_benchmark_batch,
            finish_benchmark,
            run_quick_benchmark,
            run_quick_conversion_benchmark,
            translation_model_status,
            translation_model_download,
            conversion_model_status,
            conversion_model_download,
            conversion_model_cancel,
            conversion_model_delete,
            english_dictionary_status,
            english_dictionary_download,
            dictionary_status,
            dictionary_download,
            dictionary_cancel,
            setup_assets_status,
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

    #[test]
    fn benchmark_backend_override_uses_gpu_layers_without_changing_ime_settings() {
        let mut config = AppConfig::default();
        config.zenzai.backend = "cpu".into();
        config.zenzai.gpu_layers = 8;
        assert_eq!(benchmark_backend(&config, None).unwrap(), ("cpu", 0));
        assert_eq!(
            benchmark_backend(&config, Some("vulkan")).unwrap(),
            ("vulkan", 8)
        );
        assert_eq!(config.zenzai.backend, "cpu");
        assert!(benchmark_backend(&config, Some("unknown")).is_err());
    }
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
            runtime_config_dirty: Mutex::new(false),
            startup_notice: Mutex::new(None),
        }
    }

    #[test]
    fn runtime_change_restarts_after_save_and_failed_restart_can_be_retried() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let base = AppConfig::default();
        let mut config = base.clone();
        config.zenzai.backend = "cpu".into();
        let first = update_config_impl_with(&state, base, config.clone(), |_, saved| {
            assert_eq!(AppConfig::read().unwrap().zenzai, saved.zenzai);
            anyhow::bail!("restart failed")
        })
        .unwrap();
        assert!(first.saved && !first.server_applied);
        assert!(*state.runtime_config_dirty.lock().unwrap());
        let mut restarted = false;
        let retry = update_config_impl_with(&state, config.clone(), config, |_, _| {
            restarted = true;
            Ok(())
        })
        .unwrap();
        assert!(restarted);
        assert!(!retry.changed && retry.server_applied);
        assert!(!*state.runtime_config_dirty.lock().unwrap());
        assert!(!*state.server_config_dirty.lock().unwrap());
    }

    #[test]
    fn gpu_layer_change_restarts_but_profile_edit_does_not() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let base = AppConfig::default();
        let mut config = base.clone();
        config.zenzai.gpu_layers = 4;
        let result = update_config_impl_with(&state, base, config.clone(), |_, saved| {
            assert_eq!(saved.zenzai.effective_gpu_layers(), 4);
            Ok(())
        })
        .unwrap();
        assert!(result.server_applied);
        let mut update = config.clone();
        update.zenzai.profile = "profile".into();
        let result = update_config_impl_with(&state, config, update, |_, _| {
            panic!("profile edit must not restart the server")
        })
        .unwrap();
        assert!(result.saved);
        assert!(!*state.runtime_config_dirty.lock().unwrap());
    }

    #[test]
    fn appearance_saves_without_conversion_server() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let base = AppConfig::default();
        let mut config = base.clone();
        config.appearance.frontend_theme = shared::ColorTheme::Sakura;
        config.appearance.ime_theme = shared::ColorTheme::Forest;
        let result = update_config_impl(&state, base, config.clone()).unwrap();
        assert!(result.saved && result.changed && result.server_applied);
        assert_eq!(AppConfig::read().unwrap().appearance, config.appearance);
        assert!(!*state.server_config_dirty.lock().unwrap());
        assert!(state.ipc.lock().unwrap().is_none());

        // An IME theme edit from an older settings snapshot preserves the newer
        // frontend theme saved by a different page/window.
        let base = AppConfig::default();
        let mut stale_update = base.clone();
        stale_update.appearance.ime_theme = shared::ColorTheme::Paper;
        let result = update_config_impl(&state, base, stale_update).unwrap();
        assert_eq!(
            result.config.appearance.frontend_theme,
            shared::ColorTheme::Sakura
        );
        assert_eq!(
            result.config.appearance.ime_theme,
            shared::ColorTheme::Paper
        );
        assert!(state.ipc.lock().unwrap().is_none());
    }

    #[test]
    fn update_config_reports_saved_when_server_is_unavailable() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        let mut config = AppConfig::default();
        config.zenzai.enable = false;

        let result = update_config_impl(&state, AppConfig::default(), config).unwrap();

        assert!(result.saved);
        assert!(!result.server_applied);
        assert!(result.changed);
        assert!(result.message.is_some());
        assert!(temp.path().join("Azookey").join("settings.json").exists());
        assert!(!state.settings.lock().unwrap().zenzai.enable);
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
        config.zenzai.enable = false;

        let error = update_config_impl(&state, AppConfig::default(), config)
            .expect_err("save failure should be returned to the UI");

        assert!(error.contains("APPDATA"));
        assert!(state.settings.lock().unwrap().zenzai.enable);
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
        update.general.experimental_ai_assist = !base.general.experimental_ai_assist;

        let result = update_config_impl(&state, base, update).unwrap();
        assert!(result.changed);
        let persisted = AppConfig::read().unwrap();
        assert!(!persisted.general.experimental_ai_assist);
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
        assert!(state.settings.lock().unwrap().zenzai.enable);
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
    fn learning_scope_names_map_to_known_scopes_only() {
        let names = vec![
            "conversion".to_string(),
            "typo".to_string(),
            "english".to_string(),
        ];
        let scopes = learning_scopes_from_names(Some(&names));
        assert_eq!(
            scopes,
            vec![
                shared::proto::LearningMemoryScope::Conversion,
                shared::proto::LearningMemoryScope::Typo,
                shared::proto::LearningMemoryScope::English,
            ]
        );
        assert_eq!(
            scope_name(shared::proto::LearningMemoryScope::English as i32),
            "english"
        );
    }

    #[test]
    fn unknown_learning_scope_does_not_widen_the_reset() {
        // A typo in the UI must not silently become "delete everything".
        let names = vec!["conversion".to_string(), "typoo".to_string()];
        let scopes = learning_scopes_from_names(Some(&names));
        assert_eq!(scopes, vec![shared::proto::LearningMemoryScope::Conversion]);
    }

    #[test]
    fn duplicate_learning_scopes_are_collapsed() {
        let names = vec!["typo".to_string(), "typo".to_string()];
        let scopes = learning_scopes_from_names(Some(&names));
        assert_eq!(scopes, vec![shared::proto::LearningMemoryScope::Typo]);
    }

    #[test]
    fn missing_scope_list_means_every_store() {
        // Older frontends call the command with no arguments.
        assert!(learning_scopes_from_names(None).is_empty());
    }

    #[test]
    fn restart_server_keeps_existing_ipc_when_restart_fails() {
        let temp = tempfile::tempdir().unwrap();
        let _appdata = AppDataGuard::set(temp.path());
        let state = test_state();
        *state.ipc.lock().unwrap() = Some(ipc::IPCService::new_for_test());

        let error =
            restart_server_impl(&state).expect_err("missing server exe should fail restart");

        assert!(error
            .to_string()
            .contains("Unit tests must not restart a running IME"));
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
