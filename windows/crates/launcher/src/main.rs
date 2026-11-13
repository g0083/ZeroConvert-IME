#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use shared::{launcher_pipe_path, zenzai_cpu_backend_supported, AppConfig};
use std::collections::VecDeque;
use std::ffi::c_void;
use std::fs::{self, File};
use std::io::{BufRead, BufReader, Write};
use std::path::Path;
use std::process::{Child, Command, ExitStatus, Stdio};
use std::ptr::addr_of_mut;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::time::{Duration, Instant};
use std::{env, thread};

use anyhow::Context as _;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::windows::named_pipe::{NamedPipeServer, ServerOptions};
use windows::{
    core::{PCWSTR, PWSTR},
    Win32::{
        Foundation::{CloseHandle, LocalFree, HANDLE, HLOCAL},
        Security::{
            Authorization::{
                ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
                SDDL_REVISION,
            },
            GetTokenInformation, TokenLogonSid, PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES,
            TOKEN_GROUPS, TOKEN_QUERY,
        },
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    },
};

const SERVER_RESTART_DELAY: Duration = Duration::from_secs(1);
const SERVER_RESTART_WINDOW: Duration = Duration::from_secs(60);
const SERVER_RESTART_BURST_LIMIT: usize = 5;
const SERVER_RESTART_COOLDOWN: Duration = Duration::from_secs(30);
const SERVER_WATCH_POLL_INTERVAL: Duration = Duration::from_millis(100);
const UIACCESS_HANDOFF_POLL_INTERVAL: Duration = Duration::from_secs(30);
const LAUNCHER_RESTART_COMMAND: &str = "restart-server";
const LAUNCHER_COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const LAUNCHER_CRASH_TRACE_FILE_NAME: &str = "launcher-crash-trace.json";
const LAUNCHER_PREVIOUS_CRASH_TRACE_FILE_NAME: &str = "launcher-crash-trace.previous.json";

fn main() -> anyhow::Result<()> {
    shared::enable_redirection_guard().map_err(anyhow::Error::msg)?;
    let cpu_backend_supported = zenzai_cpu_backend_supported();
    env::set_var(
        "AZOOKEY_ZENZAI_CPU_SUPPORTED",
        if cpu_backend_supported { "1" } else { "0" },
    );

    let exe_path = env::current_exe()?.parent().unwrap().to_path_buf();
    let (command_tx, command_rx) = mpsc::channel();
    // Reserve the session-specific pipe before starting either child. A duplicate
    // launcher (or an ACL failure) must fail without launching more processes.
    start_launcher_command_listener(command_tx, launcher_pipe_path()?)?;

    let server_exe_path = exe_path.clone();
    let _server_handle = thread::spawn(move || {
        if let Err(error) =
            watch_server_process(&server_exe_path, cpu_backend_supported, command_rx)
        {
            eprintln!("[launcher] server watchdog stopped: {error:?}");
        }
    });

    let prediction_path = exe_path.clone();
    let _prediction_handle = thread::spawn(move || watch_prediction_server(&prediction_path));
    let translation_path = exe_path.clone();
    let _translation_handle =
        thread::spawn(move || watch_assist_server(&translation_path, AssistRole::Translation));

    loop {
        match start_ui_process(&exe_path) {
            Ok(mut ui) => match ui.wait() {
                Ok(ui_status) => {
                    if ui_status.code() == Some(shared::UIACCESS_HANDOFF_EXIT_CODE) {
                        // The UI re-launched itself with a UIAccess enabled token so that the
                        // floating candidate / "原文" windows can be drawn above elevated
                        // applications. That child is the real UI now, so this process must
                        // not start yet another one, and must keep supervising the server.
                        eprintln!(
                            "[launcher] ui.exe handed over to a UIAccess process; not starting another UI"
                        );
                        if !wait_while_ui_runs_separately() {
                            eprintln!(
                                "[launcher] the UIAccess UI process is gone; starting a new UI"
                            );
                        }
                        continue;
                    }
                    eprintln!("[launcher] ui.exe exited: {ui_status}")
                }
                Err(err) => eprintln!("[launcher] Failed to wait for ui.exe: {err:?}"),
            },
            Err(err) => eprintln!("[launcher] Failed to start ui.exe: {err:?}"),
        }
        thread::sleep(SERVER_RESTART_DELAY);
    }
}

/// Keeps the launcher alive while the UI runs as a separate UIAccess process. The
/// launcher still has to supervise the conversion server, so it must not exit here.
///
/// Returns `true` while the UI process is still alive and `false` once it is gone, so
/// that the caller can start a replacement UI.
fn wait_while_ui_runs_separately() -> bool {
    loop {
        thread::sleep(UIACCESS_HANDOFF_POLL_INTERVAL);
        if !ui_process_is_running() {
            return false;
        }
    }
}

fn process_is_running(target_exe: &str) -> bool {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Diagnostics::ToolHelp::{
        CreateToolhelp32Snapshot, Process32FirstW, Process32NextW, PROCESSENTRY32W,
        TH32CS_SNAPPROCESS,
    };

    unsafe {
        let snapshot = match CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) {
            Ok(handle) => handle,
            Err(_) => return true,
        };

        let mut entry = PROCESSENTRY32W {
            dwSize: size_of::<PROCESSENTRY32W>() as u32,
            ..Default::default()
        };
        let mut found = false;
        if Process32FirstW(snapshot, &mut entry).is_ok() {
            loop {
                if exe_file_name(&entry.szExeFile) == target_exe {
                    found = true;
                    break;
                }
                if Process32NextW(snapshot, &mut entry).is_err() {
                    break;
                }
            }
        }
        let _ = CloseHandle(snapshot);
        found
    }
}

/// Reports whether a UI process is currently running.
fn ui_process_is_running() -> bool {
    process_is_running("ui.exe")
}

fn exe_file_name(raw: &[u16]) -> String {
    let length = raw
        .iter()
        .position(|&value| value == 0)
        .unwrap_or(raw.len());
    String::from_utf16_lossy(&raw[..length])
}

fn watch_server_process(
    install_dir: &Path,
    cpu_backend_supported: bool,
    command_rx: Receiver<LauncherCommand>,
) -> anyhow::Result<()> {
    let mut recent_restarts = VecDeque::new();

    loop {
        let mut server = start_server_process(install_dir, cpu_backend_supported)?;
        let status = wait_for_server_exit_or_restart_request(&mut server, &command_rx)?;
        let restart_delay = match status {
            ServerExit::Exited(status) => {
                eprintln!("[launcher] azookey-server.exe exited: {status}");
                restart_delay_after_server_exit(&mut recent_restarts, false, Instant::now())
            }
            ServerExit::RestartRequested(status) => {
                eprintln!("[launcher] azookey-server.exe restarted by request: {status}");
                restart_delay_after_server_exit(&mut recent_restarts, true, Instant::now())
            }
        };

        if let Some(delay) = restart_delay {
            thread::sleep(delay);
        }
    }
}

fn restart_delay_after_server_exit(
    recent_restarts: &mut VecDeque<Instant>,
    restart_requested: bool,
    now: Instant,
) -> Option<Duration> {
    if restart_requested {
        return None;
    }

    recent_restarts.push_back(now);
    while recent_restarts
        .front()
        .is_some_and(|started| now.duration_since(*started) > SERVER_RESTART_WINDOW)
    {
        recent_restarts.pop_front();
    }

    if recent_restarts.len() >= SERVER_RESTART_BURST_LIMIT {
        eprintln!(
            "[launcher] azookey-server.exe restarted too often; cooling down for {} seconds",
            SERVER_RESTART_COOLDOWN.as_secs()
        );
        recent_restarts.clear();
        Some(SERVER_RESTART_COOLDOWN)
    } else {
        Some(SERVER_RESTART_DELAY)
    }
}

fn start_server_process(install_dir: &Path, cpu_backend_supported: bool) -> anyhow::Result<Child> {
    while shared::model_update::requested() {
        shared::model_update::acknowledge_conversion_stopped();
        thread::sleep(Duration::from_millis(100));
    }
    let config = load_config();

    if config.zenzai.enable && config.zenzai.backend == "cpu" && !cpu_backend_supported {
        eprintln!("[launcher] CPU backend requires AVX support. Zenzai will fall back to standard conversion.");
    }

    let mut command = process_command_with_backend(install_dir, "azookey-server.exe", &config)?;
    command.env(
        "AZOOKEY_ZENZAI_CPU_SUPPORTED",
        if cpu_backend_supported { "1" } else { "0" },
    );

    let startup_details = format!(
        "backend={};backend_dir={};zenzai_enable={};cpu_backend_supported={}",
        config.zenzai.backend,
        backend_dir(&config),
        config.zenzai.enable,
        cpu_backend_supported
    );
    write_launcher_crash_trace(
        &config,
        "server_startup",
        "spawning",
        "begin",
        &startup_details,
    );
    match spawn_process(command, "azookey-server.exe", "[server]") {
        Ok(child) => {
            write_launcher_crash_trace(
                &config,
                "server_startup",
                "spawned",
                "begin",
                &format!("child_pid={};{startup_details}", child.id()),
            );
            Ok(child)
        }
        Err(error) => {
            write_launcher_crash_trace(
                &config,
                "server_startup",
                "spawn",
                "error",
                &format!("{startup_details};error={error:?}"),
            );
            Err(error)
        }
    }
}

fn start_ui_process(install_dir: &Path) -> anyhow::Result<Child> {
    let config = load_config();
    let command = process_command_with_backend(install_dir, "ui.exe", &config)?;
    spawn_process(command, "ui.exe", "[ui]")
}

fn process_command_with_backend(
    install_dir: &Path,
    exe: &str,
    config: &AppConfig,
) -> anyhow::Result<Command> {
    let backend_path = shared::runtime_assets::backend(install_dir, &config.zenzai.backend);
    let mut command = process_command(install_dir, exe)?;
    command.env("PATH", prepend_to_path(&backend_path));
    command.env("AZOOKEY_LLAMA_BACKEND_DIR", &backend_path);
    Ok(command)
}

fn process_command(install_dir: &Path, exe: &str) -> anyhow::Result<Command> {
    let exe_path = install_dir.join(exe);
    if !exe_path.is_file() {
        anyhow::bail!(
            "Refusing to launch {exe} outside the protected install directory; expected file is missing: {}",
            exe_path.display()
        );
    }

    let mut command = Command::new(&exe_path);
    command.current_dir(install_dir);
    Ok(command)
}

fn resolve_log_path(file_name: &str) -> Option<std::path::PathBuf> {
    env::var("APPDATA").ok().map(|appdata| {
        std::path::PathBuf::from(appdata)
            .join("Azookey")
            .join("logs")
            .join(file_name)
    })
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            ch if ch.is_control() => escaped.push(' '),
            ch => escaped.push(ch),
        }
    }
    escaped
}

fn crash_trace_is_in_progress(trace: &str) -> bool {
    trace.contains("\"state\":\"begin\"") || trace.contains("\"state\": \"begin\"")
}

fn crash_trace_is_completed(trace: &str) -> bool {
    trace.contains("\"state\":\"completed\"") || trace.contains("\"state\": \"completed\"")
}

fn preserve_launcher_crash_trace_if_incomplete(config: &AppConfig) {
    if !config.debug.server_crash_trace_enabled {
        return;
    }

    let Some(source_path) = resolve_log_path(LAUNCHER_CRASH_TRACE_FILE_NAME) else {
        return;
    };
    let Ok(trace) = fs::read_to_string(source_path) else {
        return;
    };
    if !crash_trace_is_in_progress(&trace) {
        return;
    }

    let Some(previous_path) = resolve_log_path(LAUNCHER_PREVIOUS_CRASH_TRACE_FILE_NAME) else {
        return;
    };
    if let Some(parent) = previous_path.parent() {
        if let Err(error) = fs::create_dir_all(parent) {
            eprintln!("[launcher] failed to create previous crash trace directory: {error}");
            return;
        }
    }
    if let Err(error) = fs::write(previous_path, trace.as_bytes()) {
        eprintln!("[launcher] failed to preserve previous crash trace: {error}");
    }
}

fn write_launcher_crash_trace(
    config: &AppConfig,
    operation: &str,
    stage: &str,
    state: &str,
    details: &str,
) {
    if !config.debug.server_crash_trace_enabled {
        return;
    }

    if stage == "spawning" && state == "begin" {
        preserve_launcher_crash_trace_if_incomplete(config);
    }

    let Some(path) = resolve_log_path(LAUNCHER_CRASH_TRACE_FILE_NAME) else {
        return;
    };
    if stage == "spawned"
        && state == "begin"
        && fs::read_to_string(&path)
            .map(|trace| crash_trace_is_completed(&trace))
            .unwrap_or(false)
    {
        return;
    }
    if let Some(parent) = path.parent() {
        if let Err(error) = fs::create_dir_all(parent) {
            eprintln!("[launcher] failed to create crash trace directory: {error}");
            return;
        }
    }

    let trace = format!(
        concat!(
            "{{\n",
            "  \"timestamp_ms\": {},\n",
            "  \"process_id\": {},\n",
            "  \"component\": \"launcher\",\n",
            "  \"operation\": \"{}\",\n",
            "  \"stage\": \"{}\",\n",
            "  \"state\": \"{}\",\n",
            "  \"details\": \"{}\"\n",
            "}}\n"
        ),
        now_timestamp_millis(),
        std::process::id(),
        json_escape(operation),
        json_escape(stage),
        json_escape(state),
        json_escape(details),
    );

    match File::create(path).and_then(|mut file| {
        file.write_all(trace.as_bytes())?;
        file.flush()
    }) {
        Ok(()) => {}
        Err(error) => eprintln!("[launcher] failed to write crash trace: {error}"),
    }
}

fn now_timestamp_millis() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or_default()
}

fn spawn_process(mut command: Command, exe: &str, prefix: &str) -> anyhow::Result<Child> {
    let mut child = command
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .with_context(|| format!("Failed to start {exe}"))?;

    if let Some(stdout) = child.stdout.take() {
        let stdout_reader = BufReader::new(stdout);
        let prefix_stdout = prefix.to_string();
        thread::spawn(move || {
            for line in stdout_reader.lines().map_while(Result::ok) {
                println!("{}: {}", prefix_stdout, line);
            }
        });
    }

    if let Some(stderr) = child.stderr.take() {
        let stderr_reader = BufReader::new(stderr);
        let prefix_stderr = prefix.to_string();
        thread::spawn(move || {
            for line in stderr_reader.lines().map_while(Result::ok) {
                eprintln!("{}: {}", prefix_stderr, line);
            }
        });
    }

    Ok(child)
}

fn wait_for_server_exit_or_restart_request(
    server: &mut Child,
    command_rx: &Receiver<LauncherCommand>,
) -> anyhow::Result<ServerExit> {
    loop {
        if shared::model_update::requested() {
            let status = terminate_server_child(server)?;
            while shared::model_update::requested() {
                shared::model_update::acknowledge_conversion_stopped();
                thread::sleep(Duration::from_millis(100));
            }
            return Ok(ServerExit::RestartRequested(status));
        }

        if let Some(status) = server
            .try_wait()
            .context("Failed to check azookey-server.exe status")?
        {
            return Ok(ServerExit::Exited(status));
        }

        match command_rx.recv_timeout(SERVER_WATCH_POLL_INTERVAL) {
            Ok(LauncherCommand::RestartServer { reply }) => {
                let result = terminate_server_child(server);
                let reply_result = result
                    .as_ref()
                    .map(|_| ())
                    .map_err(|error| error.to_string());
                let _ = reply.send(reply_result);
                return result.map(ServerExit::RestartRequested);
            }
            Err(RecvTimeoutError::Timeout) => {}
            Err(RecvTimeoutError::Disconnected) => {
                let status = server
                    .wait()
                    .context("Failed to wait for azookey-server.exe")?;
                return Ok(ServerExit::Exited(status));
            }
        }
    }
}

fn terminate_server_child(server: &mut Child) -> anyhow::Result<ExitStatus> {
    if let Some(status) = server
        .try_wait()
        .context("Failed to check azookey-server.exe status")?
    {
        return Ok(status);
    }

    server
        .kill()
        .context("Failed to terminate azookey-server.exe")?;
    server
        .wait()
        .context("Failed to wait for azookey-server.exe after restart request")
}

fn start_launcher_command_listener(
    command_tx: Sender<LauncherCommand>,
    pipe_path: &str,
) -> anyhow::Result<()> {
    let runtime = tokio::runtime::Runtime::new()?;
    let pipe = {
        let _runtime_guard = runtime.enter();
        create_launcher_command_pipe(pipe_path, true)?
    };

    let pipe_path = pipe_path.to_owned();
    thread::spawn(move || {
        if let Err(error) =
            runtime.block_on(run_launcher_command_listener(pipe, command_tx, &pipe_path))
        {
            eprintln!("[launcher] command listener stopped: {error:?}");
        }
    });
    Ok(())
}

async fn run_launcher_command_listener(
    mut pipe: NamedPipeServer,
    command_tx: Sender<LauncherCommand>,
    pipe_path: &str,
) -> anyhow::Result<()> {
    loop {
        let connection = pipe.connect().await;

        // Reserve the next instance before dropping the connected handle. This
        // keeps the name owned without discarding the client's unread response
        // (DisconnectNamedPipe would discard it; Tokio flush is a no-op).
        let next_pipe = create_launcher_command_pipe(pipe_path, false)?;
        match connection {
            Ok(()) => {
                if let Err(error) = handle_launcher_command(&mut pipe, &command_tx).await {
                    eprintln!("[launcher] command failed: {error:?}");
                }
            }
            Err(error) => eprintln!("[launcher] command connection failed: {error:?}"),
        }
        pipe = next_pipe;
    }
}

fn create_launcher_pipe_security_descriptor() -> anyhow::Result<PSECURITY_DESCRIPTOR> {
    let logon_sid = current_logon_sid_string()?;
    let sddl = launcher_pipe_sddl(&logon_sid);
    let sddl_wide = sddl.encode_utf16().chain(Some(0)).collect::<Vec<_>>();
    let mut security_descriptor = PSECURITY_DESCRIPTOR::default();

    unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            PCWSTR(sddl_wide.as_ptr()),
            SDDL_REVISION,
            &mut security_descriptor,
            None,
        )
        .context("Failed to create launcher pipe security descriptor")?;
    }

    Ok(security_descriptor)
}

fn launcher_pipe_sddl(logon_sid: &str) -> String {
    format!("D:(D;;GA;;;NU)(A;;GA;;;SY)(A;;GRGW;;;{logon_sid})S:(ML;;NW;;;ME)")
}

fn current_logon_sid_string() -> anyhow::Result<String> {
    unsafe {
        let mut token = HANDLE::default();
        OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token)
            .context("Failed to open current process token")?;

        let result = logon_sid_string_from_token(token);
        let _ = CloseHandle(token);
        result
    }
}

fn logon_sid_string_from_token(token: HANDLE) -> anyhow::Result<String> {
    unsafe {
        let mut token_info_length = 0;
        let _ = GetTokenInformation(token, TokenLogonSid, None, 0, &mut token_info_length);
        anyhow::ensure!(
            token_info_length >= size_of::<TOKEN_GROUPS>() as u32,
            "Failed to get current logon SID buffer size"
        );

        // TOKEN_GROUPS contains pointers; use the same aligned buffer and
        // fail-closed single-logon-SID check as the server.
        let word_count = (token_info_length as usize).div_ceil(size_of::<usize>());
        let mut token_info = vec![0usize; word_count];
        GetTokenInformation(
            token,
            TokenLogonSid,
            Some(token_info.as_mut_ptr().cast()),
            token_info_length,
            &mut token_info_length,
        )
        .context("Failed to get current logon SID")?;

        let token_groups = &*(token_info.as_ptr() as *const TOKEN_GROUPS);
        anyhow::ensure!(
            token_groups.GroupCount == 1,
            "Expected one logon SID, got {}",
            token_groups.GroupCount
        );
        let mut sid_string = PWSTR::null();
        ConvertSidToStringSidW(token_groups.Groups[0].Sid, &mut sid_string)
            .context("Failed to convert current logon SID to string")?;

        let result = sid_string
            .to_string()
            .context("Failed to decode current logon SID string");
        let _ = LocalFree(HLOCAL(sid_string.as_ptr().cast()));
        result
    }
}

fn create_launcher_command_pipe(
    path: &str,
    first_pipe_instance: bool,
) -> anyhow::Result<NamedPipeServer> {
    let descriptor = create_launcher_pipe_security_descriptor()?;
    let mut attributes = SECURITY_ATTRIBUTES {
        nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: descriptor.0,
        bInheritHandle: false.into(),
    };
    let result = unsafe {
        ServerOptions::new()
            .first_pipe_instance(first_pipe_instance)
            .create_with_security_attributes_raw(path, addr_of_mut!(attributes) as *mut c_void)
    };
    unsafe {
        let _ = LocalFree(HLOCAL(descriptor.0));
    }
    result.context("Failed to create launcher command pipe")
}

async fn handle_launcher_command(
    pipe: &mut NamedPipeServer,
    command_tx: &Sender<LauncherCommand>,
) -> anyhow::Result<()> {
    let mut buffer = [0u8; 256];
    let size = pipe
        .read(&mut buffer)
        .await
        .context("Failed to read launcher command")?;

    let result = match parse_launcher_command(&buffer[..size]) {
        Ok(LauncherCommandKind::RestartServer) => request_server_restart(command_tx),
        Err(error) => Err(error),
    };
    let response = launcher_response(result);

    pipe.write_all(response.as_bytes())
        .await
        .context("Failed to write launcher command response")?;
    pipe.flush()
        .await
        .context("Failed to flush launcher command response")?;

    Ok(())
}

fn parse_launcher_command(bytes: &[u8]) -> anyhow::Result<LauncherCommandKind> {
    match std::str::from_utf8(bytes)
        .context("Launcher command is not UTF-8")?
        .trim()
    {
        LAUNCHER_RESTART_COMMAND => Ok(LauncherCommandKind::RestartServer),
        command => anyhow::bail!("Unknown launcher command: {command}"),
    }
}

fn request_server_restart(command_tx: &Sender<LauncherCommand>) -> anyhow::Result<()> {
    let (reply_tx, reply_rx) = mpsc::channel();
    command_tx
        .send(LauncherCommand::RestartServer { reply: reply_tx })
        .context("Server watchdog is not running")?;

    match reply_rx.recv_timeout(LAUNCHER_COMMAND_TIMEOUT) {
        Ok(Ok(())) => Ok(()),
        Ok(Err(message)) => anyhow::bail!(message),
        Err(RecvTimeoutError::Timeout) => anyhow::bail!("Timed out waiting for server restart"),
        Err(RecvTimeoutError::Disconnected) => {
            anyhow::bail!("Server watchdog stopped before restart completed")
        }
    }
}

fn launcher_response(result: anyhow::Result<()>) -> String {
    match result {
        Ok(()) => "ok\n".to_string(),
        Err(error) => format!("error:{error}\n"),
    }
}

fn load_config() -> AppConfig {
    AppConfig::new().unwrap_or_else(|error| {
        eprintln!("[launcher] Failed to load settings; using defaults: {error}");
        AppConfig::default()
    })
}

fn backend_dir(config: &AppConfig) -> &'static str {
    match config.zenzai.backend.as_str() {
        "cuda" => "llama_cuda_b12125",
        "vulkan" => "llama_vulkan",
        _ => "llama_cpu",
    }
}

fn prepend_to_path(path: &Path) -> String {
    let existing = env::var("PATH").unwrap_or_default();
    format!("{};{}", path.to_string_lossy(), existing)
}

#[derive(Debug)]
enum ServerExit {
    Exited(ExitStatus),
    RestartRequested(ExitStatus),
}

enum LauncherCommand {
    RestartServer {
        reply: Sender<std::result::Result<(), String>>,
    },
}

enum LauncherCommandKind {
    RestartServer,
}

#[cfg(test)]
mod tests {
    #[test]
    fn translation_switch_controls_its_own_process_and_shares_the_backend() {
        let root =
            std::env::temp_dir().join(format!("zerocon-translation-spec-{}", std::process::id()));
        std::fs::create_dir_all(root.join("llama_vulkan")).unwrap();
        std::fs::create_dir_all(root.join("models")).unwrap();
        std::fs::write(root.join("llama_vulkan/llama-server.exe"), []).unwrap();
        let model = root
            .join("models")
            .join(shared::runtime_assets::TRANSLATION_0_8B);
        let mut config = shared::AppConfig::default();
        assert!(super::translation_server_spec_for_session(&root, &config, false).is_none());
        config.general.experimental_translation = true;
        assert!(super::translation_server_spec_for_session(&root, &config, false).is_none());
        std::fs::write(&model, []).unwrap();
        // On-demand mode needs a session; persistent mode is tested without one.
        assert!(super::translation_server_spec_for_session(&root, &config, false).is_none());
        config.general.experimental_ai_assist = false;
        config.zenzai.gpu_layers = 4;
        let spec = super::translation_server_spec_for_session(&root, &config, true).unwrap();
        assert_eq!(spec.model, model);
        assert_eq!(
            spec.backend,
            shared::runtime_assets::backend(&root, &config.zenzai.backend)
        );
        assert_eq!(spec.gpu_layers, 4);
        assert!(super::translation_server_spec_for_session(&root, &config, false).is_none());
        config.general.translation_keep_alive = true;
        assert_eq!(
            super::translation_server_spec_for_session(&root, &config, false).unwrap(),
            spec
        );
        config.general.translation_keep_alive = false;
        assert!(super::translation_server_spec_for_session(&root, &config, false).is_none());
        config.general.translation_keep_alive = true;
        config.general.experimental_translation = false;
        assert!(super::translation_server_spec_for_session(&root, &config, false).is_none());
        std::fs::remove_file(model).unwrap();
        std::fs::remove_file(root.join("llama_vulkan/llama-server.exe")).unwrap();
        std::fs::remove_dir(root.join("models")).unwrap();
        std::fs::remove_dir(root.join("llama_vulkan")).unwrap();
        std::fs::remove_dir(root).unwrap();
    }
    #[test]
    fn closing_prediction_job_stops_only_its_owned_process() {
        use std::os::windows::io::AsRawHandle;
        use std::os::windows::process::CommandExt;
        let mut child = std::process::Command::new("powershell.exe")
            .args(["-NoProfile", "-Command", "Start-Sleep -Seconds 30"])
            .creation_flags(0x08000000)
            .spawn()
            .unwrap();
        let job = super::PredictionJob::attach(&child).unwrap();
        assert!(child.try_wait().unwrap().is_none());
        drop(job);
        assert_eq!(
            unsafe {
                windows::Win32::System::Threading::WaitForSingleObject(
                    windows::Win32::Foundation::HANDLE(child.as_raw_handle()),
                    2000,
                )
            },
            windows::Win32::Foundation::WAIT_OBJECT_0
        );
        let _ = child.wait().unwrap();
    }
    #[test]
    fn zerocon_prediction_has_its_own_model_backend_and_disabled_state() {
        let path =
            std::env::temp_dir().join(format!("azookey-prediction-spec-{}", std::process::id()));
        std::fs::create_dir_all(path.join("llama_vulkan")).unwrap();
        std::fs::create_dir_all(path.join("models")).unwrap();
        std::fs::write(
            path.join("models").join(shared::runtime_assets::NANO_IQ4),
            [],
        )
        .unwrap();
        std::fs::write(path.join("llama_vulkan/llama-server.exe"), []).unwrap();
        let mut config = shared::AppConfig::default();
        config.general.experimental_ai_assist = true;
        assert_eq!(config.zenzai.backend, "vulkan");
        for (backend, directory) in [
            ("vulkan", "llama_vulkan"),
            ("cuda", "llama_cuda_b12125"),
            ("cpu", "llama_cpu"),
        ] {
            std::fs::create_dir_all(path.join(directory)).unwrap();
            std::fs::write(path.join(directory).join("llama-server.exe"), []).unwrap();
            config.zenzai.backend = backend.into();
            let spec = super::prediction_server_spec(&path, &config).unwrap();
            assert_eq!(
                spec.model,
                path.join("models").join(shared::runtime_assets::NANO_IQ4)
            );
            assert_eq!(spec.backend, path.join(super::backend_dir(&config)));
            assert_eq!(spec.backend, path.join(directory));
            assert_eq!(spec.gpu_layers, if backend == "cpu" { 0 } else { 99 });
        }
        config.zenzai.backend = "vulkan".into();
        let full = super::prediction_server_spec(&path, &config).unwrap();
        config.zenzai.gpu_layers = 4;
        let limited = super::prediction_server_spec(&path, &config).unwrap();
        assert_ne!(
            full, limited,
            "memory changes must replace the owned process"
        );
        assert_eq!(limited.gpu_layers, 4);
        let corrector_path = path.join("models").join(shared::runtime_assets::CORRECTOR);
        std::fs::create_dir_all(corrector_path.parent().unwrap()).unwrap();
        std::fs::write(&corrector_path, []).unwrap();
        let corrector = super::assist_server_spec(&path, &config, true).unwrap();
        assert_eq!(corrector.gpu_layers, 4);
        config.general.experimental_ai_engine = "external".into();
        assert!(super::assist_server_spec(&path, &config, true).is_none());
        assert!(super::prediction_server_spec(&path, &config).is_some());
        config.general.experimental_ai_assist = false;
        assert!(super::prediction_server_spec(&path, &config).is_none());
        assert!(super::assist_server_spec(&path, &config, true).is_none());
        std::fs::remove_file(corrector_path).unwrap();
        std::fs::remove_file(path.join("models").join(shared::runtime_assets::NANO_IQ4)).unwrap();
        std::fs::remove_dir(path.join("models")).unwrap();
        for directory in ["llama_vulkan", "llama_cuda_b12125", "llama_cpu"] {
            std::fs::remove_file(path.join(directory).join("llama-server.exe")).unwrap();
            std::fs::remove_dir(path.join(directory)).unwrap();
        }
        std::fs::remove_dir(path).unwrap();
    }
    use super::{
        launcher_pipe_sddl, launcher_response, parse_launcher_command, process_command,
        restart_delay_after_server_exit, LauncherCommandKind, SERVER_RESTART_BURST_LIMIT,
        SERVER_RESTART_COOLDOWN, SERVER_RESTART_DELAY,
    };
    use std::collections::VecDeque;
    use std::time::{Duration, Instant};

    #[test]
    fn parse_launcher_command_accepts_restart_server() {
        assert!(matches!(
            parse_launcher_command(b"restart-server\n").unwrap(),
            LauncherCommandKind::RestartServer
        ));
    }

    #[test]
    fn parse_launcher_command_rejects_unknown_command() {
        assert!(parse_launcher_command(b"stop-server\n").is_err());
    }

    #[test]
    fn launcher_response_encodes_success_and_error() {
        assert_eq!(launcher_response(Ok(())), "ok\n");
        assert_eq!(
            launcher_response(Err(anyhow::anyhow!("denied"))),
            "error:denied\n"
        );
    }

    #[test]
    fn launcher_pipe_sddl_denies_network_and_grants_only_system_and_current_logon() {
        assert_eq!(
            launcher_pipe_sddl("S-1-5-5-1-2"),
            "D:(D;;GA;;;NU)(A;;GA;;;SY)(A;;GRGW;;;S-1-5-5-1-2)S:(ML;;NW;;;ME)"
        );
    }

    #[test]
    fn launcher_pipe_reserves_name_across_connections_and_duplicate_startup_fails() {
        use super::{
            create_launcher_command_pipe, run_launcher_command_listener,
            start_launcher_command_listener,
        };
        use std::sync::mpsc;
        use tokio::{
            io::{AsyncReadExt, AsyncWriteExt},
            net::windows::named_pipe::ClientOptions,
        };
        use windows::Win32::Foundation::{ERROR_ACCESS_DENIED, ERROR_PIPE_BUSY};

        fn open_client(path: &str) -> tokio::net::windows::named_pipe::NamedPipeClient {
            let deadline = Instant::now() + Duration::from_secs(5);
            loop {
                match ClientOptions::new().open(path) {
                    Ok(client) => return client,
                    Err(error)
                        if error.raw_os_error() == Some(ERROR_PIPE_BUSY.0 as i32)
                            && Instant::now() < deadline =>
                    {
                        std::thread::sleep(Duration::from_millis(1));
                    }
                    Err(error) => panic!("Failed to open test launcher pipe: {error}"),
                }
            }
        }

        let runtime = tokio::runtime::Runtime::new().unwrap();
        let path = format!(
            r"\\.\pipe\LOCAL\azookey_launcher_test_{}_{}",
            std::process::id(),
            super::now_timestamp_millis()
        );
        let pipe = {
            let _runtime_guard = runtime.enter();
            create_launcher_command_pipe(&path, true).unwrap()
        };
        let (command_tx, command_rx) = mpsc::channel();

        // Initialization failure must reach main's gate before children spawn.
        assert!(start_launcher_command_listener(command_tx.clone(), &path).is_err());
        let watchdog = std::thread::spawn(move || {
            while let Ok(super::LauncherCommand::RestartServer { reply }) = command_rx.recv() {
                reply.send(Ok(())).unwrap();
            }
        });

        runtime.block_on(async {
            let listener_path = path.clone();
            let listener = tokio::spawn(async move {
                run_launcher_command_listener(pipe, command_tx, &listener_path).await
            });
            for _ in 0..3 {
                // An abandoned connection must not release the launcher name
                // or stop subsequent restart requests.
                drop(open_client(&path));
                let mut client = open_client(&path);
                client.write_all(b"restart-server\n").await.unwrap();
                let mut response = [0u8; 256];
                let size = client.read(&mut response).await.unwrap();
                assert_eq!(&response[..size], b"ok\n");

                let error = create_launcher_command_pipe(&path, true).unwrap_err();
                assert_eq!(
                    error
                        .downcast_ref::<std::io::Error>()
                        .unwrap()
                        .raw_os_error(),
                    Some(ERROR_ACCESS_DENIED.0 as i32)
                );
            }

            listener.abort();
            assert!(listener.await.unwrap_err().is_cancelled());
            // Releasing the resident handle permits a new launcher instance.
            assert!(create_launcher_command_pipe(&path, true).is_ok());
        });
        watchdog.join().unwrap();
    }

    #[test]
    fn missing_protected_executable_never_falls_back_to_path() {
        let install_dir = std::env::temp_dir().join("azookey-missing-protected-executable-test");

        let error = process_command(&install_dir, "ui.exe").unwrap_err();

        assert!(error.to_string().contains("protected install directory"));
        assert!(error.to_string().contains("ui.exe"));
    }

    #[test]
    fn requested_restarts_do_not_count_toward_crash_cooldown() {
        let mut recent_restarts = VecDeque::new();
        let start = Instant::now();

        for offset in 0..SERVER_RESTART_BURST_LIMIT {
            assert_eq!(
                restart_delay_after_server_exit(
                    &mut recent_restarts,
                    true,
                    start + Duration::from_secs(offset as u64)
                ),
                None
            );
        }

        assert!(recent_restarts.is_empty());
        assert_eq!(
            restart_delay_after_server_exit(&mut recent_restarts, false, start),
            Some(SERVER_RESTART_DELAY)
        );
    }

    #[test]
    fn unexpected_restarts_trigger_crash_cooldown() {
        let mut recent_restarts = VecDeque::new();
        let start = Instant::now();

        for offset in 0..SERVER_RESTART_BURST_LIMIT - 1 {
            assert_eq!(
                restart_delay_after_server_exit(
                    &mut recent_restarts,
                    false,
                    start + Duration::from_secs(offset as u64)
                ),
                Some(SERVER_RESTART_DELAY)
            );
        }

        assert_eq!(
            restart_delay_after_server_exit(
                &mut recent_restarts,
                false,
                start + Duration::from_secs(SERVER_RESTART_BURST_LIMIT as u64)
            ),
            Some(SERVER_RESTART_COOLDOWN)
        );
        assert!(recent_restarts.is_empty());
    }
}

#[derive(Debug, PartialEq, Eq)]
struct AssistServerSpec {
    model: std::path::PathBuf,
    backend: std::path::PathBuf,
    gpu_layers: u32,
}

struct PredictionJob(HANDLE);
impl PredictionJob {
    fn attach(child: &Child) -> anyhow::Result<Self> {
        use std::os::windows::io::AsRawHandle;
        use windows::Win32::System::JobObjects::{
            AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
            SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
            JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        };
        unsafe {
            let job = Self(CreateJobObjectW(None, PCWSTR::null())?);
            let limits = JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
                BasicLimitInformation:
                    windows::Win32::System::JobObjects::JOBOBJECT_BASIC_LIMIT_INFORMATION {
                        LimitFlags: JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
                        ..Default::default()
                    },
                ..Default::default()
            };
            SetInformationJobObject(
                job.0,
                JobObjectExtendedLimitInformation,
                &limits as *const _ as *const c_void,
                size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            )?;
            AssignProcessToJobObject(job.0, HANDLE(child.as_raw_handle()))?;
            Ok(job)
        }
    }
}
impl Drop for PredictionJob {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.0);
        }
    }
}

fn prediction_server_spec(install_dir: &Path, config: &AppConfig) -> Option<AssistServerSpec> {
    assist_server_spec(install_dir, config, false)
}

fn assist_server_spec(
    install_dir: &Path,
    config: &AppConfig,
    corrector: bool,
) -> Option<AssistServerSpec> {
    if !config.general.experimental_ai_assist
        || (corrector && config.general.experimental_ai_engine != "embedded")
    {
        return None;
    }
    let model = if corrector {
        shared::runtime_assets::corrector_model(install_dir)
    } else {
        shared::runtime_assets::converter_model(install_dir, &config.zenzai.model)
    };
    let backend = shared::runtime_assets::backend(install_dir, &config.zenzai.backend);
    (model.is_file() && backend.join("llama-server.exe").is_file()).then_some(AssistServerSpec {
        model,
        backend,
        gpu_layers: config.zenzai.effective_gpu_layers(),
    })
}

// This worker owns only the prediction process it launches. Never stop another
// application's server merely because it happens to occupy the same port.
fn watch_prediction_server(install_dir: &Path) {
    watch_assist_server(install_dir, AssistRole::Prediction);
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum AssistRole {
    Prediction,
    Corrector,
    Translation,
}

fn translation_server_spec(install_dir: &Path, config: &AppConfig) -> Option<AssistServerSpec> {
    translation_server_spec_for_session(
        install_dir,
        config,
        config.general.translation_keep_alive
            || shared::translation_session::is_translation_active(),
    )
}

fn translation_server_spec_for_session(
    install_dir: &Path,
    config: &AppConfig,
    session_active: bool,
) -> Option<AssistServerSpec> {
    if !config.general.experimental_translation
        || !(config.general.translation_keep_alive || session_active)
    {
        return None;
    }
    let model = shared::runtime_assets::translation_model_path(
        install_dir,
        &config.general.translation_model,
    );
    let backend = shared::runtime_assets::backend(install_dir, &config.zenzai.backend);
    (model.is_file() && backend.join("llama-server.exe").is_file()).then_some(AssistServerSpec {
        model,
        backend,
        gpu_layers: config.zenzai.effective_gpu_layers(),
    })
}

fn watch_assist_server(install_dir: &Path, role: AssistRole) {
    let corrector = role == AssistRole::Corrector;
    let translation = role == AssistRole::Translation;
    let port = if translation {
        shared::TRANSLATION_SERVER_PORT
    } else if corrector {
        18090
    } else {
        shared::PREDICTION_SERVER_PORT
    };
    let label = if translation {
        "[translation]"
    } else if corrector {
        "[corrector]"
    } else {
        "[prediction]"
    };
    let mut config_cache = shared::AppConfigCache::new();
    let mut owned: Option<(AssistServerSpec, Child, PredictionJob)> = None;
    loop {
        if shared::model_update::requested() {
            if let Some((_, mut child, _job)) = owned.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
            // No model process owned by this watcher remains before acknowledging.
            if role == AssistRole::Prediction {
                shared::model_update::acknowledge_prediction_stopped();
            }
            thread::sleep(Duration::from_millis(100));
            continue;
        }
        // AppConfig::new() normalizes and rewrites settings. Polling must only
        // read them, and a transient read error must not stop a healthy model.
        let Ok(config) = config_cache.read() else {
            thread::sleep(Duration::from_secs(2));
            continue;
        };
        let desired = if translation {
            translation_server_spec(install_dir, config)
        } else if corrector {
            assist_server_spec(install_dir, config, true)
        } else {
            prediction_server_spec(install_dir, config)
        };
        if let Some((spec, child, _job)) = owned.as_mut() {
            if desired.as_ref() != Some(spec) {
                let _ = child.kill();
                let _ = child.wait();
                owned = None;
            } else if !matches!(child.try_wait(), Ok(None)) {
                let _ = child.wait();
                owned = None;
            }
        }
        if owned.is_none() {
            if let Some(spec) = desired {
                let listening = std::net::TcpStream::connect_timeout(
                    &std::net::SocketAddr::from(([127, 0, 0, 1], port)),
                    Duration::from_millis(50),
                )
                .is_ok();
                if !listening {
                    let mut command = Command::new(spec.backend.join("llama-server.exe"));
                    command.current_dir(&spec.backend);
                    command.env("PATH", prepend_to_path(&spec.backend));
                    command
                        .arg("-m")
                        .arg(&spec.model)
                        .arg("--port")
                        .arg(port.to_string())
                        .args([
                            "--host",
                            "127.0.0.1",
                            "-c",
                            if translation {
                                "4096"
                            } else if corrector {
                                "512"
                            } else {
                                "2048"
                            },
                            "--threads",
                            "4",
                        ])
                        .arg("-ngl")
                        .arg(spec.gpu_layers.to_string());
                    if corrector {
                        command.args(["-n", "32"]);
                    }
                    if translation {
                        command.args(["--alias", "plamo-3-translate-2b", "--parallel", "1"]);
                    }
                    use std::os::windows::process::CommandExt;
                    command.creation_flags(0x08000000);
                    match spawn_process(command, "llama-server.exe", label) {
                        Ok(mut child) => {
                            let job = match PredictionJob::attach(&child) {
                                Ok(job) => job,
                                Err(error) => {
                                    let _ = child.kill();
                                    let _ = child.wait();
                                    eprintln!("[launcher] cannot own prediction process lifetime: {error:?}");
                                    thread::sleep(Duration::from_secs(2));
                                    continue;
                                }
                            };
                            println!(
                                "[launcher] {label} port={port} model={} backend={} gpu_layers={}",
                                spec.model.display(),
                                spec.backend.display(),
                                spec.gpu_layers
                            );
                            owned = Some((spec, child, job));
                        }
                        Err(error) => eprintln!("[launcher] prediction server failed: {error:?}"),
                    }
                }
            }
        }
        thread::sleep(Duration::from_secs(2));
    }
}

#[allow(dead_code)]
fn watch_ai_server_process(install_dir: &Path) {
    watch_assist_server(install_dir, AssistRole::Corrector);
}
