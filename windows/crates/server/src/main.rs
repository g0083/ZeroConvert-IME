#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use azookey_server::{CompositionOwner, PipeClient, TonicNamedPipeServer};
use tonic::{transport::Server, Request, Response, Status};
use tonic_reflection::server::Builder as ReflectionBuilder;
use windows::Win32::System::Threading::{GetCurrentProcess, SetPriorityClass, HIGH_PRIORITY_CLASS};

use shared::proto::azookey_service_server::{AzookeyService, AzookeyServiceServer};
use shared::proto::{
    AdjustClauseBoundaryRequest, AdjustClauseBoundaryResponse, AdvanceClauseRequest,
    AdvanceClauseResponse, AppendTextRequest, AppendTextResponse, ClearTextRequest,
    ClearTextResponse, ComposingText, CompositionOperationKind, CompositionSnapshotOperation,
    MoveCursorRequest, MoveCursorResponse, PerformanceLogRequest, PerformanceLogResponse,
    PrepareFutureClausesRequest, PrepareFutureClausesResponse, PreparedClauseAdvance,
    RemoveTextRequest, RemoveTextResponse, ReplaceCompositionRequest, ReplaceCompositionResponse,
    ShrinkTextRequest, ShrinkTextResponse, StartReconversionRequest, StartReconversionResponse,
    Suggestion, UpdateCompositionSnapshotRequest, UpdateCompositionSnapshotResponse,
};
use shared::{server_pipe_path, AppConfig};

use std::{
    backtrace::Backtrace,
    collections::HashSet,
    ffi::{c_char, c_int, CStr, CString, OsStr},
    fs::{self, File, OpenOptions},
    io::{BufWriter, Write},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering},
        mpsc, Arc, OnceLock,
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

mod ai_assist;
use ai_assist::AiAssistConfig;

const USE_ZENZAI: bool = true;
static ASSIST_REQUEST_SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);
static JEV_CONVERSION_ENABLED: AtomicBool = AtomicBool::new(false);
static AI_ASSIST_ENABLED: AtomicBool = AtomicBool::new(false);
static TRANSLATION_ENABLED: AtomicBool = AtomicBool::new(false);
static TRANSLATION_TARGET: std::sync::RwLock<String> = std::sync::RwLock::new(String::new());
static TRANSLATION_REQUEST_SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);
// Latest translation request's cancellation token. A new kind==3 request
// cancels the previous worker so the fresh baseline is not queued behind it.
static TRANSLATION_CANCEL: std::sync::Mutex<
    Option<Arc<ai_assist::translation::TranslationCancel>>,
> = std::sync::Mutex::new(None);
static AI_ENGINE_IS_LM_STUDIO: AtomicBool = AtomicBool::new(false);
static LAST_CONTEXT: std::sync::RwLock<String> = std::sync::RwLock::new(String::new());
const MAX_CONVERSION_CONTINUATION_READING_COUNT: usize = 64;
const MAX_CONVERSION_CONTINUATION_SURFACE_COUNT: usize = 128;

#[derive(Debug, Clone, PartialEq, Eq)]
struct ConversionContinuation {
    reading: String,
    surface: String,
}

static CONVERSION_CONTINUATION: std::sync::RwLock<Option<ConversionContinuation>> =
    std::sync::RwLock::new(None);

fn reload_ai_assist_from_settings(config: &AppConfig) {
    TRANSLATION_ENABLED.store(config.general.experimental_translation, Ordering::Relaxed);
    if let Ok(mut target) = TRANSLATION_TARGET.write() {
        *target = config.general.translation_target.clone();
    }
    ai_assist::corrector::clear_cache();
    ai_assist::prediction::clear_cache();
    let enabled = config.general.experimental_ai_assist;
    AI_ASSIST_ENABLED.store(enabled, Ordering::Relaxed);
    let is_lm_studio = config.general.experimental_ai_engine == "lm_studio";
    AI_ENGINE_IS_LM_STUDIO.store(is_lm_studio, Ordering::Relaxed);
    if enabled && !is_lm_studio {
        ai_assist::keyboard_hybrid::prewarm();
    }
    eprintln!(
        "[AI Assist] config loaded: enabled={}, prediction=zerocon, corrector_engine={}",
        enabled,
        if is_lm_studio {
            "lm_studio"
        } else {
            "embedded"
        }
    );
}

/// AI アシスト候補 (誤字補正・動的マクロ・先読み) を候補リストへ追加する。
///
/// 通常変換の 1 候補目と候補の順序は破壊せず、追加候補はリスト末尾 (あるいは
/// 1 候補目の直後) に控えめに追加する。
fn current_assist_config() -> AiAssistConfig {
    let is_lm_studio = AI_ENGINE_IS_LM_STUDIO.load(Ordering::Relaxed);
    AiAssistConfig {
        enabled: AI_ASSIST_ENABLED.load(Ordering::Relaxed),
        engine: if is_lm_studio {
            "lm_studio".to_string()
        } else {
            "embedded".to_string()
        },
        corrector_port: if is_lm_studio {
            1234
        } else {
            shared::PREDICTION_SERVER_PORT
        },
        prediction_port: shared::PREDICTION_SERVER_PORT,
    }
}

// Deterministic macro expansion stays immediate, including numeric expressions.
fn append_local_macros(
    hiragana_text: &str,
    whole_composition_count: i32,
    final_suggestions: &mut Vec<Suggestion>,
) {
    let macro_candidates = ai_assist::current_expansions(hiragana_text);
    if !macro_candidates.is_empty() {
        let mut inserted = Vec::new();
        for candidate in macro_candidates {
            if candidate.text.trim().is_empty()
                || inserted.iter().any(|text: &String| text == &candidate.text)
                || final_suggestions.iter().any(|s| s.text == candidate.text)
            {
                continue;
            }
            inserted.push(candidate.text.clone());
        }
        if !inserted.is_empty() {
            let insert_at = usize::from(!final_suggestions.is_empty());
            let additions: Vec<Suggestion> = inserted
                .into_iter()
                .map(|text| Suggestion {
                    text,
                    subtext: String::new(),
                    corresponding_count: whole_composition_count,
                    candidate_id: shared::ai_candidate::DYNAMIC_MACRO,
                })
                .collect();
            for (offset, suggestion) in additions.into_iter().enumerate() {
                final_suggestions.insert(insert_at + offset, suggestion);
            }
        }
    }
}

fn append_ai_assist_candidates(
    context: &str,
    hiragana_text: &str,
    composing_raw: &str,
    config: &AiAssistConfig,
    final_suggestions: &mut Vec<Suggestion>,
    kind: u32,
) -> (u32, String) {
    if !config.enabled || hiragana_text.is_empty() {
        return (0, String::new());
    }
    let first_surface = final_suggestions
        .first()
        .map(|s| s.text.clone())
        .unwrap_or_else(|| hiragana_text.to_string());
    // 追加候補は変換全体の読みを置き換えるため、通常候補と同じ入力長を使う。
    let whole_composition_count = final_suggestions
        .first()
        .map(|s| s.corresponding_count)
        .unwrap_or(0);

    // 1. Generate correction against the immutable normal-conversion snapshot.
    //    Evidence is evaluated independently from the unchanged-surface prediction.
    // The canonical reading preserves mixed kana/Latin. Never correct a second,
    // all-Latin raw key sequence as though it were an independent Japanese input.
    let hybrid = (kind != 2)
        .then(|| ai_assist::keyboard_hybrid::suggest(context, hiragana_text, composing_raw, config))
        .flatten();
    let hybrid_proposal = hybrid.as_ref().is_some_and(Option::is_some);
    let targets = [hiragana_text];
    let mut found_typo = hybrid.flatten();
    for target in targets
        .into_iter()
        .filter(|_| kind != 2 && !hybrid_proposal)
    {
        if let Some(suggestion) =
            ai_assist::corrector::suggest_composition(context, target, &first_surface, config)
        {
            found_typo = Some(suggestion);
            break;
        }
    }
    let mut relevance = 0;
    let mut continuation = String::new();
    if let Some(suggestion) = found_typo {
        if !suggestion.surface.is_empty() && suggestion.surface != first_surface {
            let subtext = if suggestion.learned {
                "💡 学習済み".to_string()
            } else {
                "💡 もしかして".to_string()
            };
            // Preserve normal candidate order. The client decides whether to propose or apply.
            final_suggestions.push(Suggestion {
                text: suggestion.surface.clone(),
                subtext,
                corresponding_count: whole_composition_count,
                candidate_id: shared::ai_candidate::TYPO_CORRECTION,
            });
            // 確定通知 (candidate_id 999901) と突き合わせるため候補を記憶する。
            ai_assist::remember_typo_candidate(&suggestion);
            relevance = if hybrid_proposal {
                // Strong bounded keyboard evidence should be offered ahead of
                // speculative continuation, while remaining below automatic 100.
                80
            } else if !hybrid_proposal
                && ai_assist::corrector::can_auto_apply_composition(
                    hiragana_text,
                    &first_surface,
                    &suggestion.surface,
                )
            {
                100
            } else if suggestion.learned {
                70
            } else {
                30
            };
        }
    }

    append_local_macros(hiragana_text, whole_composition_count, final_suggestions);

    // Generate against the unchanged visible baseline, even if a correction exists.
    if kind != 1 {
        if let Some(prediction) =
            ai_assist::prediction::suggest(context, hiragana_text, &first_surface, config)
        {
            let text = format!("{first_surface}{}", prediction.continuation);
            if !final_suggestions.iter().any(|s| s.text == text) {
                continuation = prediction.continuation;
                relevance = relevance.max(prediction.relevance);
                final_suggestions.push(Suggestion {
                    text,
                    subtext: continuation.clone(),
                    corresponding_count: whole_composition_count,
                    candidate_id: shared::ai_candidate::PREDICTIVE,
                });
            }
        }
    }
    (relevance, continuation)
}
// Do not queue obsolete cloud work or hold the composition mutation lock.
static JEV_REQUEST_SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(2);
static PREDICTION_REQUEST_SLOTS: tokio::sync::Semaphore = tokio::sync::Semaphore::const_new(1);
const INPUT_STYLE_DIRECT: i32 = 1;
const SERVER_LOG_FILE_NAME: &str = "server.log";
const SERVER_PERFORMANCE_LOG_FILE_NAME: &str = "server-performance.tsv";
const SERVER_CRASH_TRACE_FILE_NAME: &str = "server-crash-trace.json";
const SERVER_PREVIOUS_CRASH_TRACE_FILE_NAME: &str = "server-crash-trace.previous.json";
const LAUNCHER_CRASH_TRACE_FILE_NAME: &str = "launcher-crash-trace.json";
const LAUNCHER_PREVIOUS_CRASH_TRACE_FILE_NAME: &str = "launcher-crash-trace.previous.json";
const SERVER_PERFORMANCE_LOG_HEADER: &str =
    "timestamp_ms\trequest_id\tcomponent\toperation\tstage\telapsed_ms\tdetails";
const LOG_MAX_BYTES: u64 = 4 * 1024 * 1024;
const LOG_FLUSH_INTERVAL: Duration = Duration::from_millis(500);
const LOG_FLUSH_ACK_TIMEOUT: Duration = Duration::from_secs(2);
const WARMUP_RECENT_INPUT_SKIP_MS: u64 = 2_000;
const MAX_RECONVERSION_SURFACE_COUNT: usize = 128;
const MAX_RECONVERSION_READINGS: usize = 4;
const MAX_RECONVERSION_SUGGESTIONS: usize = 100;

static SERVER_LOG_WORKER: OnceLock<ServerLogWorker> = OnceLock::new();
static SERVER_LOG_ENABLED: AtomicBool = AtomicBool::new(false);
static SERVER_PERFORMANCE_LOG_ENABLED: AtomicBool = AtomicBool::new(false);
static SERVER_LOG_LEVEL: AtomicU8 = AtomicU8::new(ServerLogLevel::Warn as u8);
static SERVER_CRASH_TRACE_ENABLED: AtomicBool = AtomicBool::new(true);
static REQUEST_SEQUENCE: AtomicU64 = AtomicU64::new(1);
const SERVER_GENERATED_REQUEST_ID_PREFIX: u64 = 1 << 63;
static SERVER_SESSION_ID: OnceLock<u64> = OnceLock::new();
static HAS_ACTIVE_COMPOSITION: AtomicBool = AtomicBool::new(false);
static SERVER_REQUESTS_IN_FLIGHT: AtomicU64 = AtomicU64::new(0);
static LAST_INPUT_REQUEST_FINISHED_MS: AtomicU64 = AtomicU64::new(0);
static MONOTONIC_START: OnceLock<Instant> = OnceLock::new();

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
enum ServerLogLevel {
    Off = 0,
    Error = 1,
    Warn = 2,
    Info = 3,
    Debug = 4,
}

impl ServerLogLevel {
    fn as_str(self) -> &'static str {
        match self {
            Self::Off => "OFF",
            Self::Error => "ERROR",
            Self::Warn => "WARN",
            Self::Info => "INFO",
            Self::Debug => "DEBUG",
        }
    }

    fn from_u8(value: u8) -> Self {
        match value {
            1 => Self::Error,
            2 => Self::Warn,
            3 => Self::Info,
            4 => Self::Debug,
            _ => Self::Off,
        }
    }
}

#[derive(Clone, Debug)]
struct LogPaths {
    server_log: PathBuf,
    performance_log: PathBuf,
}

#[derive(Default)]
struct ServerLogSinks {
    normal: Option<RotatingLogSink>,
    performance: Option<RotatingLogSink>,
}

impl ServerLogSinks {
    fn flush_all(&mut self) {
        if let Some(sink) = self.normal.as_mut() {
            sink.flush();
        }
        if let Some(sink) = self.performance.as_mut() {
            sink.flush();
        }
    }
}

struct ServerLogWorker {
    sender: mpsc::Sender<ServerLogCommand>,
}

#[derive(Default)]
struct ServerLogConfigureResult {
    normal_enabled: bool,
    performance_enabled: bool,
}

enum ServerLogCommand {
    Configure {
        paths: Option<LogPaths>,
        ack: mpsc::Sender<ServerLogConfigureResult>,
    },
    WriteLog(String),
    WritePerformance(String),
    Flush(mpsc::Sender<()>),
}

struct RotatingLogSink {
    path: PathBuf,
    writer: BufWriter<File>,
    bytes_written: u64,
    header: Option<&'static str>,
    last_flush: Instant,
}

impl RotatingLogSink {
    fn open(path: PathBuf, header: Option<&'static str>) -> std::io::Result<Self> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }

        rotate_existing_log_if_needed(&path)?;

        let bytes_written = path.metadata().map(|metadata| metadata.len()).unwrap_or(0);
        let needs_header = header.is_some() && bytes_written == 0;
        let file = OpenOptions::new().create(true).append(true).open(&path)?;
        let mut sink = Self {
            path,
            writer: BufWriter::new(file),
            bytes_written,
            header,
            last_flush: Instant::now(),
        };

        if needs_header {
            if let Some(header) = sink.header {
                sink.write_raw_line(header)?;
                sink.flush();
            }
        }

        Ok(sink)
    }

    fn write_line(&mut self, line: &str) -> std::io::Result<()> {
        let line_bytes = line.len() as u64 + 1;
        if self.bytes_written.saturating_add(line_bytes) > LOG_MAX_BYTES {
            self.rotate()?;
        }

        self.write_raw_line(line)?;
        if self.last_flush.elapsed() >= LOG_FLUSH_INTERVAL {
            self.flush();
        }

        Ok(())
    }

    fn write_raw_line(&mut self, line: &str) -> std::io::Result<()> {
        self.writer.write_all(line.as_bytes())?;
        self.writer.write_all(b"\n")?;
        self.bytes_written = self.bytes_written.saturating_add(line.len() as u64 + 1);
        Ok(())
    }

    fn rotate(&mut self) -> std::io::Result<()> {
        self.flush();
        let rotated_path = rotated_log_path(&self.path);
        match fs::remove_file(&rotated_path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }
        match fs::rename(&self.path, rotated_path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(error) => return Err(error),
        }

        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)?;
        self.writer = BufWriter::new(file);
        self.bytes_written = 0;
        self.last_flush = Instant::now();

        if let Some(header) = self.header {
            self.write_raw_line(header)?;
        }

        Ok(())
    }

    fn flush(&mut self) {
        let _ = self.writer.flush();
        self.last_flush = Instant::now();
    }
}

fn rotated_log_path(path: &std::path::Path) -> PathBuf {
    let Some(file_name) = path.file_name() else {
        return path.with_extension("1");
    };
    let mut rotated_file_name = file_name.to_os_string();
    rotated_file_name.push(".1");
    path.with_file_name(rotated_file_name)
}

fn rotate_existing_log_if_needed(path: &std::path::Path) -> std::io::Result<()> {
    let Ok(metadata) = path.metadata() else {
        return Ok(());
    };

    if metadata.len() <= LOG_MAX_BYTES {
        return Ok(());
    }

    let rotated_path = rotated_log_path(path);
    match fs::remove_file(&rotated_path) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error),
    }
    fs::rename(path, rotated_path)
}

fn server_log_worker() -> &'static ServerLogWorker {
    SERVER_LOG_WORKER.get_or_init(|| {
        let (sender, receiver) = mpsc::channel();
        std::thread::Builder::new()
            .name("azookey-server-log-writer".to_owned())
            .spawn(move || server_log_worker_loop(receiver))
            .expect("failed to spawn azookey server log writer");
        ServerLogWorker { sender }
    })
}

fn server_log_worker_loop(receiver: mpsc::Receiver<ServerLogCommand>) {
    let mut sinks = ServerLogSinks::default();

    loop {
        match receiver.recv_timeout(LOG_FLUSH_INTERVAL) {
            Ok(command) => handle_server_log_command(&mut sinks, command),
            Err(mpsc::RecvTimeoutError::Timeout) => sinks.flush_all(),
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                sinks.flush_all();
                break;
            }
        }
    }
}

fn handle_server_log_command(sinks: &mut ServerLogSinks, command: ServerLogCommand) {
    match command {
        ServerLogCommand::Configure { paths, ack } => {
            let result = configure_server_log_sinks(sinks, paths);
            let _ = ack.send(result);
        }
        ServerLogCommand::WriteLog(line) => {
            if let Some(sink) = sinks.normal.as_mut() {
                eprintln!("{line}");
                let _ = sink.write_line(&line);
            }
        }
        ServerLogCommand::WritePerformance(line) => {
            if let Some(sink) = sinks.performance.as_mut() {
                let _ = sink.write_line(&line);
            }
        }
        ServerLogCommand::Flush(ack) => {
            sinks.flush_all();
            let _ = ack.send(());
        }
    }
}

fn configure_server_log_sinks(
    sinks: &mut ServerLogSinks,
    paths: Option<LogPaths>,
) -> ServerLogConfigureResult {
    sinks.flush_all();
    *sinks = ServerLogSinks::default();

    let Some(paths) = paths else {
        return ServerLogConfigureResult::default();
    };

    sinks.normal = match RotatingLogSink::open(paths.server_log, None) {
        Ok(sink) => Some(sink),
        Err(error) => {
            eprintln!("Failed to open server log file: {error}");
            None
        }
    };
    sinks.performance =
        match RotatingLogSink::open(paths.performance_log, Some(SERVER_PERFORMANCE_LOG_HEADER)) {
            Ok(sink) => Some(sink),
            Err(error) => {
                eprintln!("Failed to open server performance log file: {error}");
                None
            }
        };

    ServerLogConfigureResult {
        normal_enabled: sinks.normal.is_some(),
        performance_enabled: sinks.performance.is_some(),
    }
}

fn send_server_log_command(command: ServerLogCommand) {
    if let Err(error) = server_log_worker().sender.send(command) {
        eprintln!("Failed to send server log command: {error}");
    }
}

fn should_log(level: ServerLogLevel) -> bool {
    if level == ServerLogLevel::Off {
        return false;
    }

    SERVER_LOG_ENABLED.load(Ordering::Relaxed)
        && level <= ServerLogLevel::from_u8(SERVER_LOG_LEVEL.load(Ordering::Relaxed))
}

fn should_log_performance() -> bool {
    SERVER_PERFORMANCE_LOG_ENABLED.load(Ordering::Relaxed)
        && ServerLogLevel::from_u8(SERVER_LOG_LEVEL.load(Ordering::Relaxed))
            >= ServerLogLevel::Debug
}

macro_rules! log_event_lazy {
    ($level:expr, $($arg:tt)*) => {{
        let level = $level;
        if should_log(level) {
            log_event(level, &format!($($arg)*));
        }
    }};
}

macro_rules! performance_event_lazy {
    ($request_id:expr, $operation:expr, $stage:expr, $elapsed_ms:expr, $($arg:tt)*) => {{
        if should_log_performance() {
            log_performance_event(
                $request_id,
                "rust",
                $operation,
                $stage,
                $elapsed_ms,
                &format!($($arg)*),
            );
        }
    }};
}

struct RawComposingText {
    text: String,
    cursor: i32,
}

struct ComposedText {
    hiragana: Option<String>,
    suggestions: Vec<Suggestion>,
}

#[derive(Debug, Clone)]
#[repr(C)]
struct FFICandidate {
    text: *mut c_char,
    subtext: *mut c_char,
    hiragana: *mut c_char,
    corresponding_count: c_int,
    candidate_id: u64,
}

unsafe extern "C" {
    fn Initialize(path: *const c_char, use_zenzai: bool);
    fn SetContext(context: *const c_char);
    fn SetConversionContinuation(reading: *const c_char, surface: *const c_char) -> bool;
    fn AppendText(input: *const c_char, cursorPtr: *mut c_int) -> *mut c_char;
    fn AppendTextDirect(input: *const c_char, cursorPtr: *mut c_int) -> *mut c_char;
    fn RemoveText(cursorPtr: *mut c_int) -> *mut c_char;
    fn MoveCursor(offset: c_int, cursorPtr: *mut c_int) -> *mut c_char;
    fn GetCursorPosition() -> c_int;
    fn GetRawInput() -> *mut c_char;
    fn AdjustClauseBoundary(
        currentInputCount: c_int,
        direction: c_int,
        expectedRawInput: *const c_char,
        appliedPtr: *mut c_int,
        adjustedInputCountPtr: *mut c_int,
        cursorOffsetPtr: *mut c_int,
    ) -> *mut c_char;
    fn ClearComposingTextSnapshots();
    fn PushComposingTextSnapshot(selectedCandidateId: u64);
    fn PopComposingTextSnapshot(selectedCandidateId: u64);
    fn PinLearningCandidate(candidateId: u64) -> bool;
    fn ShrinkText(offset: c_int) -> *mut c_char;
    fn ClearText();
    fn Warmup() -> bool;
    fn HasActiveComposition() -> bool;
    fn InferReconversionReadings(surface: *const c_char) -> *mut c_char;
    fn SelectJevCandidate(json: *const c_char) -> c_int;
    fn GetComposedText(lengthPtr: *mut c_int) -> *mut *mut FFICandidate;
    fn GetComposedTextForReconversion(lengthPtr: *mut c_int) -> *mut *mut FFICandidate;
    fn GetComposedTextForCursorPrefix(
        requiredInputCount: c_int,
        lengthPtr: *mut c_int,
    ) -> *mut *mut FFICandidate;
    fn FreeCString(ptr: *mut c_char);
    fn FreeCandidateList(ptr: *mut *mut FFICandidate, length: c_int);
    fn CommitLearningCandidate(candidateId: u64, commitKind: c_int) -> bool;
    fn CommitLearningCandidates(
        candidateIds: *const u64,
        commitKinds: *const c_int,
        count: c_int,
    ) -> c_int;
    fn ResetLearningMemory() -> bool;
    fn ForgetLearningCandidate(candidateId: u64) -> bool;
    fn GetLearningStats() -> *mut c_char;
    fn LoadConfig();
    fn SetRequestId(request_id: u64);
    fn SetServerLogCallbacks(
        log_enabled: extern "C" fn() -> bool,
        log_level_enabled: extern "C" fn(*const c_char) -> bool,
        performance_log_enabled: extern "C" fn() -> bool,
        write_log: extern "C" fn(*const c_char, *const c_char),
        write_performance_log: extern "C" fn(u64, *const c_char, *const c_char, u64, *const c_char),
        flush_log: extern "C" fn(),
        crash_trace_enabled: extern "C" fn() -> bool,
        write_crash_trace: extern "C" fn(
            *const c_char,
            *const c_char,
            *const c_char,
            *const c_char,
        ),
    );
}

struct OwnedFfiString {
    ptr: *mut c_char,
}

impl OwnedFfiString {
    unsafe fn from_raw(scope: &str, ptr: *mut c_char) -> Result<Self, String> {
        if ptr.is_null() {
            return Err(format!("[{scope}] Swift FFI returned null pointer"));
        }

        Ok(Self { ptr })
    }

    fn to_string_lossy(&self) -> String {
        unsafe { CStr::from_ptr(self.ptr as *const c_char) }
            .to_string_lossy()
            .into_owned()
    }
}

impl Drop for OwnedFfiString {
    fn drop(&mut self) {
        unsafe {
            FreeCString(self.ptr);
        }
    }
}

struct OwnedFfiCandidates {
    ptr: *mut *mut FFICandidate,
    length: c_int,
}

impl OwnedFfiCandidates {
    unsafe fn from_raw(
        scope: &str,
        ptr: *mut *mut FFICandidate,
        length: c_int,
    ) -> Result<Self, String> {
        if length < 0 {
            if !ptr.is_null() {
                FreeCandidateList(ptr, 0);
            }
            return Err(format!("[{scope}] invalid negative length: {length}"));
        }

        if length > 0 && ptr.is_null() {
            return Err(format!(
                "[{scope}] null candidate list pointer (length={length})"
            ));
        }

        Ok(Self { ptr, length })
    }

    fn len(&self) -> usize {
        self.length as usize
    }

    unsafe fn candidate_ptr(&self, index: usize) -> *mut FFICandidate {
        *self.ptr.add(index)
    }

    fn free_with_performance(mut self, request_id: u64, operation: &str) {
        let ptr = self.ptr;
        let length = self.length;
        self.ptr = std::ptr::null_mut();
        self.length = 0;

        let free_start = Instant::now();
        unsafe {
            FreeCandidateList(ptr, length);
        }
        performance_event_lazy!(
            request_id,
            operation,
            "free_candidate_list",
            elapsed_ms(free_start),
            "candidate_count={}",
            length.max(0)
        );
    }
}

impl Drop for OwnedFfiCandidates {
    fn drop(&mut self) {
        if !self.ptr.is_null() {
            unsafe {
                FreeCandidateList(self.ptr, self.length);
            }
        }
    }
}

fn next_request_id() -> u64 {
    SERVER_GENERATED_REQUEST_ID_PREFIX | REQUEST_SEQUENCE.fetch_add(1, Ordering::Relaxed)
}

fn request_id_or_next(request_id: u64) -> u64 {
    if request_id == 0 {
        next_request_id()
    } else {
        request_id
    }
}

fn server_session_id() -> u64 {
    *SERVER_SESSION_ID.get_or_init(|| {
        let session_id = (u64::from(std::process::id()) << 32) ^ (now_timestamp_millis() as u64);
        session_id.max(1)
    })
}

fn set_request_id(request_id: u64) {
    unsafe {
        SetRequestId(request_id);
    }
}

fn register_server_log_callbacks() {
    unsafe {
        SetServerLogCallbacks(
            AzookeyServerLogEnabled,
            AzookeyServerLogLevelEnabled,
            AzookeyServerPerformanceLogEnabled,
            AzookeyServerLogFromSwift,
            AzookeyServerPerformanceLogFromSwift,
            AzookeyServerLogFlushFromSwift,
            AzookeyServerCrashTraceEnabled,
            AzookeyServerCrashTraceFromSwift,
        );
    }
}

fn elapsed_ms(start: Instant) -> u128 {
    start.elapsed().as_millis()
}

fn performance_instant(enabled: bool) -> Option<Instant> {
    enabled.then(Instant::now)
}

fn add_elapsed_ms(total: &mut u128, start: Option<Instant>) {
    if let Some(start) = start {
        *total += elapsed_ms(start);
    }
}

fn monotonic_millis() -> u64 {
    let elapsed = MONOTONIC_START
        .get_or_init(Instant::now)
        .elapsed()
        .as_millis();
    u64::try_from(elapsed).unwrap_or(u64::MAX)
}

fn record_input_request_finished() {
    LAST_INPUT_REQUEST_FINISHED_MS.store(monotonic_millis().max(1), Ordering::Release);
}

struct ServerRequestGuard {
    is_input_request: bool,
}

impl ServerRequestGuard {
    fn begin(is_input_request: bool) -> Self {
        SERVER_REQUESTS_IN_FLIGHT.fetch_add(1, Ordering::AcqRel);
        Self { is_input_request }
    }
}

impl Drop for ServerRequestGuard {
    fn drop(&mut self) {
        if self.is_input_request {
            record_input_request_finished();
        }
        SERVER_REQUESTS_IN_FLIGHT.fetch_sub(1, Ordering::AcqRel);
    }
}

enum WarmupSkipReason {
    ActiveComposition,
    RequestInFlight { in_flight: u64 },
    RecentInput { elapsed_ms: u64 },
}

impl WarmupSkipReason {
    fn details(&self) -> String {
        match self {
            Self::ActiveComposition => "reason=active_composition".to_owned(),
            Self::RequestInFlight { in_flight } => {
                format!("reason=request_in_flight;in_flight={in_flight}")
            }
            Self::RecentInput { elapsed_ms } => format!(
                "reason=recent_input;elapsed_ms={elapsed_ms};skip_ms={WARMUP_RECENT_INPUT_SKIP_MS}"
            ),
        }
    }
}

fn warmup_skip_reason() -> Option<WarmupSkipReason> {
    if has_active_composition() {
        return Some(WarmupSkipReason::ActiveComposition);
    }

    let in_flight = SERVER_REQUESTS_IN_FLIGHT.load(Ordering::Acquire);
    if in_flight > 0 {
        return Some(WarmupSkipReason::RequestInFlight { in_flight });
    }

    let last_input_finished_ms = LAST_INPUT_REQUEST_FINISHED_MS.load(Ordering::Acquire);
    if last_input_finished_ms > 0 {
        let elapsed_ms = monotonic_millis().saturating_sub(last_input_finished_ms);
        if elapsed_ms < WARMUP_RECENT_INPUT_SKIP_MS {
            return Some(WarmupSkipReason::RecentInput { elapsed_ms });
        }
    }

    None
}

fn now_timestamp_millis() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or_default()
}

fn resolve_log_path(file_name: &str) -> PathBuf {
    resolve_log_path_from_roots(
        std::env::var_os("APPDATA").as_deref(),
        &std::env::temp_dir(),
        file_name,
    )
}

fn resolve_log_path_from_roots(
    app_data: Option<&OsStr>,
    temporary_directory: &Path,
    file_name: &str,
) -> PathBuf {
    let root = app_data
        .filter(|path| !path.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| temporary_directory.to_path_buf());
    root.join("Azookey").join("logs").join(file_name)
}

fn configure_server_logging(config: &AppConfig) -> Option<LogPaths> {
    JEV_CONVERSION_ENABLED.store(config.general.jev_conversion, Ordering::Relaxed);
    let level = server_log_level_from_str(&config.debug.server_log_level);
    SERVER_LOG_LEVEL.store(level as u8, Ordering::Relaxed);
    SERVER_CRASH_TRACE_ENABLED.store(config.debug.server_crash_trace_enabled, Ordering::Relaxed);

    let paths = config.debug.server_log_enabled.then(|| LogPaths {
        server_log: resolve_log_path(SERVER_LOG_FILE_NAME),
        performance_log: resolve_log_path(SERVER_PERFORMANCE_LOG_FILE_NAME),
    });
    let (ack_tx, ack_rx) = mpsc::channel();
    send_server_log_command(ServerLogCommand::Configure {
        paths: paths.clone(),
        ack: ack_tx,
    });

    match ack_rx.recv_timeout(LOG_FLUSH_ACK_TIMEOUT) {
        Ok(result) => {
            SERVER_LOG_ENABLED.store(result.normal_enabled, Ordering::Relaxed);
            SERVER_PERFORMANCE_LOG_ENABLED.store(result.performance_enabled, Ordering::Relaxed);
        }
        Err(error) => {
            SERVER_LOG_ENABLED.store(false, Ordering::Relaxed);
            SERVER_PERFORMANCE_LOG_ENABLED.store(false, Ordering::Relaxed);
            eprintln!("Timed out configuring server logging: {error}");
        }
    }

    paths
}

fn reload_server_logging_from_settings() -> Option<LogPaths> {
    let config = AppConfig::read().unwrap_or_default();
    reload_ai_assist_from_settings(&config);
    configure_server_logging(&config)
}

fn flush_server_logs() {
    let (ack_tx, ack_rx) = mpsc::channel();
    send_server_log_command(ServerLogCommand::Flush(ack_tx));
    if let Err(error) = ack_rx.recv_timeout(LOG_FLUSH_ACK_TIMEOUT) {
        eprintln!("Timed out flushing server logs: {error}");
    }
}

fn log_event(level: ServerLogLevel, message: &str) {
    log_event_with_component(None, level, message);
}

fn log_event_with_component(component: Option<&str>, level: ServerLogLevel, message: &str) {
    if !should_log(level) {
        return;
    }

    let level_label = if let Some(component) = component {
        format!("{component}/{}", level.as_str())
    } else {
        level.as_str().to_owned()
    };
    let line = format!("[{}] [{}] {}", now_timestamp_millis(), level_label, message);

    send_server_log_command(ServerLogCommand::WriteLog(line));
}

fn crash_trace_enabled() -> bool {
    SERVER_CRASH_TRACE_ENABLED.load(Ordering::Relaxed)
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

fn compact_trace_for_log(trace: &str) -> String {
    let compact = trace.split_whitespace().collect::<Vec<_>>().join(" ");
    const MAX_TRACE_LOG_CHARS: usize = 512;
    if compact.chars().count() <= MAX_TRACE_LOG_CHARS {
        return compact;
    }
    compact.chars().take(MAX_TRACE_LOG_CHARS).collect()
}

fn crash_trace_is_in_progress(trace: &str) -> bool {
    trace.contains("\"state\":\"begin\"") || trace.contains("\"state\": \"begin\"")
}

fn preserve_crash_trace_if_incomplete(
    source_file_name: &str,
    previous_file_name: &str,
) -> Option<String> {
    if !crash_trace_enabled() {
        return None;
    }

    let source_path = resolve_log_path(source_file_name);
    let Ok(trace) = fs::read_to_string(&source_path) else {
        return None;
    };
    if !crash_trace_is_in_progress(&trace) {
        return None;
    }

    let previous_path = resolve_log_path(previous_file_name);
    if let Some(parent) = previous_path.parent() {
        if let Err(error) = fs::create_dir_all(parent) {
            eprintln!("Failed to create previous crash trace directory: {error}");
            return Some(trace);
        }
    }
    if let Err(error) = fs::write(&previous_path, trace.as_bytes()) {
        eprintln!("Failed to preserve previous crash trace: {error}");
    }
    Some(trace)
}

fn log_previous_crash_trace(label: &str, trace: &str) {
    log_event(
        ServerLogLevel::Warn,
        &format!(
            "{label} was still in progress: {}",
            compact_trace_for_log(trace)
        ),
    );
}

fn log_and_consume_previous_crash_trace_if_incomplete(file_name: &str, label: &str) {
    if !crash_trace_enabled() {
        return;
    }

    let path = resolve_log_path(file_name);
    let Ok(trace) = fs::read_to_string(&path) else {
        return;
    };
    if crash_trace_is_in_progress(&trace) {
        let should_consume = should_log(ServerLogLevel::Warn);
        log_previous_crash_trace(label, &trace);
        if should_consume {
            flush_server_logs();
            match fs::remove_file(&path) {
                Ok(()) => {}
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
                Err(error) => {
                    log_event(
                        ServerLogLevel::Warn,
                        &format!("failed to clear consumed {label}: {error}"),
                    );
                    flush_server_logs();
                }
            }
        }
    }
}

fn write_crash_trace_file(
    file_name: &str,
    component: &str,
    operation: &str,
    stage: &str,
    state: &str,
    details: &str,
) {
    if !crash_trace_enabled() {
        return;
    }

    let path = resolve_log_path(file_name);
    if let Some(parent) = path.parent() {
        if let Err(error) = fs::create_dir_all(parent) {
            eprintln!("Failed to create crash trace directory: {error}");
            return;
        }
    }

    let trace = format!(
        concat!(
            "{{\n",
            "  \"timestamp_ms\": {},\n",
            "  \"process_id\": {},\n",
            "  \"component\": \"{}\",\n",
            "  \"operation\": \"{}\",\n",
            "  \"stage\": \"{}\",\n",
            "  \"state\": \"{}\",\n",
            "  \"details\": \"{}\"\n",
            "}}\n"
        ),
        now_timestamp_millis(),
        std::process::id(),
        json_escape(component),
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
        Err(error) => eprintln!("Failed to write crash trace: {error}"),
    }
}

fn write_server_crash_trace(
    component: &str,
    operation: &str,
    stage: &str,
    state: &str,
    details: &str,
) {
    write_crash_trace_file(
        SERVER_CRASH_TRACE_FILE_NAME,
        component,
        operation,
        stage,
        state,
        details,
    );
}

fn sanitize_performance_field(value: &str) -> String {
    value
        .chars()
        .map(|ch| match ch {
            '\t' | '\r' | '\n' => ' ',
            _ => ch,
        })
        .collect()
}

fn log_performance_event(
    request_id: u64,
    component: &str,
    operation: &str,
    stage: &str,
    elapsed_ms: u128,
    details: &str,
) {
    if !should_log_performance() {
        return;
    }

    let line = format!(
        "{}\t{}\t{}\t{}\t{}\t{}\t{}",
        now_timestamp_millis(),
        request_id,
        sanitize_performance_field(component),
        sanitize_performance_field(operation),
        sanitize_performance_field(stage),
        elapsed_ms,
        sanitize_performance_field(details),
    );

    send_server_log_command(ServerLogCommand::WritePerformance(line));
}

fn optional_cstr_lossy(ptr: *const c_char) -> String {
    if ptr.is_null() {
        return String::new();
    }

    unsafe { CStr::from_ptr(ptr) }
        .to_string_lossy()
        .into_owned()
}

fn server_log_level_from_str(level: &str) -> ServerLogLevel {
    match level.to_ascii_uppercase().as_str() {
        "OFF" => ServerLogLevel::Off,
        "ERROR" => ServerLogLevel::Error,
        "WARN" | "WARNING" => ServerLogLevel::Warn,
        "INFO" => ServerLogLevel::Info,
        "DEBUG" => ServerLogLevel::Debug,
        _ => ServerLogLevel::Warn,
    }
}

#[no_mangle]
pub extern "C" fn AzookeyServerLogEnabled() -> bool {
    SERVER_LOG_ENABLED.load(Ordering::Relaxed)
}

#[no_mangle]
pub extern "C" fn AzookeyServerLogLevelEnabled(level: *const c_char) -> bool {
    should_log(server_log_level_from_str(&optional_cstr_lossy(level)))
}

#[no_mangle]
pub extern "C" fn AzookeyServerPerformanceLogEnabled() -> bool {
    should_log_performance()
}

#[no_mangle]
pub extern "C" fn AzookeyServerLogFromSwift(level: *const c_char, message: *const c_char) {
    let level = server_log_level_from_str(&optional_cstr_lossy(level));
    if !should_log(level) {
        return;
    }

    let message = optional_cstr_lossy(message);
    log_event_with_component(Some("SWIFT"), level, &message);
}

#[no_mangle]
pub extern "C" fn AzookeyServerPerformanceLogFromSwift(
    request_id: u64,
    operation: *const c_char,
    stage: *const c_char,
    elapsed_ms: u64,
    details: *const c_char,
) {
    if !should_log_performance() {
        return;
    }

    log_performance_event(
        request_id,
        "swift",
        &optional_cstr_lossy(operation),
        &optional_cstr_lossy(stage),
        elapsed_ms as u128,
        &optional_cstr_lossy(details),
    );
}

#[no_mangle]
pub extern "C" fn AzookeyServerLogFlushFromSwift() {
    flush_server_logs();
}

#[no_mangle]
pub extern "C" fn AzookeyServerCrashTraceEnabled() -> bool {
    crash_trace_enabled()
}

#[no_mangle]
pub extern "C" fn AzookeyServerCrashTraceFromSwift(
    operation: *const c_char,
    stage: *const c_char,
    state: *const c_char,
    details: *const c_char,
) {
    write_server_crash_trace(
        "swift",
        &optional_cstr_lossy(operation),
        &optional_cstr_lossy(stage),
        &optional_cstr_lossy(state),
        &optional_cstr_lossy(details),
    );
}

fn install_panic_hook() {
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |panic_info| {
        let payload = if let Some(message) = panic_info.payload().downcast_ref::<&str>() {
            (*message).to_owned()
        } else if let Some(message) = panic_info.payload().downcast_ref::<String>() {
            message.clone()
        } else {
            "<non-string panic payload>".to_owned()
        };

        let location = panic_info
            .location()
            .map(|location| {
                format!(
                    "{}:{}:{}",
                    location.file(),
                    location.line(),
                    location.column()
                )
            })
            .unwrap_or_else(|| "<unknown>".to_owned());

        let backtrace = Backtrace::force_capture();
        write_server_crash_trace(
            "rust",
            "panic",
            "panic_hook",
            "error",
            &format!("payload={payload};location={location}"),
        );
        log_event(
            ServerLogLevel::Error,
            &format!("payload={payload}; location={location}; backtrace={backtrace}"),
        );
        flush_server_logs();

        default_hook(panic_info);
    }));
}

fn cstring_from_input(scope: &str, value: &str) -> Result<CString, String> {
    CString::new(value).map_err(|error| format!("[{scope}] CString::new failed: {error}"))
}

fn ffi_text_result(scope: &str, result: *mut c_char) -> Result<String, String> {
    let result = unsafe { OwnedFfiString::from_raw(scope, result)? };
    Ok(result.to_string_lossy())
}

fn status_from_error(scope: &str, error: String) -> Status {
    log_event(ServerLogLevel::Error, &error);
    Status::internal(format!("{scope} failed"))
}

#[allow(clippy::result_large_err)]
fn validate_shrink_offset(offset: i32) -> Result<i32, Status> {
    if offset < 0 {
        Err(Status::invalid_argument(
            "shrink_text offset must be non-negative",
        ))
    } else {
        Ok(offset)
    }
}

#[allow(clippy::result_large_err)]
fn validate_reconversion_surface(surface: &str) -> Result<usize, Status> {
    let surface_count = surface.chars().count();
    if surface_count == 0 || surface_count > MAX_RECONVERSION_SURFACE_COUNT {
        Err(Status::invalid_argument(
            "reconversion surface must contain between 1 and 128 characters",
        ))
    } else {
        Ok(surface_count)
    }
}

fn reconversion_surface_rank(suggestions: &[Suggestion], surface: &str) -> Option<usize> {
    suggestions
        .iter()
        .position(|suggestion| suggestion.text == surface)
}

fn merge_reconversion_suggestions(
    surface: &str,
    hiragana: &str,
    suggestion_groups: &[Vec<Suggestion>],
) -> Vec<Suggestion> {
    let corresponding_count = i32::try_from(hiragana.chars().count()).unwrap_or(i32::MAX);
    let mut merged = Vec::with_capacity(MAX_RECONVERSION_SUGGESTIONS);
    let mut seen_texts = HashSet::with_capacity(MAX_RECONVERSION_SUGGESTIONS);
    seen_texts.insert(surface.to_string());
    merged.push(Suggestion {
        text: surface.to_string(),
        subtext: String::new(),
        corresponding_count,
        candidate_id: 0,
    });

    let max_group_len = suggestion_groups.iter().map(Vec::len).max().unwrap_or(0);
    'rank: for rank in 0..max_group_len {
        for group in suggestion_groups {
            let Some(suggestion) = group.get(rank) else {
                continue;
            };
            if !seen_texts.insert(suggestion.text.clone()) {
                continue;
            }
            let mut suggestion = suggestion.clone();
            suggestion.corresponding_count = corresponding_count;
            // Candidate identifiers are scoped to the converter's current state. Candidate
            // groups from alternate readings are no longer valid for learning after the
            // chosen reading is restored, so reconversion candidates are deliberately neutral.
            suggestion.candidate_id = 0;
            merged.push(suggestion);
            if merged.len() == MAX_RECONVERSION_SUGGESTIONS {
                break 'rank;
            }
        }
    }
    merged
}

#[inline]
fn retained_prepared_snapshot_count(
    request_succeeded: bool,
    leave_at_last: bool,
    completed: bool,
    advance_count: usize,
) -> usize {
    if request_succeeded && leave_at_last && completed {
        advance_count
    } else {
        0
    }
}

fn add_text(input: &str) -> Result<RawComposingText, String> {
    let input = cstring_from_input("AppendText.input", input)?;

    unsafe {
        let mut cursor: c_int = 0;
        let result = AppendText(input.as_ptr(), &mut cursor);
        let text = ffi_text_result("AppendText", result)?;

        Ok(RawComposingText { text, cursor })
    }
}

fn add_text_direct(input: &str) -> Result<RawComposingText, String> {
    let input = cstring_from_input("AppendTextDirect.input", input)?;

    unsafe {
        let mut cursor: c_int = 0;
        let result = AppendTextDirect(input.as_ptr(), &mut cursor);
        let text = ffi_text_result("AppendTextDirect", result)?;

        Ok(RawComposingText { text, cursor })
    }
}

fn infer_reconversion_readings(surface: &str) -> Result<Vec<String>, String> {
    let surface = cstring_from_input("InferReconversionReadings.surface", surface)?;
    let result = unsafe { InferReconversionReadings(surface.as_ptr()) };
    if result.is_null() {
        return Ok(Vec::new());
    }
    let result = unsafe { OwnedFfiString::from_raw("InferReconversionReadings", result)? };
    decode_reconversion_readings(&result.to_string_lossy())
}

fn decode_reconversion_readings(encoded: &str) -> Result<Vec<String>, String> {
    let readings: Vec<String> = serde_json::from_str(encoded)
        .map_err(|error| format!("InferReconversionReadings returned invalid JSON: {error}"))?;
    if readings.iter().any(String::is_empty) {
        return Err("InferReconversionReadings returned an empty reading".to_string());
    }
    Ok(readings)
}

fn move_cursor(offset: i32) -> Result<RawComposingText, String> {
    unsafe {
        let mut cursor: c_int = 0;
        let result = MoveCursor(offset, &mut cursor);
        let text = ffi_text_result("MoveCursor", result)?;

        Ok(RawComposingText { text, cursor })
    }
}

fn get_cursor_position() -> i32 {
    unsafe { GetCursorPosition() }
}

fn get_raw_input() -> Result<String, String> {
    unsafe { ffi_text_result("GetRawInput", GetRawInput()) }
}

struct RawClauseBoundaryAdjustment {
    text: String,
    applied: bool,
    adjusted_input_count: i32,
    cursor_offset: i32,
}

fn hiragana_boundary_fallback(
    hiragana: &str,
    cursor: i32,
    corresponding_count: i32,
) -> Option<ComposedText> {
    let cursor = usize::try_from(cursor).ok()?;
    let mut prefix = String::new();
    let mut suffix = String::new();
    for (index, character) in hiragana.chars().enumerate() {
        if index < cursor {
            prefix.push(character);
        } else {
            suffix.push(character);
        }
    }
    if prefix.is_empty() || cursor > hiragana.chars().count() {
        return None;
    }

    Some(ComposedText {
        hiragana: Some(hiragana.to_string()),
        suggestions: vec![Suggestion {
            text: prefix,
            subtext: suffix,
            corresponding_count,
            candidate_id: 0,
        }],
    })
}

fn adjust_clause_boundary(
    current_input_count: i32,
    direction: i32,
    expected_raw_input: &str,
) -> Result<RawClauseBoundaryAdjustment, String> {
    let expected_raw_input =
        cstring_from_input("AdjustClauseBoundary.expectedRawInput", expected_raw_input)?;
    unsafe {
        let mut applied: c_int = 0;
        let mut adjusted_input_count: c_int = current_input_count;
        let mut cursor_offset: c_int = 0;
        let result = AdjustClauseBoundary(
            current_input_count,
            direction,
            expected_raw_input.as_ptr(),
            &mut applied,
            &mut adjusted_input_count,
            &mut cursor_offset,
        );
        let text = ffi_text_result("AdjustClauseBoundary", result)?;

        Ok(RawClauseBoundaryAdjustment {
            text,
            applied: applied != 0,
            adjusted_input_count,
            cursor_offset,
        })
    }
}

fn rollback_clause_boundary(
    adjusted_input_count: i32,
    original_input_count: i32,
    direction: i32,
    expected_raw_input: &str,
) -> Result<(), String> {
    let rollback = adjust_clause_boundary(adjusted_input_count, -direction, expected_raw_input)?;
    if rollback.applied && rollback.adjusted_input_count == original_input_count {
        Ok(())
    } else {
        Err(format!(
            "AdjustClauseBoundary rollback failed: applied={} adjusted_input_count={} expected_input_count={original_input_count}",
            rollback.applied, rollback.adjusted_input_count
        ))
    }
}

fn remove_text() -> Result<RawComposingText, String> {
    unsafe {
        let mut cursor: c_int = 0;
        let result = RemoveText(&mut cursor);
        let text = ffi_text_result("RemoveText", result)?;

        Ok(RawComposingText { text, cursor })
    }
}

fn clear_text() {
    unsafe {
        ClearText();
    }
}

fn swift_commit_learning_candidate(candidate_id: u64, commit_kind: i32) -> bool {
    unsafe { CommitLearningCandidate(candidate_id, commit_kind as c_int) }
}

fn swift_commit_learning_candidates(commits: &[shared::proto::LearningCandidateCommit]) -> usize {
    let Ok(count) = c_int::try_from(commits.len()) else {
        return 0;
    };
    let candidate_ids = commits
        .iter()
        .map(|commit| commit.candidate_id)
        .collect::<Vec<_>>();
    let commit_kinds = commits
        .iter()
        .map(|commit| commit.commit_kind as c_int)
        .collect::<Vec<_>>();
    let committed =
        unsafe { CommitLearningCandidates(candidate_ids.as_ptr(), commit_kinds.as_ptr(), count) };
    usize::try_from(committed).unwrap_or_default()
}

fn swift_reset_learning_memory() -> bool {
    unsafe { ResetLearningMemory() }
}

fn swift_forget_learning_candidate(candidate_id: u64) -> bool {
    unsafe { ForgetLearningCandidate(candidate_id) }
}

/// Reads the engine's learning counters. The JSON is owned by the engine and
/// must be released with `FreeCString`.
fn swift_get_learning_stats() -> Option<shared::proto::LearningStatsResponse> {
    let pointer = unsafe { GetLearningStats() };
    if pointer.is_null() {
        return None;
    }
    let json = unsafe { std::ffi::CStr::from_ptr(pointer) }
        .to_string_lossy()
        .into_owned();
    unsafe { FreeCString(pointer) };
    let value: serde_json::Value = serde_json::from_str(&json).ok()?;
    Some(shared::proto::LearningStatsResponse {
        server_session_id: server_session_id(),
        selection_count: value["selectionCount"].as_u64().unwrap_or(0) as u32,
        history_present: value["historyPresent"].as_bool().unwrap_or(false),
        history_paused: value["historyPaused"].as_bool().unwrap_or(false),
        learning_mode: value["learningMode"]
            .as_str()
            .unwrap_or_default()
            .to_string(),
        typo_count: ai_assist::learning::learned_typo_count() as u32,
    })
}

/// AI アシスト候補の確定通知を処理する。
///
/// 「💡 もしかして」候補 (candidate_id 999901) が確定した場合は、生成時に
/// 記録した誤字読みと正解表記の組を自己学習ストアへ記録する。変換エンジン
/// の学習キャッシュには存在しない id なので、エンジンへは転送しない。
fn handle_ai_assist_learning_commit(candidate_id: u64, surface: &str) -> bool {
    if candidate_id == shared::ai_candidate::PREDICTIVE {
        // Predictions are not dictionary candidates and have no learning store.
        return false;
    }
    if candidate_id != shared::ai_candidate::TYPO_CORRECTION {
        return false;
    }
    let Some(candidate) = ai_assist::take_typo_candidate(surface) else {
        log_event(
            ServerLogLevel::Warn,
            &format!(
                "[ai_assist_learning] no matching typo candidate surface_len={}",
                surface.chars().count()
            ),
        );
        return false;
    };

    let recorded =
        ai_assist::record_learned_typo(&candidate.token_reading, &candidate.token_surface);
    log_event(
        ServerLogLevel::Info,
        &format!(
            "[ai_assist_learning] recorded typo correction reading_len={} surface_len={} recorded={recorded}",
            candidate.token_reading.chars().count(),
            candidate.token_surface.chars().count()
        ),
    );
    recorded
}

fn warmup() -> bool {
    unsafe { Warmup() }
}

fn query_active_composition_state() -> bool {
    unsafe { HasActiveComposition() }
}

fn has_active_composition() -> bool {
    HAS_ACTIVE_COMPOSITION.load(Ordering::Relaxed)
}

fn update_active_composition_state(text: &str) {
    HAS_ACTIVE_COMPOSITION.store(!text.is_empty(), Ordering::Relaxed);
}

fn get_composed_text(
    use_cursor_prefix: bool,
    required_cursor_prefix_input_count: Option<i32>,
    request_id: u64,
) -> Result<ComposedText, String> {
    get_composed_text_from_ffi(
        use_cursor_prefix,
        required_cursor_prefix_input_count,
        false,
        request_id,
    )
}

fn get_reconversion_composed_text(request_id: u64) -> Result<ComposedText, String> {
    get_composed_text_from_ffi(false, None, true, request_id)
}

fn get_composed_text_from_ffi(
    use_cursor_prefix: bool,
    required_cursor_prefix_input_count: Option<i32>,
    reconversion: bool,
    request_id: u64,
) -> Result<ComposedText, String> {
    let mut length: c_int = 0;
    let operation = if reconversion {
        "get_composed_text_for_reconversion"
    } else if use_cursor_prefix {
        "get_composed_text_for_cursor_prefix"
    } else {
        "get_composed_text"
    };
    let ffi_call_start = Instant::now();
    let result = unsafe {
        if reconversion {
            GetComposedTextForReconversion(&mut length)
        } else if use_cursor_prefix {
            GetComposedTextForCursorPrefix(
                required_cursor_prefix_input_count.unwrap_or(-1),
                &mut length,
            )
        } else {
            GetComposedText(&mut length)
        }
    };
    let call_name = if reconversion {
        "GetComposedTextForReconversion"
    } else if use_cursor_prefix {
        "GetComposedTextForCursorPrefix"
    } else {
        "GetComposedText"
    };
    let candidates = unsafe { OwnedFfiCandidates::from_raw(call_name, result, length)? };
    let length = candidates.len();
    performance_event_lazy!(
        request_id,
        operation,
        "ffi_call",
        elapsed_ms(ffi_call_start),
        "candidate_count={length};use_cursor_prefix={use_cursor_prefix};reconversion={reconversion}"
    );

    let mut suggestions = Vec::with_capacity(length);
    let mut hiragana = None;
    let mut seen_texts = HashSet::with_capacity(length);
    let performance_enabled = should_log_performance();
    let mut cstr_decode_ms = 0;
    let mut dedup_ms = 0;
    let mut duplicate_count = 0usize;
    log_event_lazy!(
        ServerLogLevel::Debug,
        "[{call_name}] candidate_count={length}"
    );

    for index in 0..length {
        let candidate_ptr = unsafe { candidates.candidate_ptr(index) };
        if candidate_ptr.is_null() {
            log_event(
                ServerLogLevel::Warn,
                &format!("[{call_name}] candidate[{index}] is null and skipped"),
            );
            continue;
        }

        let candidate = unsafe { (*candidate_ptr).clone() };
        if candidate.text.is_null() || candidate.subtext.is_null() {
            log_event(
                ServerLogLevel::Warn,
                &format!(
                    "[{call_name}] candidate[{index}] has null text/subtext pointer and was skipped"
                ),
            );
            continue;
        }

        if hiragana.is_none() && !candidate.hiragana.is_null() {
            let decode_start = performance_instant(performance_enabled);
            hiragana = Some(
                unsafe { CStr::from_ptr(candidate.hiragana) }
                    .to_string_lossy()
                    .into_owned(),
            );
            add_elapsed_ms(&mut cstr_decode_ms, decode_start);
        }

        let text_decode_start = performance_instant(performance_enabled);
        let text = unsafe { CStr::from_ptr(candidate.text) }
            .to_string_lossy()
            .into_owned();
        add_elapsed_ms(&mut cstr_decode_ms, text_decode_start);

        let dedup_start = performance_instant(performance_enabled);
        if !seen_texts.insert(text.clone()) {
            duplicate_count += 1;
            add_elapsed_ms(&mut dedup_ms, dedup_start);
            continue;
        }
        add_elapsed_ms(&mut dedup_ms, dedup_start);

        let subtext_decode_start = performance_instant(performance_enabled);
        let subtext = unsafe { CStr::from_ptr(candidate.subtext) }
            .to_string_lossy()
            .into_owned();
        add_elapsed_ms(&mut cstr_decode_ms, subtext_decode_start);
        let corresponding_count = candidate.corresponding_count;

        let suggestion = Suggestion {
            text,
            subtext,
            corresponding_count,
            candidate_id: candidate.candidate_id,
        };

        suggestions.push(suggestion);
    }
    performance_event_lazy!(
        request_id,
        operation,
        "cstr_decode",
        cstr_decode_ms,
        "candidate_count={length};unique_candidate_count={};duplicate_count={duplicate_count}",
        suggestions.len()
    );
    performance_event_lazy!(
        request_id,
        operation,
        "dedup",
        dedup_ms,
        "candidate_count={length};unique_candidate_count={};duplicate_count={duplicate_count}",
        suggestions.len()
    );
    candidates.free_with_performance(request_id, operation);
    if !use_cursor_prefix && !reconversion {
        promote_full_composition_top_candidate(&mut suggestions, hiragana.as_deref());
    }

    Ok(ComposedText {
        hiragana,
        suggestions,
    })
}

fn promote_full_composition_top_candidate(suggestions: &mut [Suggestion], hiragana: Option<&str>) {
    let Some(first) = suggestions.first() else {
        return;
    };
    if first.subtext.is_empty() {
        return;
    }
    let first_corresponding_count = first.corresponding_count;
    if let Some(full_index) = suggestions
        .iter()
        .enumerate()
        .skip(1)
        .find_map(|(index, cand)| {
            let is_raw_hiragana = hiragana.is_some_and(|reading| cand.text == reading);
            (cand.subtext.is_empty()
                && cand.corresponding_count >= first_corresponding_count
                && !is_raw_hiragana)
                .then_some(index)
        })
    {
        suggestions[..=full_index].rotate_right(1);
    }
}

fn shrink_text(offset: i32) -> Result<RawComposingText, String> {
    clear_conversion_continuation();
    unsafe {
        let result = ShrinkText(offset);
        let text = ffi_text_result("ShrinkText", result)?;

        Ok(RawComposingText { text, cursor: 0 })
    }
}

struct CompositionSnapshotRollback {
    armed: bool,
}

impl CompositionSnapshotRollback {
    fn push(selected_candidate_id: u64) -> Self {
        unsafe {
            PushComposingTextSnapshot(selected_candidate_id);
        }
        Self { armed: true }
    }

    fn commit(mut self) {
        self.armed = false;
    }
}

impl Drop for CompositionSnapshotRollback {
    fn drop(&mut self) {
        if self.armed {
            unsafe {
                PopComposingTextSnapshot(0);
            }
        }
    }
}

fn valid_jev_request(request: &shared::proto::RerankCandidatesRequest) -> bool {
    request.allow_remote
        && !request.reading.is_empty()
        && request.reading.chars().count() <= 256
        && request.context.chars().count() <= 2048
        && (2..=32).contains(&request.candidates.len())
        && request
            .candidates
            .iter()
            .all(|text| !text.is_empty() && text.chars().count() <= 512)
}

#[derive(Debug, Clone, Default)]
pub struct MyAzookeyService {
    mutation_lock: Arc<tokio::sync::Mutex<CompositionOwner>>,
}

type FfiTask = Box<dyn FnOnce() + Send + 'static>;

enum FfiMessage {
    Task(FfiTask),
    Shutdown,
}

struct FfiExecutor {
    sender: std::sync::mpsc::Sender<FfiMessage>,
}

impl FfiExecutor {
    fn install_main() -> std::sync::mpsc::Receiver<FfiMessage> {
        let (sender, receiver) = std::sync::mpsc::channel::<FfiMessage>();
        assert!(
            FFI_EXECUTOR.set(Self { sender }).is_ok(),
            "main FFI executor must be initialized before FFI work is queued"
        );
        receiver
    }

    fn spawn() -> Self {
        let (sender, receiver) = std::sync::mpsc::channel::<FfiMessage>();
        std::thread::Builder::new()
            .name("azookey-ffi".to_owned())
            .spawn(move || run_ffi_loop(receiver))
            .expect("failed to spawn azookey FFI worker");
        Self { sender }
    }
}

fn run_ffi_loop(receiver: std::sync::mpsc::Receiver<FfiMessage>) {
    while let Ok(message) = receiver.recv() {
        match message {
            FfiMessage::Task(task) => task(),
            FfiMessage::Shutdown => break,
        }
    }
}

fn shutdown_main_ffi() {
    if let Some(executor) = FFI_EXECUTOR.get() {
        let _ = executor.sender.send(FfiMessage::Shutdown);
    }
}

static FFI_EXECUTOR: OnceLock<FfiExecutor> = OnceLock::new();

fn enqueue_ffi<F>(f: F)
where
    F: FnOnce() + Send + 'static,
{
    let executor = FFI_EXECUTOR.get_or_init(FfiExecutor::spawn);
    let _ = executor.sender.send(FfiMessage::Task(Box::new(move || {
        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
    })));
}

async fn run_ffi<F, T>(f: F) -> Result<T, Status>
where
    F: FnOnce() -> T + Send + 'static,
    T: Send + 'static,
{
    let executor = FFI_EXECUTOR.get_or_init(FfiExecutor::spawn);
    let (tx, rx) = tokio::sync::oneshot::channel();
    executor
        .sender
        .send(FfiMessage::Task(Box::new(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
            let _ = tx.send(result);
        })))
        .map_err(|_| Status::internal("azookey FFI worker stopped"))?;
    match rx.await {
        Ok(Ok(value)) => Ok(value),
        Ok(Err(_panic)) => Err(Status::internal("azookey FFI task panicked")),
        Err(_) => Err(Status::internal("azookey FFI worker dropped response")),
    }
}

async fn authorize_composition<T>(
    mutation_guard: &mut tokio::sync::MutexGuard<'_, CompositionOwner>,
    request: &Request<T>,
    auto_claim: bool,
) -> Result<(), Status> {
    let mut released = false;
    mutation_guard.authorize(request, auto_claim, || {
        released = true;
    })?;
    if released {
        run_ffi(release_composition_state).await?;
    }
    Ok(())
}

fn conversion_continuation_kana_like(reading: &str) -> bool {
    !reading.is_empty()
        && reading
            .chars()
            .all(|character| matches!(character as u32, 0x3041..=0x3096 | 0x309D | 0x309E | 0x30FC))
}

fn valid_conversion_continuation(reading: &str, surface: &str, context: &str) -> bool {
    !reading.is_empty()
        && !surface.is_empty()
        && reading.chars().count() <= MAX_CONVERSION_CONTINUATION_READING_COUNT
        && surface.chars().count() <= MAX_CONVERSION_CONTINUATION_SURFACE_COUNT
        && conversion_continuation_kana_like(reading)
        && (context.is_empty() || context.ends_with(surface))
}

fn clear_conversion_continuation() {
    let previous = CONVERSION_CONTINUATION
        .write()
        .ok()
        .and_then(|mut continuation| continuation.take());
    unsafe { SetConversionContinuation(c"".as_ptr(), c"".as_ptr()) };
    if let Some(previous) = previous {
        log_event(
            ServerLogLevel::Debug,
            &format!(
                "[conversion_continuation] cleared reading_len={} surface_len={}",
                previous.reading.chars().count(),
                previous.surface.chars().count()
            ),
        );
    }
}

fn install_conversion_continuation(reading: &str, surface: &str) -> bool {
    let context = LAST_CONTEXT
        .read()
        .map(|context| context.clone())
        .unwrap_or_default();
    if !valid_conversion_continuation(reading, surface, &context) {
        clear_conversion_continuation();
        log_event(
            ServerLogLevel::Debug,
            &format!(
                "[conversion_continuation] rejected reading_len={} surface_len={} context_len={}",
                reading.chars().count(),
                surface.chars().count(),
                context.chars().count()
            ),
        );
        return false;
    }
    let reading_cstring = match cstring_from_input("SetConversionContinuation.reading", reading) {
        Ok(reading) => reading,
        Err(_) => {
            clear_conversion_continuation();
            return false;
        }
    };
    let surface_cstring = match cstring_from_input("SetConversionContinuation.surface", surface) {
        Ok(surface) => surface,
        Err(_) => {
            clear_conversion_continuation();
            return false;
        }
    };
    let installed =
        unsafe { SetConversionContinuation(reading_cstring.as_ptr(), surface_cstring.as_ptr()) };
    if installed {
        if let Ok(mut continuation) = CONVERSION_CONTINUATION.write() {
            *continuation = Some(ConversionContinuation {
                reading: reading.to_string(),
                surface: surface.to_string(),
            });
        }
    } else if let Ok(mut continuation) = CONVERSION_CONTINUATION.write() {
        *continuation = None;
    }
    log_event(
        ServerLogLevel::Debug,
        &format!(
            "[conversion_continuation] installed={installed} reading_len={} surface_len={}",
            reading.chars().count(),
            surface.chars().count()
        ),
    );
    installed
}

fn release_composition_state() {
    clear_text(); // Also clears snapshots and cached/pinned learning candidates.
    unsafe { SetContext(c"".as_ptr()) };
    clear_conversion_continuation();
    HAS_ACTIVE_COMPOSITION.store(false, Ordering::Relaxed);
}

#[tonic::async_trait]
impl AzookeyService for MyAzookeyService {
    async fn get_assist_candidates(
        &self,
        request: Request<shared::proto::GetAssistCandidatesRequest>,
    ) -> Result<Response<shared::proto::GetAssistCandidatesResponse>, Status> {
        // Same-session pipe access is checked by the transport. This is a read-only
        // snapshot request: no composition lock or Swift FFI on the worker.
        let request = request.into_inner();
        let config = current_assist_config();
        if request.kind == 3 {
            if !TRANSLATION_ENABLED.load(Ordering::Relaxed)
                || request.input_count <= 0
                || request.raw_input.chars().count() > 512
                || !ai_assist::translation::valid_source(&request.baseline)
            {
                return Ok(Response::new(Default::default()));
            }
            // A newer translation request supersedes the running one: cancel it
            // so the llama-server slot frees within a poll slice instead of
            // blocking the fresh request for up to the full 10s deadline.
            let cancel = Arc::new(ai_assist::translation::TranslationCancel::new());
            {
                let mut current = TRANSLATION_CANCEL.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(previous) = current.replace(Arc::clone(&cancel)) {
                    previous.cancel();
                }
            }
            let permit = TRANSLATION_REQUEST_SLOTS
                .acquire()
                .await
                .map_err(|_| Status::unavailable("translation stopped"))?;
            let response = tokio::task::spawn_blocking(move || {
                let _permit = permit;
                let configured_target = TRANSLATION_TARGET
                    .read()
                    .map(|target| target.clone())
                    .unwrap_or_default();
                let text = TRANSLATION_ENABLED
                    .load(Ordering::Relaxed)
                    .then(|| match configured_target.as_str() {
                        "en" | "ja" => ai_assist::translation::translate_to_cancellable(
                            &request.baseline,
                            configured_target.as_str(),
                            &cancel,
                        ),
                        _ => ai_assist::translation::translate_cancellable(
                            &request.baseline,
                            &cancel,
                        ),
                    })
                    .flatten();
                shared::proto::GetAssistCandidatesResponse {
                    suggestions: text
                        .into_iter()
                        .map(|text| Suggestion {
                            text,
                            subtext: String::new(),
                            corresponding_count: request.input_count,
                            candidate_id: shared::ai_candidate::TRANSLATION,
                        })
                        .collect(),
                    relevance: 100,
                    ..Default::default()
                }
            })
            .await
            .map_err(|_| Status::internal("translation worker failed"))?;
            return Ok(Response::new(response));
        }
        if !config.enabled
            || !(2..=512).contains(&request.reading.chars().count())
            || request.raw_input.chars().count() > 512
            || request.baseline.chars().count() > 512
            || request.input_count <= 0
        {
            return Ok(Response::new(
                shared::proto::GetAssistCandidatesResponse::default(),
            ));
        }
        let context = LAST_CONTEXT.read().map(|c| c.clone()).unwrap_or_default();
        if request.kind > 2 {
            return Err(Status::invalid_argument("unknown assistance kind"));
        }
        let slots = if request.kind == 2 {
            &PREDICTION_REQUEST_SLOTS
        } else {
            &ASSIST_REQUEST_SLOTS
        };
        // Waiting here is cancellable by the RPC: only a live snapshot may start
        // work after the previous bounded inference releases its permit.
        let permit = slots
            .acquire()
            .await
            .map_err(|_| Status::unavailable("assist stopped"))?;
        let response = tokio::task::spawn_blocking(move || {
            let _permit = permit;
            let mut suggestions = vec![Suggestion {
                text: request.baseline.clone(),
                subtext: String::new(),
                corresponding_count: request.input_count,
                candidate_id: 0,
            }];
            let (relevance, continuation) = append_ai_assist_candidates(
                &context,
                &request.reading,
                &request.raw_input,
                &config,
                &mut suggestions,
                request.kind,
            );
            suggestions.remove(0);
            let auto_apply = relevance == 100
                && suggestions
                    .iter()
                    .find(|s| s.candidate_id == shared::ai_candidate::TYPO_CORRECTION)
                    .is_some_and(|s| {
                        ai_assist::corrector::can_auto_apply_composition(
                            &request.reading,
                            &request.baseline,
                            &s.text,
                        )
                    });
            shared::proto::GetAssistCandidatesResponse {
                suggestions,
                auto_apply,
                relevance,
                continuation,
            }
        })
        .await
        .map_err(|_| Status::internal("assist worker failed"))?;
        Ok(Response::new(response))
    }

    async fn rerank_candidates(
        &self,
        request: Request<shared::proto::RerankCandidatesRequest>,
    ) -> Result<Response<shared::proto::RerankCandidatesResponse>, Status> {
        // Credential-backed requests require the same desktop trust boundary as
        // management RPCs; composition access alone is insufficient.
        PipeClient::authorize_management(&request)?;
        let request = request.into_inner();
        let request_id = request_id_or_next(request.request_id);
        let start = Instant::now();
        let candidate_count = request.candidates.len();
        let mut selected_index = None;
        if JEV_CONVERSION_ENABLED.load(Ordering::Relaxed) && valid_jev_request(&request) {
            if let Ok(permit) = JEV_REQUEST_SLOTS.try_acquire() {
                // The snapshot and API call are independent of Swift's global KKC state.
                selected_index = tokio::task::spawn_blocking(move || {
                    let _permit = permit;
                    if !JEV_CONVERSION_ENABLED.load(Ordering::Relaxed) {
                        return None;
                    }
                    let api_key = shared::jev_credentials::read_jev_api_key()?;
                    let payload = serde_json::json!({
                        "apiKey": api_key,
                        "reading": request.reading,
                        "context": request.context,
                        "candidates": request.candidates,
                    });
                    let json = CString::new(payload.to_string()).ok()?;
                    let index = unsafe { SelectJevCandidate(json.as_ptr()) };
                    (index >= 0 && (index as usize) < candidate_count).then_some(index as u32)
                })
                .await
                .unwrap_or(None);
            }
        }
        performance_event_lazy!(
            request_id,
            "jev_rerank",
            "total",
            elapsed_ms(start),
            "candidate_count={candidate_count};fallback={}",
            selected_index.is_none()
        );
        Ok(Response::new(shared::proto::RerankCandidatesResponse {
            selected_index,
        }))
    }

    async fn append_text(
        &self,
        request: Request<AppendTextRequest>,
    ) -> Result<Response<AppendTextResponse>, Status> {
        // Connection initialization is not a composition read or claim.
        if request.get_ref().text_to_append.is_empty() {
            PipeClient::authorize_connection(&request)?;
            return Ok(Response::new(AppendTextResponse {
                composing_text: Some(ComposingText::default()),
                server_session_id: server_session_id(),
            }));
        }
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let conversion_continuation_reading = request.conversion_continuation_reading;
        let conversion_continuation_surface = request.conversion_continuation_surface;
        let input_style = request.input_style;
        let input = request.text_to_append;
        let (composing_text, composed_text) = run_ffi(move || {
            set_request_id(request_id);
            let input_len = input.chars().count();
            match (
                conversion_continuation_reading.is_empty(),
                conversion_continuation_surface.is_empty(),
            ) {
                (false, false) => {
                    install_conversion_continuation(
                        &conversion_continuation_reading,
                        &conversion_continuation_surface,
                    );
                }
                (true, true) => {}
                _ => clear_conversion_continuation(),
            }
            let append_start = Instant::now();
            let composing_text = if input_style == INPUT_STYLE_DIRECT {
                add_text_direct(&input).map_err(|error| status_from_error("append_text", error))?
            } else {
                add_text(&input).map_err(|error| status_from_error("append_text", error))?
            };
            performance_event_lazy!(
                request_id,
                "append_text",
                "swift_append_text",
                elapsed_ms(append_start),
                "input_len={input_len};input_style={input_style}"
            );
            let get_composed_start = Instant::now();
            let composed_text = get_composed_text(false, None, request_id)
                .map_err(|error| status_from_error("append_text", error))?;
            performance_event_lazy!(
                request_id,
                "append_text",
                "swift_get_composed_text",
                elapsed_ms(get_composed_start),
                "suggestions={};hiragana_len={}",
                composed_text.suggestions.len(),
                composed_text
                    .hiragana
                    .as_ref()
                    .unwrap_or(&composing_text.text)
                    .chars()
                    .count()
            );
            update_active_composition_state(&composing_text.text);
            Ok::<_, Status>((composing_text, composed_text))
        })
        .await??;
        performance_event_lazy!(
            request_id,
            "append_text",
            "total",
            elapsed_ms(handler_start),
            "status=success;cursor={};hiragana_len={};suggestions={}",
            composing_text.cursor,
            composing_text.text.chars().count(),
            composed_text.suggestions.len()
        );

        let mut final_suggestions = composed_text.suggestions;
        let full_count = final_suggestions
            .iter()
            .map(|s| s.corresponding_count)
            .max()
            .unwrap_or(0);
        append_local_macros(
            composed_text
                .hiragana
                .as_deref()
                .unwrap_or(&composing_text.text),
            full_count,
            &mut final_suggestions,
        );
        let hiragana_text = composed_text
            .hiragana
            .unwrap_or_else(|| composing_text.text.clone());

        Ok(Response::new(AppendTextResponse {
            composing_text: Some(ComposingText {
                hiragana: hiragana_text,
                suggestions: final_suggestions,
            }),
            server_session_id: server_session_id(),
        }))
    }

    async fn replace_composition(
        &self,
        request: Request<ReplaceCompositionRequest>,
    ) -> Result<Response<ReplaceCompositionResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let operations = request.operations;
        let operation_count = operations.len();

        let (composing_text, composed_text) = run_ffi(move || {
            set_request_id(request_id);
            // This is an absolute-state operation: clear the old converter state and
            // replay the complete client-observed mutation log. It is safe to repeat after a
            // timeout whose server-side completion is unknown.
            clear_text();
            let mut composing_text = RawComposingText {
                text: String::new(),
                cursor: 0,
            };
            for operation in &operations {
                match CompositionOperationKind::try_from(operation.kind)
                    .map_err(|_| Status::invalid_argument("unknown composition operation"))?
                {
                    CompositionOperationKind::Append => {
                        composing_text = if operation.input_style == INPUT_STYLE_DIRECT {
                            add_text_direct(&operation.text)
                                .map_err(|error| status_from_error("replace_composition", error))?
                        } else {
                            add_text(&operation.text)
                                .map_err(|error| status_from_error("replace_composition", error))?
                        };
                    }
                    CompositionOperationKind::Remove => {
                        composing_text = remove_text()
                            .map_err(|error| status_from_error("replace_composition", error))?;
                    }
                    CompositionOperationKind::MoveCursor => {
                        composing_text = move_cursor(operation.cursor_offset)
                            .map_err(|error| status_from_error("replace_composition", error))?;
                    }
                }
            }

            let composed_text = if composing_text.text.is_empty() {
                ComposedText {
                    hiragana: Some(String::new()),
                    suggestions: Vec::new(),
                }
            } else {
                get_composed_text(false, None, request_id)
                    .map_err(|error| status_from_error("replace_composition", error))?
            };
            update_active_composition_state(&composing_text.text);
            Ok::<_, Status>((composing_text, composed_text))
        })
        .await??;
        performance_event_lazy!(
            request_id,
            "replace_composition",
            "total",
            elapsed_ms(handler_start),
            "status=success;operations={};hiragana_len={};suggestions={}",
            operation_count,
            composing_text.text.chars().count(),
            composed_text.suggestions.len()
        );

        let mut final_suggestions = composed_text.suggestions;
        let full_count = final_suggestions
            .iter()
            .map(|s| s.corresponding_count)
            .max()
            .unwrap_or(0);
        append_local_macros(
            composed_text
                .hiragana
                .as_deref()
                .unwrap_or(&composing_text.text),
            full_count,
            &mut final_suggestions,
        );
        let hiragana_text = composed_text
            .hiragana
            .unwrap_or_else(|| composing_text.text.clone());

        Ok(Response::new(ReplaceCompositionResponse {
            composing_text: Some(ComposingText {
                hiragana: hiragana_text,
                suggestions: final_suggestions,
            }),
            server_session_id: server_session_id(),
        }))
    }

    async fn start_reconversion(
        &self,
        request: Request<StartReconversionRequest>,
    ) -> Result<Response<StartReconversionResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, false).await?;
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.get_ref().request_id);
        let handler_start = Instant::now();
        let surface_len = validate_reconversion_surface(&request.get_ref().surface)?;
        let surface_for_infer = request.get_ref().surface.clone();

        let mut readings = run_ffi(move || {
            set_request_id(request_id);
            clear_conversion_continuation();
            infer_reconversion_readings(&surface_for_infer)
                .map_err(|error| status_from_error("start_reconversion", error))
        })
        .await??;
        readings.truncate(MAX_RECONVERSION_READINGS);
        if readings.is_empty() {
            performance_event_lazy!(
                request_id,
                "start_reconversion",
                "total",
                elapsed_ms(handler_start),
                "status=unsupported;surface_len={surface_len}"
            );
            return Ok(Response::new(StartReconversionResponse {
                composing_text: None,
                server_session_id: server_session_id(),
                selection_index: 0,
                applied: false,
            }));
        }

        // Reading inference is non-mutating. Only replace the converter state after it
        // succeeds, so unsupported text cannot disturb an existing composition.
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let surface = request.into_inner().surface;
        let reading_count = readings.len();
        let (hiragana, suggestions, chosen_reading, best_surface_rank) = run_ffi(move || {
            let mut chosen_index = 0;
            let mut best_surface_rank = usize::MAX;
            let mut conversions = Vec::with_capacity(readings.len());
            let mut last_composing_text = None;
            for (reading_index, reading) in readings.iter().enumerate() {
                clear_text();
                let composing = match add_text_direct(reading) {
                    Ok(text) => text,
                    Err(error) => {
                        clear_text();
                        update_active_composition_state("");
                        return Err(status_from_error("start_reconversion", error));
                    }
                };
                let composed = match get_reconversion_composed_text(request_id) {
                    Ok(text) => text,
                    Err(error) => {
                        clear_text();
                        update_active_composition_state("");
                        return Err(status_from_error("start_reconversion", error));
                    }
                };
                let surface_rank = reconversion_surface_rank(&composed.suggestions, &surface)
                    .unwrap_or(usize::MAX);
                if surface_rank < best_surface_rank {
                    best_surface_rank = surface_rank;
                    chosen_index = reading_index;
                }
                last_composing_text = Some(composing);
                conversions.push((reading.clone(), composed));
            }

            let chosen_reading = readings[chosen_index].clone();
            let last_index = conversions.len() - 1;
            let (composing_text, mut composed_text) = if chosen_index == last_index {
                let (_, composed) = conversions.pop().expect("readings are non-empty");
                (
                    last_composing_text.expect("readings are non-empty"),
                    composed,
                )
            } else {
                clear_text();
                let composing = add_text_direct(&chosen_reading).map_err(|error| {
                    clear_text();
                    update_active_composition_state("");
                    status_from_error("start_reconversion", error)
                })?;
                let composed = get_reconversion_composed_text(request_id).map_err(|error| {
                    clear_text();
                    update_active_composition_state("");
                    status_from_error("start_reconversion", error)
                })?;
                (composing, composed)
            };
            let hiragana = composed_text
                .hiragana
                .clone()
                .unwrap_or_else(|| composing_text.text.clone());
            let mut suggestion_groups = vec![std::mem::take(&mut composed_text.suggestions)];
            suggestion_groups.extend(
                conversions
                    .into_iter()
                    .enumerate()
                    .filter(|(index, _)| *index != chosen_index)
                    .map(|(_, (_, composed))| composed.suggestions),
            );
            let suggestions =
                merge_reconversion_suggestions(&surface, &hiragana, &suggestion_groups);
            update_active_composition_state(&composing_text.text);
            Ok::<_, Status>((hiragana, suggestions, chosen_reading, best_surface_rank))
        })
        .await??;
        let selection_index = 0;
        performance_event_lazy!(
            request_id,
            "start_reconversion",
            "total",
            elapsed_ms(handler_start),
            "status=success;surface_len={surface_len};reading_count={};chosen_reading_len={};surface_rank={best_surface_rank};suggestions={};selection_index={selection_index}",
            reading_count,
            chosen_reading.chars().count(),
            suggestions.len()
        );

        Ok(Response::new(StartReconversionResponse {
            composing_text: Some(ComposingText {
                hiragana,
                suggestions,
            }),
            server_session_id: server_session_id(),
            selection_index,
            applied: true,
        }))
    }

    async fn remove_text(
        &self,
        request: Request<RemoveTextRequest>,
    ) -> Result<Response<RemoveTextResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();

        let (composing_text, raw_input, composed_text) = run_ffi(move || {
            set_request_id(request_id);
            let remove_start = Instant::now();
            let composing_text =
                remove_text().map_err(|error| status_from_error("remove_text", error))?;
            let raw_input =
                get_raw_input().map_err(|error| status_from_error("remove_text", error))?;
            if composing_text.text.is_empty() {
                clear_conversion_continuation();
            }
            performance_event_lazy!(
                request_id,
                "remove_text",
                "swift_remove_text",
                elapsed_ms(remove_start),
                "hiragana_len={}",
                composing_text.text.chars().count()
            );
            let get_composed_start = Instant::now();
            let composed_text = get_composed_text(false, None, request_id)
                .map_err(|error| status_from_error("remove_text", error))?;
            performance_event_lazy!(
                request_id,
                "remove_text",
                "swift_get_composed_text",
                elapsed_ms(get_composed_start),
                "suggestions={}",
                composed_text.suggestions.len()
            );
            update_active_composition_state(&composing_text.text);
            Ok::<_, Status>((composing_text, raw_input, composed_text))
        })
        .await??;
        performance_event_lazy!(
            request_id,
            "remove_text",
            "total",
            elapsed_ms(handler_start),
            "status=success;cursor={};hiragana_len={};suggestions={}",
            composing_text.cursor,
            composing_text.text.chars().count(),
            composed_text.suggestions.len()
        );

        Ok(Response::new(RemoveTextResponse {
            composing_text: Some(ComposingText {
                hiragana: composed_text.hiragana.unwrap_or(composing_text.text),
                suggestions: composed_text.suggestions,
            }),
            server_session_id: server_session_id(),
            raw_input: Some(raw_input),
        }))
    }

    async fn move_cursor(
        &self,
        request: Request<MoveCursorRequest>,
    ) -> Result<Response<MoveCursorResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let raw_offset = request.offset;
        let use_cursor_prefix = raw_offset == 0;

        let (composing_text, composed_text, raw_input) = run_ffi(move || {
            set_request_id(request_id);
            clear_conversion_continuation();
            let move_start = Instant::now();
            let composing_text =
                move_cursor(raw_offset).map_err(|error| status_from_error("move_cursor", error))?;
            performance_event_lazy!(
                request_id,
                "move_cursor",
                "swift_move_cursor",
                elapsed_ms(move_start),
                "offset={raw_offset};hiragana_len={}",
                composing_text.text.chars().count()
            );
            let get_composed_start = Instant::now();
            let composed_text = get_composed_text(use_cursor_prefix, None, request_id)
                .map_err(|error| status_from_error("move_cursor", error))?;
            let raw_input = use_cursor_prefix
                .then(get_raw_input)
                .transpose()
                .map_err(|error| status_from_error("move_cursor", error))?;
            performance_event_lazy!(
                request_id,
                "move_cursor",
                "swift_get_composed_text",
                elapsed_ms(get_composed_start),
                "use_cursor_prefix={use_cursor_prefix};suggestions={}",
                composed_text.suggestions.len()
            );
            update_active_composition_state(&composing_text.text);
            Ok::<_, Status>((composing_text, composed_text, raw_input))
        })
        .await??;
        performance_event_lazy!(
            request_id,
            "move_cursor",
            "total",
            elapsed_ms(handler_start),
            "status=success;offset={raw_offset};cursor={};hiragana_len={};suggestions={};use_cursor_prefix={use_cursor_prefix}",
            composing_text.cursor,
            composing_text.text.chars().count(),
            composed_text.suggestions.len()
        );

        Ok(Response::new(MoveCursorResponse {
            composing_text: Some(ComposingText {
                hiragana: composed_text.hiragana.unwrap_or(composing_text.text),
                suggestions: composed_text.suggestions,
            }),
            server_session_id: server_session_id(),
            raw_input,
        }))
    }

    async fn adjust_clause_boundary(
        &self,
        request: Request<AdjustClauseBoundaryRequest>,
    ) -> Result<Response<AdjustClauseBoundaryResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let current_input_count = validate_shrink_offset(request.current_input_count)?;
        let expected_raw_input = request.expected_raw_input;
        let expected_input_count = expected_raw_input.chars().count();
        if current_input_count as usize > expected_input_count {
            return Err(Status::invalid_argument(
                "adjust_clause_boundary boundary exceeds expected raw input",
            ));
        }
        let direction = match request.direction {
            -1 | 1 => request.direction,
            _ => {
                return Err(Status::invalid_argument(
                    "adjust_clause_boundary direction must be -1 or 1",
                ));
            }
        };

        let response = run_ffi(move || {
            set_request_id(request_id);
            clear_conversion_continuation();
            let adjust_start = Instant::now();
            let adjustment =
                adjust_clause_boundary(current_input_count, direction, &expected_raw_input)
                    .map_err(|error| status_from_error("adjust_clause_boundary", error))?;
            performance_event_lazy!(
                request_id,
                "adjust_clause_boundary",
                "swift_adjust_boundary",
                elapsed_ms(adjust_start),
                "current_input_count={current_input_count};expected_input_count={};direction={direction};applied={};adjusted_input_count={};cursor_offset={}",
                expected_input_count,
                adjustment.applied,
                adjustment.adjusted_input_count,
                adjustment.cursor_offset
            );

            if !adjustment.applied {
                performance_event_lazy!(
                    request_id,
                    "adjust_clause_boundary",
                    "total",
                    elapsed_ms(handler_start),
                    "status=skipped;current_input_count={current_input_count};expected_input_count={};direction={direction}",
                    expected_input_count
                );
                return Ok(AdjustClauseBoundaryResponse {
                    composing_text: None,
                    applied: false,
                    adjusted_input_count: current_input_count,
                    cursor_offset: 0,
                    server_session_id: server_session_id(),
                });
            }

            let get_composed_start = Instant::now();
            let mut composed_text =
                match get_composed_text(true, Some(adjustment.adjusted_input_count), request_id) {
                    Ok(composed_text) => composed_text,
                    Err(error) => {
                        rollback_clause_boundary(
                            adjustment.adjusted_input_count,
                            current_input_count,
                            direction,
                            &expected_raw_input,
                        )
                        .map_err(|rollback_error| {
                            status_from_error(
                                "adjust_clause_boundary",
                                format!("{error}; {rollback_error}"),
                            )
                        })?;
                        return Err(status_from_error("adjust_clause_boundary", error));
                    }
                };
            performance_event_lazy!(
                request_id,
                "adjust_clause_boundary",
                "swift_get_composed_text",
                elapsed_ms(get_composed_start),
                "suggestions={}",
                composed_text.suggestions.len()
            );

            if !composed_text
                .suggestions
                .iter()
                .any(|candidate| candidate.corresponding_count == adjustment.adjusted_input_count)
            {
                let cursor = get_cursor_position();
                if let Some(fallback) = hiragana_boundary_fallback(
                    &adjustment.text,
                    cursor,
                    adjustment.adjusted_input_count,
                ) {
                    composed_text = fallback;
                    performance_event_lazy!(
                        request_id,
                        "adjust_clause_boundary",
                        "candidate_fallback",
                        0,
                        "status=hiragana;current_input_count={current_input_count};expected_input_count={};direction={direction};adjusted_input_count={};cursor={cursor}",
                        expected_input_count,
                        adjustment.adjusted_input_count
                    );
                } else {
                    rollback_clause_boundary(
                        adjustment.adjusted_input_count,
                        current_input_count,
                        direction,
                        &expected_raw_input,
                    )
                    .map_err(|error| status_from_error("adjust_clause_boundary", error))?;
                    return Err(Status::internal(
                        "adjust_clause_boundary could not materialize the adjusted boundary",
                    ));
                }
            }

            update_active_composition_state(&adjustment.text);
            performance_event_lazy!(
                request_id,
                "adjust_clause_boundary",
                "total",
                elapsed_ms(handler_start),
                "status=success;current_input_count={current_input_count};expected_input_count={};direction={direction};adjusted_input_count={};cursor_offset={};suggestions={}",
                expected_input_count,
                adjustment.adjusted_input_count,
                adjustment.cursor_offset,
                composed_text.suggestions.len()
            );
            Ok(AdjustClauseBoundaryResponse {
                composing_text: Some(ComposingText {
                    hiragana: composed_text.hiragana.unwrap_or(adjustment.text),
                    suggestions: composed_text.suggestions,
                }),
                applied: true,
                adjusted_input_count: adjustment.adjusted_input_count,
                cursor_offset: adjustment.cursor_offset,
                server_session_id: server_session_id(),
            })
        })
        .await??;
        Ok(Response::new(response))
    }

    async fn update_composition_snapshot(
        &self,
        request: Request<UpdateCompositionSnapshotRequest>,
    ) -> Result<Response<UpdateCompositionSnapshotResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let operation = CompositionSnapshotOperation::try_from(request.operation)
            .map_err(|_| Status::invalid_argument("unknown composition snapshot operation"))?;
        if operation == CompositionSnapshotOperation::Unspecified {
            return Err(Status::invalid_argument(
                "composition snapshot operation is required",
            ));
        }
        let selected_candidate_id = request.selected_candidate_id;

        run_ffi(move || {
            set_request_id(request_id);
            unsafe {
                match operation {
                    CompositionSnapshotOperation::Unspecified => {}
                    CompositionSnapshotOperation::Clear => ClearComposingTextSnapshots(),
                    CompositionSnapshotOperation::Push => {
                        PushComposingTextSnapshot(selected_candidate_id)
                    }
                    CompositionSnapshotOperation::Pop => {
                        PopComposingTextSnapshot(selected_candidate_id)
                    }
                }
            }
        })
        .await?;
        performance_event_lazy!(
            request_id,
            "update_composition_snapshot",
            "total",
            elapsed_ms(handler_start),
            "status=success;operation={operation:?}"
        );

        Ok(Response::new(UpdateCompositionSnapshotResponse {
            server_session_id: server_session_id(),
        }))
    }

    async fn clear_text(
        &self,
        request: Request<ClearTextRequest>,
    ) -> Result<Response<ClearTextResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, false).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let clear_start = Instant::now();
        run_ffi(move || {
            set_request_id(request_id);
            release_composition_state();
            clear_conversion_continuation();
        })
        .await?;
        mutation_guard.release();
        performance_event_lazy!(
            request_id,
            "clear_text",
            "swift_clear_text",
            elapsed_ms(clear_start),
            "status=success"
        );
        performance_event_lazy!(
            request_id,
            "clear_text",
            "total",
            elapsed_ms(handler_start),
            "status=success"
        );
        HAS_ACTIVE_COMPOSITION.store(false, Ordering::Relaxed);
        Ok(Response::new(ClearTextResponse {
            server_session_id: server_session_id(),
        }))
    }

    async fn shrink_text(
        &self,
        request: Request<ShrinkTextRequest>,
    ) -> Result<Response<ShrinkTextResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let raw_offset = validate_shrink_offset(request.offset)?;

        let (composing_text, composed_text) = run_ffi(move || {
            set_request_id(request_id);
            let shrink_start = Instant::now();
            let composing_text =
                shrink_text(raw_offset).map_err(|error| status_from_error("shrink_text", error))?;
            performance_event_lazy!(
                request_id,
                "shrink_text",
                "swift_shrink_text",
                elapsed_ms(shrink_start),
                "offset={raw_offset};hiragana_len={}",
                composing_text.text.chars().count()
            );
            let get_composed_start = Instant::now();
            let composed_text = get_composed_text(false, None, request_id)
                .map_err(|error| status_from_error("shrink_text", error))?;
            performance_event_lazy!(
                request_id,
                "shrink_text",
                "swift_get_composed_text",
                elapsed_ms(get_composed_start),
                "suggestions={}",
                composed_text.suggestions.len()
            );
            update_active_composition_state(&composing_text.text);
            Ok::<_, Status>((composing_text, composed_text))
        })
        .await??;
        performance_event_lazy!(
            request_id,
            "shrink_text",
            "total",
            elapsed_ms(handler_start),
            "status=success;hiragana_len={};suggestions={}",
            composing_text.text.chars().count(),
            composed_text.suggestions.len()
        );

        Ok(Response::new(ShrinkTextResponse {
            composing_text: Some(ComposingText {
                hiragana: composed_text.hiragana.unwrap_or(composing_text.text),
                suggestions: composed_text.suggestions,
            }),
            server_session_id: server_session_id(),
        }))
    }

    async fn advance_clause(
        &self,
        request: Request<AdvanceClauseRequest>,
    ) -> Result<Response<AdvanceClauseResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let raw_offset = validate_shrink_offset(request.offset)?;
        let selected_candidate_id = request.selected_candidate_id;

        let (shrunk_text, navigation_composing_text, raw_input) = run_ffi(move || {
            set_request_id(request_id);
            let snapshot_rollback = CompositionSnapshotRollback::push(selected_candidate_id);

            let shrink_start = Instant::now();
            let shrunk_text = shrink_text(raw_offset)
                .map_err(|error| status_from_error("advance_clause", error))?;
            let raw_input =
                get_raw_input().map_err(|error| status_from_error("advance_clause", error))?;
            performance_event_lazy!(
                request_id,
                "advance_clause",
                "swift_shrink_text",
                elapsed_ms(shrink_start),
                "offset={raw_offset};candidate_generation=deferred"
            );

            let move_start = Instant::now();
            let navigation_text =
                move_cursor(0).map_err(|error| status_from_error("advance_clause", error))?;
            let navigation_composed = get_composed_text(true, None, request_id)
                .map_err(|error| status_from_error("advance_clause", error))?;
            performance_event_lazy!(
                request_id,
                "advance_clause",
                "swift_move_cursor",
                elapsed_ms(move_start),
                "hiragana_len={};suggestions={}",
                navigation_text.text.chars().count(),
                navigation_composed.suggestions.len()
            );

            update_active_composition_state(&navigation_text.text);
            let nav_len = navigation_composed.suggestions.len();
            let navigation_composing_text = ComposingText {
                hiragana: navigation_composed.hiragana.unwrap_or(navigation_text.text),
                suggestions: navigation_composed.suggestions,
            };
            snapshot_rollback.commit();
            performance_event_lazy!(
                request_id,
                "advance_clause",
                "total",
                elapsed_ms(handler_start),
                "status=success;offset={raw_offset};navigation_suggestions={}",
                nav_len
            );
            Ok::<_, Status>((shrunk_text, navigation_composing_text, raw_input))
        })
        .await??;

        Ok(Response::new(AdvanceClauseResponse {
            shrunk_text: Some(ComposingText {
                hiragana: shrunk_text.text,
                suggestions: Vec::new(),
            }),
            navigation_text: Some(navigation_composing_text),
            server_session_id: server_session_id(),
            raw_input,
        }))
    }

    async fn prepare_future_clauses(
        &self,
        request: Request<PrepareFutureClausesRequest>,
    ) -> Result<Response<PrepareFutureClausesResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let mut offset = validate_shrink_offset(request.initial_offset)?;
        let leave_at_last = request.leave_at_last;
        let initial_selected_candidate_id = request.initial_selected_candidate_id;

        let (advances, completed) = run_ffi(move || {
            set_request_id(request_id);
            let mut advances = Vec::new();
            let mut snapshot_count = 0usize;
            let mut last_signature = None;
            let mut completed = false;
            let mut selected_candidate_id = initial_selected_candidate_id;

            let result = (|| -> Result<(), Box<Status>> {
                for _ in 0..shared::MAX_PREPARED_CLAUSE_ADVANCES {
                    unsafe {
                        PushComposingTextSnapshot(selected_candidate_id);
                    }
                    snapshot_count += 1;

                    let shrunk_text = shrink_text(offset).map_err(|error| {
                        Box::new(status_from_error("prepare_future_clauses", error))
                    })?;
                    let navigation_text = move_cursor(0).map_err(|error| {
                        Box::new(status_from_error("prepare_future_clauses", error))
                    })?;
                    let navigation_composed =
                        get_composed_text(true, None, request_id).map_err(|error| {
                            Box::new(status_from_error("prepare_future_clauses", error))
                        })?;

                    let Some(selected) = navigation_composed.suggestions.first() else {
                        break;
                    };
                    selected_candidate_id = selected.candidate_id;
                    unsafe {
                        PinLearningCandidate(selected_candidate_id);
                    }
                    let is_last = selected.subtext.is_empty();
                    let signature = (
                        navigation_composed
                            .hiragana
                            .as_deref()
                            .unwrap_or(&navigation_text.text)
                            .to_string(),
                        selected.corresponding_count,
                        selected.subtext.clone(),
                    );
                    if last_signature.as_ref() == Some(&signature) {
                        break;
                    }
                    last_signature = Some(signature);
                    offset =
                        validate_shrink_offset(selected.corresponding_count).map_err(Box::new)?;
                    let navigation_composing_text = ComposingText {
                        hiragana: navigation_composed.hiragana.unwrap_or(navigation_text.text),
                        suggestions: navigation_composed.suggestions,
                    };
                    advances.push(PreparedClauseAdvance {
                        shrunk_text: Some(ComposingText {
                            hiragana: shrunk_text.text,
                            suggestions: Vec::new(),
                        }),
                        navigation_text: Some(navigation_composing_text),
                    });
                    if is_last {
                        completed = true;
                        break;
                    }
                }
                Ok(())
            })();

            let retained_snapshot_count = retained_prepared_snapshot_count(
                result.is_ok(),
                leave_at_last,
                completed,
                advances.len(),
            );
            for _ in retained_snapshot_count..snapshot_count {
                unsafe {
                    PopComposingTextSnapshot(0);
                }
            }
            result.map_err(|status| *status)?;

            if leave_at_last && completed {
                if let Some(final_text) = advances
                    .last()
                    .and_then(|advance| advance.navigation_text.as_ref())
                {
                    update_active_composition_state(&final_text.hiragana);
                }
            }
            Ok::<_, Status>((advances, completed))
        })
        .await??;

        performance_event_lazy!(
            request_id,
            "prepare_future_clauses",
            "total",
            elapsed_ms(handler_start),
            "status=success;advance_count={};leave_at_last={leave_at_last};completed={completed}",
            advances.len(),
        );
        Ok(Response::new(PrepareFutureClausesResponse {
            advances,
            server_session_id: server_session_id(),
            completed,
        }))
    }

    async fn set_context(
        &self,
        request: Request<shared::proto::SetContextRequest>,
    ) -> Result<Response<shared::proto::SetContextResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(false);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let context = request.context;
        let trimmed_context = context
            .split('\r')
            .rfind(|s| !s.is_empty())
            .unwrap_or_default()
            .to_string();
        let original_len = context.chars().count();
        let trimmed_len = trimmed_context.chars().count();
        let context_cstring = cstring_from_input("SetContext.context", &trimmed_context)
            .map_err(|error| status_from_error("set_context", error))?;

        run_ffi(move || {
            set_request_id(request_id);
            if let Ok(mut lock) = LAST_CONTEXT.write() {
                *lock = trimmed_context.clone();
            }
            let continuation_context_matches = CONVERSION_CONTINUATION
                .read()
                .map(|continuation| {
                    continuation.as_ref().map_or(true, |continuation| {
                        trimmed_context.is_empty()
                            || trimmed_context.ends_with(continuation.surface.as_str())
                    })
                })
                .unwrap_or(false);
            if !continuation_context_matches {
                clear_conversion_continuation();
            }
            let set_context_start = Instant::now();
            unsafe { SetContext(context_cstring.as_ptr()) };
            performance_event_lazy!(
                request_id,
                "set_context",
                "swift_set_context",
                elapsed_ms(set_context_start),
                "original_len={original_len};trimmed_len={trimmed_len}"
            );
        })
        .await?;
        performance_event_lazy!(
            request_id,
            "set_context",
            "total",
            elapsed_ms(handler_start),
            "status=success"
        );
        Ok(Response::new(shared::proto::SetContextResponse {
            server_session_id: server_session_id(),
        }))
    }

    async fn update_config(
        &self,
        request: Request<shared::proto::UpdateConfigRequest>,
    ) -> Result<Response<shared::proto::UpdateConfigResponse>, Status> {
        PipeClient::authorize_management(&request)?;
        let _mutation_guard = self.mutation_lock.lock().await;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(false);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let has_active_composition = run_ffi(move || {
            let _log_paths = reload_server_logging_from_settings();
            set_request_id(request_id);
            let load_config_start = Instant::now();
            unsafe { LoadConfig() };
            let has_active_composition = query_active_composition_state();
            performance_event_lazy!(
                request_id,
                "update_config",
                "swift_load_config",
                elapsed_ms(load_config_start),
                "active_composition={has_active_composition}"
            );
            HAS_ACTIVE_COMPOSITION.store(has_active_composition, Ordering::Relaxed);
            has_active_composition
        })
        .await?;
        performance_event_lazy!(
            request_id,
            "update_config",
            "total",
            elapsed_ms(handler_start),
            "status=success;active_composition={has_active_composition}"
        );
        Ok(Response::new(shared::proto::UpdateConfigResponse {
            server_session_id: server_session_id(),
        }))
    }

    async fn commit_learning_candidate(
        &self,
        request: Request<shared::proto::CommitLearningCandidateRequest>,
    ) -> Result<Response<shared::proto::CommitLearningCandidateResponse>, Status> {
        if request.get_ref().candidate_id == shared::ai_candidate::PREDICTIVE {
            PipeClient::authorize_connection(&request)?;
            let feedback = request.into_inner();
            if !(3..=512).contains(&feedback.surface.chars().count())
                || feedback
                    .surface
                    .chars()
                    .any(|c| c.is_control() || ('\u{EE00}'..='\u{EE02}').contains(&c))
            {
                return Err(Status::invalid_argument("invalid prediction feedback"));
            }
            // Late post-commit feedback must not reacquire composition ownership
            // or interfere with the application that now has focus.
            // Compatibility with older clients: acknowledge without learning.
            return Ok(Response::new(
                shared::proto::CommitLearningCandidateResponse {
                    server_session_id: server_session_id(),
                },
            ));
        }
        if request.get_ref().candidate_id == shared::ai_candidate::TYPO_CORRECTION
            && !request.get_ref().typo_reading.is_empty()
        {
            // Feedback is stateless. It must not claim/clear a newer composition
            // when the client flushes a previous explicit acceptance after commit.
            let feedback = request.into_inner();
            if feedback.typo_reading.chars().count() <= 128
                && feedback.surface.chars().count() <= 384
            {
                tokio::task::spawn_blocking(move || {
                    ai_assist::record_learned_typo(&feedback.typo_reading, &feedback.surface)
                })
                .await
                .map_err(|_| Status::internal("typo feedback failed"))?;
            }
            return Ok(Response::new(
                shared::proto::CommitLearningCandidateResponse {
                    server_session_id: server_session_id(),
                },
            ));
        }
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let candidate_id = request.candidate_id;
        let commit_kind = request.commit_kind;
        let surface = request.surface;

        // AI アシスト候補は変換エンジンの学習キャッシュに存在しないため、
        // エンジンへは渡さず AI 学習経路で処理する。
        if shared::ai_candidate::is_ai_assist_candidate(candidate_id) {
            let committed = tokio::task::spawn_blocking(move || {
                handle_ai_assist_learning_commit(candidate_id, &surface)
            })
            .await
            .unwrap_or(false);
            performance_event_lazy!(
                request_id,
                "commit_learning_candidate",
                "ai_assist_learning",
                elapsed_ms(handler_start),
                "candidate_id={candidate_id};commit_kind={commit_kind};committed={committed}"
            );
            return Ok(Response::new(
                shared::proto::CommitLearningCandidateResponse {
                    server_session_id: server_session_id(),
                },
            ));
        }

        let committed = run_ffi(move || {
            set_request_id(request_id);
            let commit_start = Instant::now();
            let committed = swift_commit_learning_candidate(candidate_id, commit_kind);
            if !committed {
                log_event(
                    ServerLogLevel::Warn,
                    &format!(
                        "[commit_learning_candidate] candidate not learned candidate_id={candidate_id};commit_kind={commit_kind}"
                    ),
                );
            }
            performance_event_lazy!(
                request_id,
                "commit_learning_candidate",
                "swift_commit_learning_candidate",
                elapsed_ms(commit_start),
                "candidate_id={candidate_id};commit_kind={commit_kind};committed={committed}"
            );
            committed
        })
        .await?;
        performance_event_lazy!(
            request_id,
            "commit_learning_candidate",
            "total",
            elapsed_ms(handler_start),
            "status=success;candidate_id={candidate_id};commit_kind={commit_kind};committed={committed}"
        );

        Ok(Response::new(
            shared::proto::CommitLearningCandidateResponse {
                server_session_id: server_session_id(),
            },
        ))
    }

    async fn commit_learning_candidates(
        &self,
        request: Request<shared::proto::CommitLearningCandidatesRequest>,
    ) -> Result<Response<shared::proto::CommitLearningCandidatesResponse>, Status> {
        let mut mutation_guard = self.mutation_lock.lock().await;
        authorize_composition(&mut mutation_guard, &request, true).await?;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(true);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();
        let requested_count = request.commits.len();

        // AI アシスト候補を変換エンジンの学習対象から分離する。
        let mut engine_commits = Vec::with_capacity(requested_count);
        let mut ai_commits = Vec::new();
        for commit in request.commits {
            if shared::ai_candidate::is_ai_assist_candidate(commit.candidate_id) {
                ai_commits.push(commit);
            } else {
                engine_commits.push(commit);
            }
        }

        let committed_count = run_ffi(move || {
            set_request_id(request_id);
            let mut committed_count = 0usize;
            for commit in &ai_commits {
                if handle_ai_assist_learning_commit(commit.candidate_id, &commit.surface) {
                    committed_count += 1;
                }
            }

            let commit_start = Instant::now();
            if !engine_commits.is_empty() {
                committed_count += swift_commit_learning_candidates(&engine_commits);
            }
            performance_event_lazy!(
                request_id,
                "commit_learning_candidates",
                "swift_commit_learning_candidates",
                elapsed_ms(commit_start),
                "requested_count={requested_count};committed_count={committed_count}"
            );
            committed_count
        })
        .await?;
        performance_event_lazy!(
            request_id,
            "commit_learning_candidates",
            "total",
            elapsed_ms(handler_start),
            "status=success;requested_count={requested_count};committed_count={committed_count}"
        );

        Ok(Response::new(
            shared::proto::CommitLearningCandidatesResponse {
                server_session_id: server_session_id(),
                committed_count: u32::try_from(committed_count).unwrap_or(u32::MAX),
            },
        ))
    }

    async fn reset_learning_memory(
        &self,
        request: Request<shared::proto::ResetLearningMemoryRequest>,
    ) -> Result<Response<shared::proto::ResetLearningMemoryResponse>, Status> {
        PipeClient::authorize_management(&request)?;
        let _mutation_guard = self.mutation_lock.lock().await;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(false);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();

        // An unspecified scope keeps the historical behaviour: everything.
        let scopes = if request.scopes.is_empty() {
            vec![
                shared::proto::LearningMemoryScope::Conversion as i32,
                shared::proto::LearningMemoryScope::Typo as i32,
            ]
        } else {
            request.scopes.clone()
        };
        let wants_conversion =
            scopes.contains(&(shared::proto::LearningMemoryScope::Conversion as i32));
        let wants_typo = scopes.contains(&(shared::proto::LearningMemoryScope::Typo as i32));

        let mut applied = Vec::new();
        let mut failed = Vec::new();
        let mut reset = true;

        if wants_conversion {
            let reset_start = Instant::now();
            let reset_conversion = run_ffi(move || {
                set_request_id(request_id);
                swift_reset_learning_memory()
            })
            .await?;
            performance_event_lazy!(
                request_id,
                "reset_learning_memory",
                "swift_reset_learning_memory",
                elapsed_ms(reset_start),
                "reset={reset_conversion}"
            );
            if reset_conversion {
                applied.push(shared::proto::LearningMemoryScope::Conversion as i32);
            } else {
                // The engine can legitimately have nothing to reset; report the
                // failure only when the caller asked for scopes it could not serve.
                failed.push(shared::proto::LearningMemoryScope::Conversion as i32);
                reset = false;
                log_event(
                    ServerLogLevel::Warn,
                    "[reset_learning_memory] Swift reset returned false",
                );
            }
        }

        if wants_typo {
            let cleared = tokio::task::spawn_blocking(ai_assist::learning::clear_learned_typos)
                .await
                .map_err(|_| Status::internal("typo reset failed"))?;
            if cleared {
                applied.push(shared::proto::LearningMemoryScope::Typo as i32);
            } else {
                // Clearing an empty store is not a failure.
                applied.push(shared::proto::LearningMemoryScope::Typo as i32);
            }
        }

        let stats = run_ffi(swift_get_learning_stats).await?;
        performance_event_lazy!(
            request_id,
            "reset_learning_memory",
            "total",
            elapsed_ms(handler_start),
            "status=success;reset={reset};applied={applied:?};failed={failed:?}"
        );

        Ok(Response::new(shared::proto::ResetLearningMemoryResponse {
            server_session_id: server_session_id(),
            reset,
            applied,
            failed,
            stats,
        }))
    }

    async fn forget_learning_candidate(
        &self,
        request: Request<shared::proto::ForgetLearningCandidateRequest>,
    ) -> Result<Response<shared::proto::ForgetLearningCandidateResponse>, Status> {
        PipeClient::authorize_connection(&request)?;
        let _mutation_guard = self.mutation_lock.lock().await;
        let request = request.into_inner();
        let _request_guard = ServerRequestGuard::begin(false);
        let request_id = request_id_or_next(request.request_id);
        let handler_start = Instant::now();

        // Deleting an AI assist typo is a separate store with its own key.
        if request.candidate_id == shared::ai_candidate::TYPO_CORRECTION
            && !request.typo_reading.is_empty()
        {
            if request.typo_reading.chars().count() > 128 || request.surface.chars().count() > 384 {
                return Err(Status::invalid_argument("invalid typo feedback"));
            }
            let reading = request.typo_reading;
            let surface = request.surface;
            let forgot = tokio::task::spawn_blocking(move || {
                ai_assist::learning::forget_learned_typo(&reading, &surface)
            })
            .await
            .map_err(|_| Status::internal("typo deletion failed"))?;
            performance_event_lazy!(
                request_id,
                "forget_learning_candidate",
                "typo_learning",
                elapsed_ms(handler_start),
                "candidate_id={};forgot={forgot}",
                request.candidate_id
            );
            return Ok(Response::new(
                shared::proto::ForgetLearningCandidateResponse {
                    server_session_id: server_session_id(),
                    forgot,
                },
            ));
        }

        if shared::ai_candidate::is_ai_assist_candidate(request.candidate_id) {
            return Ok(Response::new(
                shared::proto::ForgetLearningCandidateResponse {
                    server_session_id: server_session_id(),
                    forgot: false,
                },
            ));
        }

        let candidate_id = request.candidate_id;
        let forgot = run_ffi(move || {
            set_request_id(request_id);
            swift_forget_learning_candidate(candidate_id)
        })
        .await?;
        if !forgot {
            log_event(
                ServerLogLevel::Warn,
                &format!("[forget_learning_candidate] not forgotten candidate_id={candidate_id}"),
            );
        }
        performance_event_lazy!(
            request_id,
            "forget_learning_candidate",
            "swift_forget_learning_candidate",
            elapsed_ms(handler_start),
            "candidate_id={candidate_id};forgot={forgot}"
        );
        Ok(Response::new(
            shared::proto::ForgetLearningCandidateResponse {
                server_session_id: server_session_id(),
                forgot,
            },
        ))
    }

    async fn get_learning_stats(
        &self,
        request: Request<shared::proto::LearningStatsRequest>,
    ) -> Result<Response<shared::proto::LearningStatsResponse>, Status> {
        PipeClient::authorize_connection(&request)?;
        let _request_guard = ServerRequestGuard::begin(false);
        let request = request.into_inner();
        let request_id = request_id_or_next(request.request_id);
        let stats = run_ffi(move || {
            set_request_id(request_id);
            swift_get_learning_stats()
        })
        .await?
        .unwrap_or_else(|| shared::proto::LearningStatsResponse {
            server_session_id: server_session_id(),
            selection_count: 0,
            history_present: false,
            history_paused: false,
            learning_mode: String::new(),
            typo_count: ai_assist::learning::learned_typo_count() as u32,
        });
        Ok(Response::new(stats))
    }

    async fn log_performance(
        &self,
        request: Request<PerformanceLogRequest>,
    ) -> Result<Response<PerformanceLogResponse>, Status> {
        PipeClient::authorize_connection(&request)?;
        let _request_guard = ServerRequestGuard::begin(false);
        let request = request.into_inner();
        log_performance_event(
            request.request_id,
            &request.component,
            &request.operation,
            &request.stage,
            u128::from(request.elapsed_ms),
            &request.details,
        );
        Ok(Response::new(PerformanceLogResponse {}))
    }
}

struct MainFfiShutdownGuard;

impl Drop for MainFfiShutdownGuard {
    fn drop(&mut self) {
        shutdown_main_ffi();
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    ai_assist::runtime::configure_settings_resolver(|| AppConfig::settings_path().ok());
    // Child loader failures must exit instead of opening an invisible modal
    // entry-point dialog. Workers inherit this process error mode.
    unsafe {
        use windows::Win32::System::Diagnostics::Debug::{
            SetErrorMode, SEM_FAILCRITICALERRORS, SEM_NOGPFAULTERRORBOX, SEM_NOOPENFILEERRORBOX,
        };
        SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
    }
    shared::enable_redirection_guard().map_err(std::io::Error::other)?;
    install_panic_hook();
    let log_paths = reload_server_logging_from_settings();
    if let Some(trace) = preserve_crash_trace_if_incomplete(
        SERVER_CRASH_TRACE_FILE_NAME,
        SERVER_PREVIOUS_CRASH_TRACE_FILE_NAME,
    ) {
        log_previous_crash_trace("previous server crash trace", &trace);
    }
    log_and_consume_previous_crash_trace_if_incomplete(
        LAUNCHER_PREVIOUS_CRASH_TRACE_FILE_NAME,
        "previous launcher startup crash trace",
    );
    write_server_crash_trace("rust", "server_startup", "main", "begin", "");
    if let Some(log_paths) = log_paths {
        log_event(
            ServerLogLevel::Info,
            &format!(
                "AzookeyServer started (log_path={} performance_log_path={})",
                log_paths.server_log.display(),
                log_paths.performance_log.display()
            ),
        );
    }
    register_server_log_callbacks();

    // Keep IME RPC latency predictable when other applications saturate the CPU.
    unsafe {
        match SetPriorityClass(GetCurrentProcess(), HIGH_PRIORITY_CLASS) {
            Ok(()) => log_event_lazy!(ServerLogLevel::Info, "process priority set to HIGH"),
            Err(e) => log_event_lazy!(ServerLogLevel::Info, "SetPriorityClass failed: {e}"),
        }
    }

    let current_exe = std::env::current_exe()?;
    let parent_dir = current_exe
        .parent()
        .ok_or_else(|| std::io::Error::other("failed to get executable parent directory"))?;
    let parent_dir_str = parent_dir
        .to_str()
        .ok_or_else(|| std::io::Error::other("executable path is not valid UTF-8"))?
        .to_string();
    let init_path =
        cstring_from_input("Initialize.path", &parent_dir_str).map_err(std::io::Error::other)?;

    // Run Swift/Zenzai FFI sequentially on the OS main thread while Tokio drives
    // named-pipe IPC and AI assist on its multi-thread runtime.
    let ffi_receiver = FfiExecutor::install_main();
    enqueue_ffi(move || unsafe {
        Initialize(init_path.as_ptr(), USE_ZENZAI);
    });

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()?;
    let server_handle = std::thread::Builder::new()
        .name("azookey-tokio".to_owned())
        .spawn(move || -> std::io::Result<()> {
            let _shutdown_guard = MainFfiShutdownGuard;
            let result = runtime.block_on(run_server());
            drop(runtime);
            result
        })?;

    run_ffi_loop(ffi_receiver);

    match server_handle.join() {
        Ok(result) => result.map_err(Into::into),
        Err(_) => Err(std::io::Error::other("azookey server runtime panicked").into()),
    }
}

async fn run_server() -> std::io::Result<()> {
    let service = MyAzookeyService::default();

    let warmup_service = service.clone();
    tokio::spawn(async move {
        // Yield briefly after opening the pipe so initial focus handshakes finish first,
        // then warm up the foreground converter once if the user is not yet typing.
        tokio::time::sleep(std::time::Duration::from_millis(350)).await;
        let request_id = next_request_id();
        if let Some(reason) = warmup_skip_reason() {
            let details = reason.details();
            log_event_lazy!(
                ServerLogLevel::Debug,
                "request_id={request_id} [warmup] skipped {details}"
            );
            performance_event_lazy!(request_id, "warmup", "skip", 0, "{details}");
            return;
        }
        let Ok(_guard) = warmup_service.mutation_lock.try_lock() else {
            performance_event_lazy!(
                request_id,
                "warmup",
                "skip",
                0,
                "reason=composition_lock_busy"
            );
            return;
        };
        if let Some(reason) = warmup_skip_reason() {
            let details = reason.details();
            performance_event_lazy!(request_id, "warmup", "skip", 0, "{details}");
            return;
        }
        let schedule_start = Instant::now();
        let Ok(scheduled) = run_ffi(move || {
            set_request_id(request_id);
            warmup()
        })
        .await
        else {
            return;
        };
        let schedule_elapsed_ms = elapsed_ms(schedule_start);
        if scheduled {
            performance_event_lazy!(
                request_id,
                "warmup",
                "schedule",
                schedule_elapsed_ms,
                "status=scheduled"
            );
        } else {
            performance_event_lazy!(
                request_id,
                "warmup",
                "skip",
                schedule_elapsed_ms,
                "reason=active_composition"
            );
        }
    });

    let reflection_service = ReflectionBuilder::configure()
        .register_encoded_file_descriptor_set(shared::proto::FILE_DESCRIPTOR_SET)
        .build_v1()
        .map_err(std::io::Error::other)?;

    let pipe_path = server_pipe_path().map_err(std::io::Error::other)?;
    let incoming = TonicNamedPipeServer::new_with_first_pipe_callback(pipe_path, move || {
        log_event_lazy!(ServerLogLevel::Info, "AzookeyServer listening");
        write_server_crash_trace(
            "rust",
            "server_startup",
            "listening",
            "completed",
            &format!("pipe={pipe_path}"),
        );
        write_crash_trace_file(
            LAUNCHER_CRASH_TRACE_FILE_NAME,
            "rust",
            "server_startup",
            "server_listening",
            "completed",
            &format!("server_pid={};pipe={pipe_path}", std::process::id()),
        );
    })
    .map_err(std::io::Error::other)?;

    Server::builder()
        .add_service(AzookeyServiceServer::new(service))
        .add_service(reflection_service)
        .serve_with_incoming(incoming)
        .await
        .map_err(|error| {
            log_event(
                ServerLogLevel::Error,
                &format!("AzookeyServer terminated with error: {error}"),
            );
            std::io::Error::other(error)
        })?;

    Ok(())
}

#[cfg(test)]
mod path_tests {
    #[tokio::test]
    async fn prediction_does_not_wait_for_correction_or_complete_unfinished_romaji() {
        use shared::proto::azookey_service_server::AzookeyService;
        super::AI_ASSIST_ENABLED.store(true, std::sync::atomic::Ordering::Relaxed);
        let _correction = super::ASSIST_REQUEST_SLOTS.acquire().await.unwrap();
        let service = super::MyAzookeyService::default();
        let response = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            service.get_assist_candidates(tonic::Request::new(
                shared::proto::GetAssistCandidatesRequest {
                    reading: "よろしくおねがいしn".into(),
                    baseline: "よろしくお願いしn".into(),
                    input_count: 20,
                    kind: 2,
                    ..Default::default()
                },
            )),
        )
        .await
        .expect("prediction must not acquire the correction permit")
        .unwrap()
        .into_inner();
        assert!(!response.auto_apply);
        assert!(response.continuation.is_empty());
        assert!(!response
            .suggestions
            .iter()
            .any(|s| s.candidate_id == shared::ai_candidate::PREDICTIVE));
        assert!(!response
            .suggestions
            .iter()
            .any(|s| s.candidate_id == shared::ai_candidate::TYPO_CORRECTION));
    }
    #[tokio::test]
    async fn assistance_generates_from_snapshot_without_waiting_for_composition_lock() {
        use shared::proto::azookey_service_server::AzookeyService;
        super::AI_ASSIST_ENABLED.store(true, std::sync::atomic::Ordering::Relaxed);
        let service = super::MyAzookeyService::default();
        let _guard = service.mutation_lock.lock().await;
        let response = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            service.get_assist_candidates(tonic::Request::new(
                shared::proto::GetAssistCandidatesRequest {
                    reading: "よろしくn".into(),
                    raw_input: "yorosiku".into(),
                    baseline: "よろしくn".into(),
                    input_count: 8,
                    request_id: 1,
                    kind: 0,
                },
            )),
        )
        .await
        .expect("assistance must not wait for Swift composition")
        .unwrap()
        .into_inner();
        assert!(!response
            .suggestions
            .iter()
            .any(|s| s.candidate_id == shared::ai_candidate::PREDICTIVE));
        assert!(!response.auto_apply);
    }
    #[test]
    fn correction_does_not_suppress_prediction() {
        use std::sync::atomic::Ordering;
        let _guard = super::ai_assist::prediction::tests::MODEL_TEST_LOCK
            .lock()
            .unwrap();
        let (config, model) = super::ai_assist::prediction::tests::model_fixture([
            "イタシマス。",
            "おつれさまよろしくお願いいたします。",
        ]);

        super::AI_ASSIST_ENABLED.store(true, Ordering::Relaxed);
        // A correction and an unchanged-surface completion must coexist.
        let mut suggestions = vec![shared::proto::Suggestion {
            text: "おつれさまよろしくお願い".to_string(),
            subtext: String::new(),
            corresponding_count: 5,
            candidate_id: 0,
        }];
        super::append_ai_assist_candidates(
            "",
            "おつれさまよろしくおねがい",
            "おつれさまよろしくおねがい",
            &config,
            &mut suggestions,
            0,
        );

        let ids: Vec<u64> = suggestions.iter().map(|s| s.candidate_id).collect();
        assert!(
            ids.contains(&shared::ai_candidate::TYPO_CORRECTION),
            "typo correction should be offered, ids={ids:?}"
        );
        assert!(
            ids.contains(&shared::ai_candidate::PREDICTIVE),
            "a weak correction must not suppress prediction, ids={ids:?}"
        );
        assert_eq!(model.join().unwrap().len(), 2);
    }

    #[test]
    fn predictive_is_offered_when_no_typo_correction_exists() {
        use std::sync::atomic::Ordering;
        let _guard = super::ai_assist::prediction::tests::MODEL_TEST_LOCK
            .lock()
            .unwrap();
        let (config, model) = super::ai_assist::prediction::tests::model_fixture([
            "イタシマス。",
            "よろしくいたします。",
        ]);

        super::AI_ASSIST_ENABLED.store(true, Ordering::Relaxed);
        let mut suggestions = vec![shared::proto::Suggestion {
            text: "よろしく".to_string(),
            subtext: String::new(),
            corresponding_count: 4,
            candidate_id: 0,
        }];
        super::append_ai_assist_candidates(
            "",
            "よろしく",
            "よろしく",
            &config,
            &mut suggestions,
            0,
        );

        let ids: Vec<u64> = suggestions.iter().map(|s| s.candidate_id).collect();
        assert!(
            ids.contains(&shared::ai_candidate::PREDICTIVE),
            "predictive should be offered, ids={ids:?}"
        );
        assert!(
            !ids.contains(&shared::ai_candidate::TYPO_CORRECTION),
            "typo correction must not be offered without a typo, ids={ids:?}"
        );
        assert_eq!(model.join().unwrap().len(), 2);
    }

    #[test]
    fn prediction_is_not_offered_for_digit_or_symbol_tail() {
        use std::net::TcpListener;
        use std::sync::atomic::Ordering;
        let _guard = super::ai_assist::prediction::tests::MODEL_TEST_LOCK
            .lock()
            .unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let port = listener.local_addr().unwrap().port();
        let config = super::AiAssistConfig {
            enabled: true,
            prediction_port: port,
            corrector_port: 1,
            ..Default::default()
        };

        super::AI_ASSIST_ENABLED.store(true, Ordering::Relaxed);
        for (reading, surface) in [("だい1", "第1"), ("ねん3", "年3"), ("だい#", "第#")] {
            let mut suggestions = vec![shared::proto::Suggestion {
                text: surface.to_string(),
                subtext: String::new(),
                corresponding_count: 2,
                candidate_id: 0,
            }];
            super::append_ai_assist_candidates("", reading, reading, &config, &mut suggestions, 2);
            assert!(
                !suggestions
                    .iter()
                    .any(|s| s.candidate_id == shared::ai_candidate::PREDICTIVE),
                "reading={reading:?} must not be predictive"
            );
        }
        assert!(
            listener.accept().is_err(),
            "a rejected tail must not issue a model request"
        );
    }

    #[tokio::test]
    async fn jev_denied_request_never_waits_for_composition_lock() {
        use shared::proto::azookey_service_server::AzookeyService;
        let service = super::MyAzookeyService::default();
        let _guard = service.mutation_lock.lock().await;
        let error = tokio::time::timeout(
            std::time::Duration::from_secs(1),
            service.rerank_candidates(tonic::Request::new(
                shared::proto::RerankCandidatesRequest {
                    reading: "きしゃ".into(),
                    candidates: vec!["記者".into(), "汽車".into()],
                    allow_remote: true,
                    ..Default::default()
                },
            )),
        )
        .await
        .expect("reranking must not acquire the composition lock")
        .unwrap_err();
        assert_eq!(error.code(), tonic::Code::Unauthenticated);
    }

    #[test]
    fn jev_requires_explicit_permission_and_bounded_snapshot() {
        let mut request = shared::proto::RerankCandidatesRequest {
            reading: "きしゃ".into(),
            context: String::new(),
            candidates: vec!["記者".into(), "汽車".into()],
            allow_remote: false,
            request_id: 1,
        };
        assert!(!super::valid_jev_request(&request));
        request.allow_remote = true;
        assert!(super::valid_jev_request(&request));
        request.candidates.resize(33, "貴社".into());
        assert!(!super::valid_jev_request(&request));
        request.candidates.truncate(2);
        request.context = "あ".repeat(2049);
        assert!(!super::valid_jev_request(&request));
    }

    use super::{
        decode_reconversion_readings, hiragana_boundary_fallback, merge_reconversion_suggestions,
        resolve_log_path_from_roots, retained_prepared_snapshot_count,
        validate_reconversion_surface, validate_shrink_offset, MyAzookeyService,
        MAX_RECONVERSION_SUGGESTIONS,
    };
    use shared::proto::Suggestion;
    use std::{ffi::OsStr, path::Path};

    #[tokio::test]
    async fn stateless_feedback_and_unsupported_reconversion_preserve_composition_owner() {
        use azookey_server::{PipeClient, TonicNamedPipeServer};
        use futures_core::Stream;
        use shared::proto::{
            azookey_service_server::AzookeyService, ClearTextRequest, SetContextRequest,
            StartReconversionRequest,
        };
        use std::{os::windows::io::IntoRawHandle, sync::Arc};
        use tokio::net::windows::named_pipe::NamedPipeClient;
        use tonic::{transport::server::Connected, Code, Request};

        fn request<T>(body: T, client: &Arc<PipeClient>) -> Request<T> {
            let mut request = Request::new(body);
            request.extensions_mut().insert(Arc::clone(client));
            request
        }

        let path = format!(
            r"\\.\pipe\LOCAL\azookey_reconversion_owner_test_{}_{}",
            std::process::id(),
            super::next_request_id()
        );
        let mut incoming = Box::pin(TonicNamedPipeServer::new(&path).unwrap());
        let first_handle = shared::open_named_pipe_client_handle(&path).unwrap();
        let _first_pipe =
            unsafe { NamedPipeClient::from_raw_handle(first_handle.into_raw_handle()) }.unwrap();
        let first_transport = std::future::poll_fn(|cx| incoming.as_mut().poll_next(cx))
            .await
            .unwrap()
            .unwrap();
        let second_handle = shared::open_named_pipe_client_handle(&path).unwrap();
        let _second_pipe =
            unsafe { NamedPipeClient::from_raw_handle(second_handle.into_raw_handle()) }.unwrap();
        let second_transport = std::future::poll_fn(|cx| incoming.as_mut().poll_next(cx))
            .await
            .unwrap()
            .unwrap();
        let first = first_transport.connect_info();
        let second = second_transport.connect_info();
        let service = MyAzookeyService::default();
        // Prediction acceptance can arrive after ClearText and after another
        // connection starts composing. It never needs the mutation lock.
        let feedback = shared::proto::CommitLearningCandidateRequest {
            candidate_id: shared::ai_candidate::PREDICTIVE,
            surface: "所有権の回帰確認です。".into(),
            ..Default::default()
        };
        {
            let mut owner = service.mutation_lock.lock().await;
            tokio::time::timeout(
                std::time::Duration::from_secs(1),
                service.commit_learning_candidate(request(feedback.clone(), &first)),
            )
            .await
            .expect("feedback must not wait for composition lock")
            .unwrap();
            owner
                .authorize(&request((), &second), true, || {})
                .expect("feedback must leave composition unclaimed");
        }
        service
            .commit_learning_candidate(request(feedback, &first))
            .await
            .expect("feedback must not interfere with a different owner");
        service
            .clear_text(request(ClearTextRequest::default(), &second))
            .await
            .unwrap();
        {
            let _guard = service.mutation_lock.lock().await;
            let reply = tokio::time::timeout(
                std::time::Duration::from_secs(1),
                service.rerank_candidates(request(
                    shared::proto::RerankCandidatesRequest::default(),
                    &first,
                )),
            )
            .await
            .expect("authenticated reranking must not acquire the composition lock")
            .expect("desktop pipe identity must be allowed")
            .into_inner();
            assert_eq!(reply.selected_index, None);
        }
        // Unassigned CJK: reading inference is unsupported without needing a
        // dictionary or model fixture. No candidate generation is performed.
        let unsupported = StartReconversionRequest {
            surface: "\u{2fa1f}".into(),
            ..Default::default()
        };
        let skipped = service
            .start_reconversion(request(unsupported.clone(), &first))
            .await
            .unwrap()
            .into_inner();
        assert!(!skipped.applied);
        service
            .clear_text(request(ClearTextRequest::default(), &second))
            .await
            .expect("a no-op must not block a different connection");

        service
            .set_context(request(SetContextRequest::default(), &first))
            .await
            .unwrap();
        let skipped = service
            .start_reconversion(request(unsupported, &first))
            .await
            .unwrap()
            .into_inner();
        assert!(!skipped.applied);
        assert_eq!(
            service
                .clear_text(request(ClearTextRequest::default(), &second))
                .await
                .unwrap_err()
                .code(),
            Code::PermissionDenied,
            "a no-op must preserve the existing owner"
        );
        service
            .clear_text(request(ClearTextRequest::default(), &first))
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn mutation_lock_keeps_replace_transaction_exclusive() {
        let service = MyAzookeyService::default();
        let first = service.mutation_lock.lock().await;

        let second = tokio::time::timeout(
            std::time::Duration::from_millis(5),
            service.mutation_lock.lock(),
        )
        .await;
        assert!(second.is_err(), "second mutation must wait for first");

        drop(first);
        let _second = tokio::time::timeout(
            std::time::Duration::from_millis(50),
            service.mutation_lock.lock(),
        )
        .await
        .expect("lock should become available");
    }

    #[test]
    fn shrink_offset_accepts_long_compositions_without_narrowing() {
        for offset in [127, 128, 129, 1024, i32::MAX] {
            assert_eq!(
                validate_shrink_offset(offset).expect("non-negative offset"),
                offset
            );
        }
    }

    #[test]
    fn shrink_offset_rejects_negative_values_before_swift_ffi() {
        let status = validate_shrink_offset(-1).expect_err("negative offset must fail");
        assert_eq!(status.code(), tonic::Code::InvalidArgument);
    }

    #[test]
    fn reconversion_surface_bounds_are_checked_before_swift_ffi() {
        assert_eq!(validate_reconversion_surface("日本語").unwrap(), 3);
        assert!(validate_reconversion_surface("").is_err());
        assert!(validate_reconversion_surface(&"あ".repeat(129)).is_err());
    }

    #[test]
    fn reconversion_reading_transport_preserves_embedded_newlines() {
        let readings = decode_reconversion_readings(r#"["キョウ\nニホン","コンニチ"]"#)
            .expect("valid reading JSON");

        assert_eq!(readings, ["キョウ\nニホン", "コンニチ"]);
        assert!(decode_reconversion_readings(r#"["valid",""]"#).is_err());
        assert!(decode_reconversion_readings("not-json").is_err());
    }

    #[test]
    fn reconversion_merges_ambiguous_readings_while_preserving_the_surface() {
        let groups = vec![
            vec![
                Suggestion {
                    text: "感じ".to_string(),
                    corresponding_count: 9,
                    candidate_id: 42,
                    ..Suggestion::default()
                },
                Suggestion {
                    text: "漢字".to_string(),
                    ..Suggestion::default()
                },
            ],
            vec![
                Suggestion {
                    text: "幹事".to_string(),
                    candidate_id: 84,
                    ..Suggestion::default()
                },
                Suggestion {
                    text: "感じ".to_string(),
                    ..Suggestion::default()
                },
            ],
        ];

        let suggestions = merge_reconversion_suggestions("漢字", "かんじ", &groups);
        let texts = suggestions
            .iter()
            .map(|suggestion| suggestion.text.as_str())
            .collect::<Vec<_>>();

        assert_eq!(texts, ["漢字", "感じ", "幹事"]);
        assert!(suggestions
            .iter()
            .all(|suggestion| suggestion.corresponding_count == 3));
        assert!(suggestions
            .iter()
            .all(|suggestion| suggestion.candidate_id == 0));
    }

    #[test]
    fn reconversion_candidate_merge_is_bounded() {
        let group = (0..(MAX_RECONVERSION_SUGGESTIONS + 20))
            .map(|index| Suggestion {
                text: format!("候補{index}"),
                ..Suggestion::default()
            })
            .collect::<Vec<_>>();

        let suggestions = merge_reconversion_suggestions("元表記", "もとひょうき", &[group]);

        assert_eq!(suggestions.len(), MAX_RECONVERSION_SUGGESTIONS);
        assert_eq!(suggestions[0].text, "元表記");
    }

    #[test]
    fn incomplete_move_to_last_preparation_rolls_back_all_server_snapshots() {
        assert_eq!(retained_prepared_snapshot_count(true, true, false, 16), 0);
        assert_eq!(retained_prepared_snapshot_count(false, true, true, 4), 0);
        assert_eq!(retained_prepared_snapshot_count(true, false, true, 4), 0);
        assert_eq!(retained_prepared_snapshot_count(true, true, true, 4), 4);
    }

    #[test]
    fn boundary_fallback_materializes_the_exact_hiragana_cursor_prefix() {
        let fallback =
            hiragana_boundary_fallback("あるていどながい", 4, 6).expect("valid non-empty boundary");

        assert_eq!(fallback.hiragana.as_deref(), Some("あるていどながい"));
        assert_eq!(fallback.suggestions.len(), 1);
        assert_eq!(fallback.suggestions[0].text, "あるてい");
        assert_eq!(fallback.suggestions[0].subtext, "どながい");
        assert_eq!(fallback.suggestions[0].corresponding_count, 6);
        assert_eq!(fallback.suggestions[0].candidate_id, 0);
    }

    #[test]
    fn boundary_fallback_rejects_empty_or_out_of_range_prefixes() {
        assert!(hiragana_boundary_fallback("ある", 0, 0).is_none());
        assert!(hiragana_boundary_fallback("ある", 3, 3).is_none());
        assert!(hiragana_boundary_fallback("ある", -1, 0).is_none());
    }

    #[test]
    fn server_logs_use_app_data() {
        let path = resolve_log_path_from_roots(
            Some(OsStr::new("/users/test/roaming")),
            Path::new("/users/test/temp"),
            "server.log",
        );

        assert_eq!(
            path,
            Path::new("/users/test/roaming/Azookey/logs/server.log")
        );
    }

    #[test]
    fn server_logs_fall_back_to_temp_instead_of_install_directory() {
        let install_directory = Path::new("/program-files/Azookey");
        let path = resolve_log_path_from_roots(
            Some(OsStr::new("")),
            Path::new("/users/test/temp"),
            "server.log",
        );

        assert_eq!(path, Path::new("/users/test/temp/Azookey/logs/server.log"));
        assert!(!path.starts_with(install_directory));
    }

    #[test]
    fn promote_full_composition_top_candidate_promotes_full_sentence_over_partial_prefix() {
        let mut suggestions = vec![
            Suggestion {
                text: "歯科医".to_string(),
                subtext: "にみてもらう".to_string(),
                corresponding_count: 6,
                candidate_id: 1,
            },
            Suggestion {
                text: "しかいにみてもらう".to_string(),
                subtext: String::new(),
                corresponding_count: 17,
                candidate_id: 2,
            },
            Suggestion {
                text: "歯科医に診てもらう".to_string(),
                subtext: String::new(),
                corresponding_count: 17,
                candidate_id: 3,
            },
        ];

        super::promote_full_composition_top_candidate(&mut suggestions, Some("しかいにみてもらう"));

        assert_eq!(suggestions[0].text, "歯科医に診てもらう");
        assert_eq!(suggestions[1].text, "歯科医");
        assert_eq!(suggestions[2].text, "しかいにみてもらう");
    }
}

#[cfg(test)]
mod translation_supersede_tests {
    use shared::proto::azookey_service_server::AzookeyService;
    use std::sync::atomic::Ordering;

    // A newer kind==3 request must cancel the previous worker's token so the
    // fresh baseline is not queued behind a superseded 10s generation.
    #[tokio::test]
    async fn newer_translation_request_cancels_the_previous_token() {
        super::TRANSLATION_ENABLED.store(true, Ordering::Relaxed);
        let first = std::sync::Arc::new(super::ai_assist::translation::TranslationCancel::new());
        {
            let mut current = super::TRANSLATION_CANCEL
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            *current = Some(std::sync::Arc::clone(&first));
        }
        let service = super::MyAzookeyService::default();
        // The request fails fast (no llama-server in tests) but the handler must
        // still have swapped in a fresh token and cancelled the old one.
        let _ = service
            .get_assist_candidates(tonic::Request::new(
                shared::proto::GetAssistCandidatesRequest {
                    baseline: "会議は明日です。".into(),
                    raw_input: "kaigi".into(),
                    input_count: 5,
                    kind: 3,
                    ..Default::default()
                },
            ))
            .await;
        assert!(
            first.is_cancelled_for_test(),
            "the previous request's token must be cancelled by a newer request"
        );
    }
}

#[cfg(test)]
mod conversion_continuation_tests {
    use super::{
        valid_conversion_continuation, MAX_CONVERSION_CONTINUATION_READING_COUNT,
        MAX_CONVERSION_CONTINUATION_SURFACE_COUNT,
    };

    #[test]
    fn accepts_kana_readings_with_matching_or_empty_context() {
        assert!(valid_conversion_continuation("きょう", "今日", ""));
        assert!(valid_conversion_continuation("きょう", "今日", "私は今日"));
        assert!(valid_conversion_continuation(
            "おーい",
            "おーい",
            "彼はおーい"
        ));
        assert!(valid_conversion_continuation("いすゞ", "いすゞ", ""));
        assert!(valid_conversion_continuation("ところゞ", "ところゞ", ""));
    }

    #[test]
    fn rejects_context_that_does_not_end_with_the_surface() {
        assert!(!valid_conversion_continuation("きょう", "今日", "明日は雨"));
        assert!(!valid_conversion_continuation("きょう", "今日", "今日は"));
    }

    #[test]
    fn rejects_empty_or_one_sided_continuations() {
        assert!(!valid_conversion_continuation("", "", ""));
        assert!(!valid_conversion_continuation("きょう", "", ""));
        assert!(!valid_conversion_continuation("", "今日", ""));
        assert!(!valid_conversion_continuation("きょう", "", "今日"));
        assert!(!valid_conversion_continuation("", "今日", "今日"));
    }

    #[test]
    fn rejects_readings_or_surfaces_over_the_cap() {
        let reading_at_cap = "あ".repeat(MAX_CONVERSION_CONTINUATION_READING_COUNT);
        let surface_at_cap = "あ".repeat(MAX_CONVERSION_CONTINUATION_SURFACE_COUNT);
        assert!(valid_conversion_continuation(
            &reading_at_cap,
            &surface_at_cap,
            ""
        ));
        let over_reading = "あ".repeat(MAX_CONVERSION_CONTINUATION_READING_COUNT + 1);
        let over_surface = "あ".repeat(MAX_CONVERSION_CONTINUATION_SURFACE_COUNT + 1);
        assert!(!valid_conversion_continuation(&over_reading, "今日", ""));
        assert!(!valid_conversion_continuation("きょう", &over_surface, ""));
    }

    #[test]
    fn rejects_non_hiragana_readings() {
        assert!(!valid_conversion_continuation("kyou", "今日", ""));
        assert!(!valid_conversion_continuation("キョウ", "今日", ""));
        assert!(!valid_conversion_continuation("今日", "今日", ""));
        assert!(!valid_conversion_continuation("きょう1", "今日", ""));
    }
}
