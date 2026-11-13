#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::{fs, sync::Arc};

use azookey_server::TonicNamedPipeServer;
use ipc::{WindowAction, WindowController, WindowService};
use shared::{
    proto::window_service_server::WindowServiceServer, ui_pipe_path,
    LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_DEFAULT,
};
use tao::dpi::{LogicalSize, PhysicalPosition};
use tao::platform::windows::{EventLoopBuilderExtWindows, WindowExtWindows};
use tao::{
    event::{Event, StartCause, WindowEvent},
    event_loop::{ControlFlow, EventLoopBuilder, EventLoopProxy},
};
use tokio::sync::{mpsc, Mutex};
use tokio::task::JoinHandle;
use tonic::transport::Server;
use uiaccess::prepare_uiaccess_token;
use utils::{
    get_candidate_window_position, get_candidate_window_position_with_ruby_clearance,
    get_ruby_window_size_for_rect, CandidateRect,
};
use windows::Win32::Foundation::{HWND, LPARAM, WPARAM};
use windows::Win32::UI::WindowsAndMessaging::{
    PostMessageW, SetForegroundWindow, SetWindowPos, ShowWindow, HWND_TOPMOST, SWP_NOACTIVATE,
    SWP_NOMOVE, SWP_NOSIZE, SW_HIDE, SW_SHOW, SW_SHOWNOACTIVATE,
};
use wry::WebContext;

pub mod candidate;
pub mod clipboard;
pub mod copy_source;
pub mod copy_translation;
pub mod data_paths;
pub mod indicator;
pub mod ipc;
pub mod ruby;
mod theme;
pub mod uiaccess;
pub mod utils;

const INDICATOR_WINDOW_LEFT_OFFSET: i32 = 45;
const CANDIDATE_WINDOW_MIN_WIDTH: u32 = 225;
const CANDIDATE_WINDOW_MAX_WIDTH: u32 = 640;
const CANDIDATE_WINDOW_BASE_WIDTH: u32 = 120;
const CANDIDATE_CHARACTER_WIDTH: u32 = 18;

#[derive(Clone, Copy, Debug)]
struct RubyMeasuredSize {
    width: f64,
    height: f64,
}

impl RubyMeasuredSize {
    fn new(width: f64, height: f64) -> Self {
        Self { width, height }
    }
}

fn place_candidate_windows(
    candidate_window: &tao::window::Window,
    indicator_window: &tao::window::Window,
    rect: CandidateRect,
    ruby_clearance: Option<(&tao::window::Window, i32)>,
) {
    let (x, y) = if let Some((ruby_window, vertical_adjustment)) = ruby_clearance {
        get_candidate_window_position_with_ruby_clearance(
            rect.top,
            rect.left,
            rect.bottom,
            rect.right,
            candidate_window,
            ruby_window,
            vertical_adjustment,
        )
    } else {
        get_candidate_window_position(
            rect.top,
            rect.left,
            rect.bottom,
            rect.right,
            candidate_window,
        )
    };
    candidate_window.set_outer_position(PhysicalPosition::new(x, y));
    indicator_window.set_outer_position(PhysicalPosition::new(
        (rect.left - INDICATOR_WINDOW_LEFT_OFFSET) as f64,
        rect.bottom as f64,
    ));
}

fn place_ruby_window(
    ruby_window: &tao::window::Window,
    rect: CandidateRect,
    vertical_adjustment: i32,
) {
    let (x, y) = utils::get_ruby_window_position(
        rect.top,
        rect.left,
        rect.bottom,
        rect.right,
        ruby_window,
        vertical_adjustment,
    );
    ruby_window.set_outer_position(PhysicalPosition::new(x, y));
}

fn set_ruby_window_measured_size(
    ruby_window: &tao::window::Window,
    rect: Option<CandidateRect>,
    measured_size: RubyMeasuredSize,
) {
    let size = if let Some(rect) = rect {
        get_ruby_window_size_for_rect(rect, measured_size.width, measured_size.height)
    } else {
        utils::RubyWindowSize::new(
            measured_logical_dimension(measured_size.width),
            measured_logical_dimension(measured_size.height),
        )
    };
    ruby_window.set_inner_size(LogicalSize::new(size.width, size.height));
}

fn measured_logical_dimension(value: f64) -> f64 {
    if value.is_finite() {
        value.ceil().max(1.0)
    } else {
        1.0
    }
}

fn set_and_place_ruby_window(
    ruby_window: &tao::window::Window,
    rect: CandidateRect,
    measured_size: RubyMeasuredSize,
    vertical_adjustment: i32,
) {
    set_ruby_window_measured_size(ruby_window, Some(rect), measured_size);
    place_ruby_window(ruby_window, rect, vertical_adjustment);
    set_ruby_window_measured_size(ruby_window, Some(rect), measured_size);
    place_ruby_window(ruby_window, rect, vertical_adjustment);
}

fn ruby_clearance<'a>(
    ruby_window: &'a tao::window::Window,
    reading: &str,
    candidate_list_visible: bool,
    _ruby_size_ready: bool,
    vertical_adjustment: i32,
) -> Option<(&'a tao::window::Window, i32)> {
    (candidate_list_visible && !reading.is_empty()).then_some((ruby_window, vertical_adjustment))
}

fn send_user_event(proxy: &EventLoopProxy<UserEvent>, event: UserEvent) {
    if let Err(error) = proxy.send_event(event) {
        eprintln!("Warning: Failed to send UI event: {error:?}");
    }
}

fn evaluate_script(webview: &wry::WebView, script: &str) {
    if let Err(error) = webview.evaluate_script(script) {
        eprintln!("Warning: Failed to evaluate WebView script: {error:?}");
    }
}

fn candidate_window_width(candidates: &[String]) -> u32 {
    let max_len = candidates
        .iter()
        .map(|s| s.chars().count())
        .max()
        .unwrap_or(0) as u32;

    CANDIDATE_WINDOW_BASE_WIDTH
        .saturating_add(max_len.saturating_mul(CANDIDATE_CHARACTER_WIDTH))
        .clamp(CANDIDATE_WINDOW_MIN_WIDTH, CANDIDATE_WINDOW_MAX_WIDTH)
}

fn logical_width_for_physical_width(physical_width: u32, scale_factor: f64) -> f64 {
    let scale_factor = if scale_factor.is_finite() && scale_factor > 0.0 {
        scale_factor
    } else {
        1.0
    };
    physical_width as f64 / scale_factor
}

fn set_candidate_window_width(candidate_window: &tao::window::Window, candidates: &[String]) {
    let size = candidate_window.inner_size();
    let scale_factor = candidate_window.scale_factor();
    let logical_height = logical_width_for_physical_width(size.height, scale_factor);
    candidate_window.set_inner_size(LogicalSize::new(
        candidate_window_width(candidates) as f64,
        logical_height,
    ));
}

fn update_candidate_list(
    candidate_webview: &wry::WebView,
    candidates: &[String],
    selected_index: Option<i32>,
) {
    match serde_json::to_string(candidates) {
        Ok(candidates) => {
            let selected_index = selected_index
                .map(|index| index.to_string())
                .unwrap_or_else(|| "null".to_string());
            evaluate_script(
                candidate_webview,
                &format!("updateCandidates({}, {})", candidates, selected_index),
            );
        }
        Err(error) => {
            eprintln!("Warning: Failed to serialize candidates: {error:?}");
        }
    }
}

fn update_ruby_reading(ruby_webview: &wry::WebView, reading: &str, request_id: u32) {
    match serde_json::to_string(reading) {
        Ok(reading) => {
            evaluate_script(
                ruby_webview,
                &format!("updateReading({}, {})", reading, request_id),
            );
        }
        Err(error) => {
            eprintln!("Warning: Failed to serialize reading: {error:?}");
        }
    }
}

fn next_ruby_size_request_id(current_request_id: &mut u32) -> u32 {
    *current_request_id = (*current_request_id).wrapping_add(1);
    if *current_request_id == 0 {
        *current_request_id = 1;
    }

    *current_request_id
}

fn show_ruby_window_if_ready(
    ruby_window: &tao::window::Window,
    ruby_window_shown: &mut bool,
    window_visible: bool,
    reading: &str,
    _ruby_size_ready: bool,
    has_candidate_rect: bool,
) {
    // ruby_size_ready が未完了でも、非表示ウィンドウでのrequestAnimationFrameブロックを防ぐため
    // window_visible かつ has_candidate_rect かつ reading があれば確実に表示する
    if window_visible && has_candidate_rect && !reading.is_empty() {
        set_window_shown_no_activate(ruby_window, ruby_window_shown);
    }
}

fn candidate_list_should_show(
    visible: bool,
    list_visible: bool,
    ready: bool,
    candidates: &[String],
) -> bool {
    visible && list_visible && ready && !candidates.is_empty()
}

fn set_candidate_list_visible(candidate_webview: &wry::WebView, visible: bool) {
    evaluate_script(
        candidate_webview,
        &format!("setCandidateListVisible({})", visible),
    );
}

fn update_indicator(indicator_webview: &wry::WebView, input_method: &str) {
    match serde_json::to_string(input_method) {
        Ok(input_method) => evaluate_script(
            indicator_webview,
            &format!("updateInputMethod({})", input_method),
        ),
        Err(error) => {
            eprintln!("Warning: Failed to serialize input method: {error:?}");
        }
    }
}

fn show_window_no_activate(window: &tao::window::Window) {
    let _ = unsafe {
        ShowWindow(
            HWND(window.hwnd() as *mut std::ffi::c_void),
            SW_SHOWNOACTIVATE,
        )
    };
}

fn hide_window(window: &tao::window::Window) {
    let _ = unsafe { ShowWindow(HWND(window.hwnd() as *mut std::ffi::c_void), SW_HIDE) };
}

fn set_window_shown_no_activate(window: &tao::window::Window, visible_state: &mut bool) {
    if !*visible_state {
        *visible_state = true;
        show_window_no_activate(window);
    }
}

fn set_window_hidden(window: &tao::window::Window, visible_state: &mut bool) {
    if *visible_state {
        *visible_state = false;
        hide_window(window);
    }
}

fn cancel_indicator_popup(
    task_guard: &Arc<Mutex<Option<JoinHandle<()>>>>,
    indicator_window: &tao::window::Window,
    indicator_window_shown: &mut bool,
    indicator_generation: &mut u64,
) {
    *indicator_generation = indicator_generation.wrapping_add(1);
    if let Ok(mut guard) = task_guard.try_lock() {
        if let Some(task) = guard.take() {
            task.abort();
        }
    } else {
        eprintln!("Warning: Failed to lock task_guard, skipping indicator timer cancel");
    }
    set_window_hidden(indicator_window, indicator_window_shown);
}

fn schedule_indicator_popup(
    task_guard: &Arc<Mutex<Option<JoinHandle<()>>>>,
    indicator_window: &tao::window::Window,
    indicator_window_shown: &mut bool,
    indicator_generation: &mut u64,
    proxy: &EventLoopProxy<UserEvent>,
) {
    *indicator_generation = indicator_generation.wrapping_add(1);
    let generation = *indicator_generation;
    set_window_shown_no_activate(indicator_window, indicator_window_shown);
    if let Ok(mut guard) = task_guard.try_lock() {
        if let Some(task) = guard.take() {
            task.abort();
        }
        let proxy = proxy.clone();
        *guard = Some(tokio::spawn(async move {
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
            send_user_event(&proxy, UserEvent::HideIndicatorIfGeneration(generation));
        }));
    }
}

fn should_render_candidate_list_visible(cached: &mut Option<bool>, next: bool) -> bool {
    if *cached == Some(next) {
        return false;
    }
    *cached = Some(next);
    true
}

fn should_render_reading(cached: &mut Option<String>, next: &str) -> bool {
    if cached.as_deref() == Some(next) {
        return false;
    }
    *cached = Some(next.to_string());
    true
}

fn candidate_list_update_diff(
    cached: &mut Option<(Vec<String>, Option<i32>)>,
    candidates: &[String],
    selected_index: Option<i32>,
) -> (bool, bool) {
    let width_changed = cached
        .as_ref()
        .is_none_or(|(prev_cands, _)| prev_cands.as_slice() != candidates);
    let list_changed = width_changed
        || cached
            .as_ref()
            .is_none_or(|(_, prev_sel)| *prev_sel != selected_index);
    if list_changed {
        *cached = Some((candidates.to_vec(), selected_index));
    }
    (width_changed, list_changed)
}

fn keep_windows_topmost(
    candidate_window: &tao::window::Window,
    ruby_window: &tao::window::Window,
    indicator_hwnd: isize,
) {
    unsafe {
        let _ = SetWindowPos(
            HWND(candidate_window.hwnd() as *mut std::ffi::c_void),
            HWND_TOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
        );

        let _ = SetWindowPos(
            HWND(ruby_window.hwnd() as *mut std::ffi::c_void),
            HWND_TOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
        );

        let _ = SetWindowPos(
            HWND(indicator_hwnd as *mut std::ffi::c_void),
            HWND_TOPMOST,
            0,
            0,
            0,
            0,
            SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
        );
    }
}

#[derive(Debug)]
pub enum UserEvent {
    UpdateTheme(shared::ColorTheme),
    CandidateReady,
    UpdateHeight(i32),
    UpdateRubySize {
        request_id: u32,
        width: f64,
        height: f64,
    },
    UpdateCandidates(String),
    UpdateSelection(i32),
    CandidateClicked(i32),
    /// The user clicked the AI assist pill in the ruby overlay.
    RubyAssistClicked,
    /// The user asked to delete one candidate's learning (context menu).
    CandidateForgetRequested(i32),
    UpdateInputMethod(String),
    HideIndicatorIfGeneration(u64),
    WindowAction(WindowAction),
    ClipboardUpdated,
    CopyTranslationShow {
        text: String,
        owner: isize,
        x: i32,
        y: i32,
    },
    CopyTranslationHide,
    ToggleClipboardWindow,
    ClipboardPaste(u64),
    ClipboardTogglePin(u64),
    ClipboardDelete(u64),
    ClipboardUpdateText(u64, String),
    ClipboardClose,
}

static COPY_POPUP_VISIBLE: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    shared::enable_redirection_guard().map_err(anyhow::Error::msg)?;

    // Obtain the UIAccess token so that the floating candidate / "原文" windows can be
    // drawn above elevated and fullscreen applications. This may re-launch the UI with a
    // UIAccess enabled token, in which case this process exits with
    // UIACCESS_HANDOFF_EXIT_CODE. Any failure here must stay non-fatal: the launcher
    // restarts a crashed UI, and a hard failure would turn that into a respawn loop
    // while the floating windows stay invisible.
    if let Err(error) = prepare_uiaccess_token() {
        eprintln!(
            "Warning: Failed to prepare the UIAccess token ({error:#}); continuing without UIAccess"
        );
    }

    let webview_data_directory = data_paths::ui_webview_data_directory();
    fs::create_dir_all(&webview_data_directory)?;
    let mut web_context = WebContext::new(Some(webview_data_directory));

    let event_loop = EventLoopBuilder::<UserEvent>::with_user_event()
        .with_any_thread(true)
        .build();

    // initialize window controller
    let (tx, mut rx) = mpsc::channel(32);
    let window_controller = WindowController::new(tx.clone());
    let grpc_service = WindowService {
        controller: window_controller.clone(),
    };

    // start grpc server
    let incoming = TonicNamedPipeServer::new(ui_pipe_path()?)?;
    tokio::spawn(async move {
        println!("WindowServer listening");
        Server::builder()
            .add_service(WindowServiceServer::new(grpc_service))
            .serve_with_incoming(incoming)
            .await
            .expect("gRPC server failed");
    });

    let event_loop_proxy = event_loop.create_proxy();
    let initial_theme = shared::AppConfig::read_cached()
        .unwrap_or_default()
        .appearance
        .ime_theme;
    let mut current_theme = initial_theme;
    let theme_script = theme::initialization_script(initial_theme);
    theme::watch_settings(event_loop_proxy.clone(), initial_theme)?;
    let task_guard: Arc<Mutex<Option<JoinHandle<()>>>> = Arc::new(Mutex::new(None));

    let proxy_clone = event_loop_proxy.clone();
    let candidate_window = candidate::create_candidate_window(&event_loop)?;
    let candidate_webview_builder = candidate::create_candidate_webview(&mut web_context)?;
    let candidate_webview = candidate_webview_builder
        .with_initialization_script(&theme_script)
        .with_devtools(cfg!(debug_assertions))
        .with_ipc_handler(move |message| {
            if let Ok(message) = serde_json::from_str::<serde_json::Value>(message.body()) {
                if let Some(type_value) = message.get("type") {
                    if type_value == "candidate-ready" {
                        send_user_event(&proxy_clone, UserEvent::CandidateReady);
                    } else if type_value == "resize" {
                        if let Some(height) = message.get("height") {
                            let height = height.as_f64().unwrap_or(0.0);
                            send_user_event(&proxy_clone, UserEvent::UpdateHeight(height as i32));
                        }
                    } else if type_value == "select" {
                        if let Some(index) = message.get("index").and_then(|value| value.as_i64()) {
                            if let Ok(index) = i32::try_from(index) {
                                send_user_event(&proxy_clone, UserEvent::CandidateClicked(index));
                            }
                        }
                    } else if type_value == "forget" {
                        if let Some(index) = message.get("index").and_then(|value| value.as_i64()) {
                            if let Ok(index) = i32::try_from(index) {
                                send_user_event(
                                    &proxy_clone,
                                    UserEvent::CandidateForgetRequested(index),
                                );
                            }
                        }
                    }
                }
            }
        })
        .build(&candidate_window)?;

    let indicator_window = indicator::create_indicator_window(&event_loop)?;
    let indicator_webview =
        indicator::create_indicator_webview(&indicator_window, &mut web_context, &theme_script)?;
    let ruby_window = ruby::create_ruby_window(&event_loop)?;
    let proxy_clone = event_loop_proxy.clone();
    let ruby_webview_builder = ruby::create_ruby_webview(&mut web_context)?;
    let ruby_webview = ruby_webview_builder
        .with_initialization_script(&theme_script)
        .with_devtools(cfg!(debug_assertions))
        .with_ipc_handler(move |message| {
            if let Ok(message) = serde_json::from_str::<serde_json::Value>(message.body()) {
                if message.get("type").and_then(|value| value.as_str()) == Some("ruby-resize") {
                    let request_id = message
                        .get("requestId")
                        .and_then(|value| value.as_u64())
                        .and_then(|value| u32::try_from(value).ok());
                    let width = message.get("width").and_then(|value| value.as_f64());
                    let height = message.get("height").and_then(|value| value.as_f64());
                    if let (Some(request_id), Some(width), Some(height)) =
                        (request_id, width, height)
                    {
                        send_user_event(
                            &proxy_clone,
                            UserEvent::UpdateRubySize {
                                request_id,
                                width,
                                height,
                            },
                        );
                    }
                } else if message.get("type").and_then(|value| value.as_str())
                    == Some("ruby-assist-click")
                {
                    send_user_event(&proxy_clone, UserEvent::RubyAssistClicked);
                }
            }
        })
        .build(&ruby_window)?;

    let clipboard_state = Arc::new(std::sync::Mutex::new(clipboard::ClipboardState::new()));
    let clipboard_window = clipboard::create_clipboard_window(&event_loop)?;
    let mut clipboard_webview: Option<wry::WebView> = None;
    let _clipboard_listener_thread =
        clipboard::start_clipboard_listener(clipboard_state.clone(), event_loop_proxy.clone());
    let clipboard_window_id = clipboard_window.id();
    let mut clipboard_window_visible = false;
    let mut clipboard_shown_at: Option<std::time::Instant> = None;

    let mut copy_popup: Option<selection_ui::PopupThread> = None;
    let mut copy_popup_visible = false;

    // handle window actions
    let proxy_clone = event_loop_proxy.clone();
    tokio::spawn(async move {
        while let Some(action) = rx.recv().await {
            send_user_event(&proxy_clone, UserEvent::WindowAction(action));
        }
    });

    let mut last_candidate_rect: Option<CandidateRect> = None;
    let mut candidate_ready = false;
    let mut latest_candidates: Vec<String> = Vec::new();
    let mut latest_selection = 0;
    let mut current_reading = String::new();
    let mut current_candidate_list_visible = true;
    let mut current_window_visible = false;
    let mut current_ruby_size_request_id = 0_u32;
    let mut current_ruby_size_ready = false;
    let mut current_ruby_measured_size: Option<RubyMeasuredSize> = None;
    let mut current_reading_vertical_adjustment =
        LIVE_CONVERSION_READING_VERTICAL_ADJUSTMENT_DEFAULT;
    let mut current_input_method: Option<String> = None;
    let mut current_engine_hwnd: u64 = 0;
    let mut candidate_window_shown = false;
    let mut ruby_window_shown = false;
    let mut indicator_window_shown = false;
    let mut indicator_generation = 0_u64;
    let mut last_rendered_candidates: Option<(Vec<String>, Option<i32>)> = None;
    let mut last_rendered_candidate_list_visible: Option<bool> = None;
    let mut last_rendered_reading: Option<String> = None;
    let mut last_rendered_selection: Option<i32> = None;

    event_loop.run(move |event, _, control_flow| {
        *control_flow = ControlFlow::Wait;

        let indicator_hwnd = indicator_window.hwnd();

        match event {
            Event::NewEvents(StartCause::Init) => {}
            Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } => *control_flow = ControlFlow::Exit,
            Event::WindowEvent {
                window_id,
                event: WindowEvent::Focused(false),
                ..
            } if window_id == clipboard_window_id => {
                if clipboard_window_visible {
                    if let Some(shown_at) = clipboard_shown_at {
                        if shown_at.elapsed().as_millis() > 150 {
                            let hwnd = clipboard_window.hwnd() as *mut std::ffi::c_void;
                            unsafe {
                                let _ = ShowWindow(HWND(hwnd), SW_HIDE);
                            }
                            clipboard_window_visible = false;
                            clipboard_shown_at = None;
                        }
                    }
                }
            }
            Event::UserEvent(script) => match script {
                UserEvent::UpdateTheme(theme) => {
                    current_theme = theme;
                    let script = format!(
                        "window.setColorTheme({})",
                        serde_json::to_string(&theme).expect("theme is serializable")
                    );
                    for webview in [&candidate_webview, &ruby_webview, &indicator_webview] {
                        evaluate_script(webview, &script);
                    }
                    if let Some(ref clipboard_webview) = clipboard_webview {
                        evaluate_script(clipboard_webview, &script);
                    }
                }
                UserEvent::CandidateReady => {
                    candidate_ready = true;
                    set_candidate_list_visible(&candidate_webview, current_candidate_list_visible);
                    last_rendered_candidate_list_visible = Some(current_candidate_list_visible);
                    set_candidate_window_width(&candidate_window, &latest_candidates);
                    update_candidate_list(&candidate_webview, &latest_candidates, Some(latest_selection));
                    last_rendered_candidates = Some((latest_candidates.clone(), Some(latest_selection)));
                    last_rendered_selection = Some(latest_selection);
                    if candidate_list_should_show(current_window_visible, current_candidate_list_visible, candidate_ready, &latest_candidates) {
                        set_window_shown_no_activate(&candidate_window, &mut candidate_window_shown);
                    }
                }
                UserEvent::UpdateCandidates(candidates) => {
                    if let Ok(values) = serde_json::from_str::<Vec<String>>(&candidates) {
                        latest_candidates = values.clone();
                        last_rendered_candidates = Some((values, None));
                    }
                    evaluate_script(
                        &candidate_webview,
                        &format!("updateCandidates({})", candidates),
                    );
                }
                UserEvent::UpdateSelection(index) => {
                    latest_selection = index;
                    last_rendered_selection = Some(index);
                    if let Some((_, ref mut sel)) = last_rendered_candidates {
                        *sel = Some(index);
                    }
                    evaluate_script(&candidate_webview, &format!("updateSelection({})", index));
                }
                UserEvent::CandidateClicked(index) => {
                    // 候補クリック採用: 直近の更新元エンジン（変換中スレッド）へ通知する。
                    // 非表示中や範囲外のクリックは無視する。
                    if !current_window_visible
                        || current_engine_hwnd == 0
                        || index < 0
                        || (index as usize) >= latest_candidates.len()
                    {
                        return;
                    }
                    unsafe {
                        let _ = PostMessageW(
                            HWND(current_engine_hwnd as *mut std::ffi::c_void),
                            shared::ENGINE_CANDIDATE_SELECT_MESSAGE,
                            WPARAM(index as usize),
                            LPARAM(0),
                        );
                    }
                }
                UserEvent::RubyAssistClicked => {
                    if !current_window_visible || current_engine_hwnd == 0 {
                        return;
                    }
                    let assist_replacement = serde_json::from_str::<serde_json::Value>(
                        &current_reading,
                    )
                    .ok()
                    .and_then(|value| {
                        let assist = value.get("assist")?;
                        if assist.get("status").and_then(|v| v.as_str()) == Some("applied") {
                            return None;
                        }
                        assist
                            .get("replacement")
                            .and_then(|v| v.as_str())
                            .map(|s| s.to_string())
                    });
                    if let Some(replacement) = assist_replacement {
                        if let Some(index) =
                            latest_candidates.iter().position(|c| *c == replacement)
                        {
                            unsafe {
                                let _ = PostMessageW(
                                    HWND(current_engine_hwnd as *mut std::ffi::c_void),
                                    shared::ENGINE_CANDIDATE_SELECT_MESSAGE,
                                    WPARAM(index),
                                    LPARAM(0),
                                );
                            }
                        }
                    }
                }
                UserEvent::CandidateForgetRequested(index) => {
                    // Same routing as a click: the engine message window owns the
                    // candidate id mapping, and only the owning thread may use it.
                    if !current_window_visible
                        || current_engine_hwnd == 0
                        || index < 0
                        || (index as usize) >= latest_candidates.len()
                    {
                        return;
                    }
                    unsafe {
                        let _ = PostMessageW(
                            HWND(current_engine_hwnd as *mut std::ffi::c_void),
                            shared::ENGINE_CANDIDATE_FORGET_MESSAGE,
                            WPARAM(index as usize),
                            LPARAM(0),
                        );
                    }
                }
                UserEvent::UpdateInputMethod(input_method) => {
                    update_indicator(&indicator_webview, &input_method);
                }
                UserEvent::HideIndicatorIfGeneration(generation) => {
                    if generation == indicator_generation {
                        set_window_hidden(&indicator_window, &mut indicator_window_shown);
                    }
                }
                UserEvent::UpdateHeight(height) => {
                    let width = candidate_window.inner_size().width;
                    let logical_width =
                        logical_width_for_physical_width(width, candidate_window.scale_factor());
                    candidate_window
                        .set_inner_size(LogicalSize::new(logical_width, height.max(0) as f64));
                    if let Some(rect) = last_candidate_rect {
                        place_candidate_windows(
                            &candidate_window,
                            &indicator_window,
                            rect,
                            ruby_clearance(
                                &ruby_window,
                                &current_reading,
                                current_candidate_list_visible,
                                current_ruby_size_ready,
                                current_reading_vertical_adjustment,
                            ),
                        );
                        if current_ruby_size_ready && !current_reading.is_empty() {
                            place_ruby_window(
                                &ruby_window,
                                rect,
                                current_reading_vertical_adjustment,
                            );
                        }
                    }
                }
                UserEvent::UpdateRubySize {
                    request_id,
                    width,
                    height,
                } => {
                    if request_id != current_ruby_size_request_id || current_reading.is_empty() {
                        return;
                    }

                    let measured_size = RubyMeasuredSize::new(width, height);
                    current_ruby_measured_size = Some(measured_size);
                    current_ruby_size_ready = true;

                    if let Some(rect) = last_candidate_rect {
                        set_and_place_ruby_window(
                            &ruby_window,
                            rect,
                            measured_size,
                            current_reading_vertical_adjustment,
                        );
                        place_candidate_windows(
                            &candidate_window,
                            &indicator_window,
                            rect,
                            ruby_clearance(
                                &ruby_window,
                                &current_reading,
                                current_candidate_list_visible,
                                current_ruby_size_ready,
                                current_reading_vertical_adjustment,
                            ),
                        );
                        place_ruby_window(&ruby_window, rect, current_reading_vertical_adjustment);
                    } else {
                        set_ruby_window_measured_size(&ruby_window, None, measured_size);
                    }

                    show_ruby_window_if_ready(
                        &ruby_window,
                        &mut ruby_window_shown,
                        current_window_visible,
                        &current_reading,
                        current_ruby_size_ready,
                        last_candidate_rect.is_some(),
                    );
                }
                UserEvent::ClipboardUpdated => {
                    if let Some(ref clipboard_webview) = clipboard_webview {
                        let items = {
                            let s = clipboard_state.lock().unwrap();
                            s.items.clone()
                        };
                        if let Ok(json) = serde_json::to_string(&items) {
                            evaluate_script(clipboard_webview, &format!("updateItems({})", json));
                        }
                    }
                }
                UserEvent::CopyTranslationShow { text, owner, x, y } => {
                    let (count, truncated) = copy_translation::count_text(&text);
                    if count == 0 {
                        return;
                    }
                    if copy_popup.is_none() {
                        copy_popup = selection_ui::PopupThread::start().ok();
                    }
                    if let Some(popup_thread) = copy_popup.as_ref() {
                        let anchor = windows::Win32::Foundation::RECT {
                            left: x,
                            top: y,
                            right: x,
                            bottom: y,
                        };
                        popup_thread.send(selection_ui::Command::Selection {
                            owner: owner as usize,
                            anchor,
                            count,
                            truncated,
                            text,
                        });
                        copy_popup_visible = true;
                        COPY_POPUP_VISIBLE.store(true, std::sync::atomic::Ordering::SeqCst);
                    }
                }
                UserEvent::CopyTranslationHide => {
                    if let Some(popup_thread) = copy_popup.as_ref() {
                        popup_thread.send(selection_ui::Command::Hide);
                    }
                    copy_popup_visible = false;
                    COPY_POPUP_VISIBLE.store(false, std::sync::atomic::Ordering::SeqCst);
                }
                UserEvent::ToggleClipboardWindow => {
                    let hwnd = clipboard_window.hwnd() as *mut std::ffi::c_void;
                    if clipboard_window_visible {
                        unsafe {
                            let _ = ShowWindow(HWND(hwnd), SW_HIDE);
                        }
                        clipboard_window_visible = false;
                    } else {
                        let preferred = if let Some(rect) = last_candidate_rect {
                            (rect.left, rect.bottom + 10)
                        } else {
                            let mut pt = windows::Win32::Foundation::POINT::default();
                            unsafe {
                                let _ = windows::Win32::UI::WindowsAndMessaging::GetCursorPos(&mut pt);
                            }
                            (pt.x, pt.y)
                        };
                        let size = clipboard_window.inner_size();
                        let pos = utils::clipboard_window_position(
                            preferred,
                            (size.width as i32, size.height as i32),
                            utils::work_area_for_point(preferred),
                        );
                        clipboard_window.set_outer_position(PhysicalPosition::new(
                            pos.0 as f64,
                            pos.1 as f64,
                        ));

                        let items = {
                            let mut s = clipboard_state.lock().unwrap();
                            // Retention is time-based, so entries can expire while the
                            // IME keeps running; prune before every reveal.
                            s.cleanup_expired(
                                shared::AppConfig::read_cached()
                                    .map(|config| config.clipboard.retention_hours)
                                    .unwrap_or(0),
                            );
                            s.items.clone()
                        };
                        let items_json = serde_json::to_string(&items).ok();
                        if let Some(ref webview) = clipboard_webview {
                            if let Some(ref json) = items_json {
                                evaluate_script(webview, &format!("updateItems({})", json));
                            }
                        } else {
                            let bootstrap_script = match items_json {
                                Some(ref json) => format!(
                                    "{}\nwindow.addEventListener('DOMContentLoaded', () => updateItems({}));",
                                    theme::initialization_script(current_theme),
                                    json
                                ),
                                None => theme::initialization_script(current_theme),
                            };
                            match clipboard::create_clipboard_webview(
                                &clipboard_window,
                                &mut web_context,
                                event_loop_proxy.clone(),
                                &bootstrap_script,
                            ) {
                                Ok(webview) => clipboard_webview = Some(webview),
                                Err(error) => {
                                    eprintln!("Warning: Failed to create clipboard WebView: {error:?}");
                                }
                            }
                        }

                        unsafe {
                            let _ = SetWindowPos(
                                HWND(hwnd),
                                HWND_TOPMOST,
                                0,
                                0,
                                0,
                                0,
                                SWP_NOMOVE | SWP_NOSIZE,
                            );
                            let _ = ShowWindow(HWND(hwnd), SW_SHOW);
                            let _ = SetForegroundWindow(HWND(hwnd));
                        }
                        clipboard_window_visible = true;
                        clipboard_shown_at = Some(std::time::Instant::now());
                    }
                }
                UserEvent::ClipboardPaste(id) => {
                    let hwnd = clipboard_window.hwnd() as *mut std::ffi::c_void;
                    unsafe {
                        let _ = ShowWindow(HWND(hwnd), SW_HIDE);
                    }
                    clipboard_window_visible = false;

                    let (target_hwnd_raw, item_opt) = {
                        let s = clipboard_state.lock().unwrap();
                        (s.target_hwnd_raw, s.items.iter().find(|i| i.id == id).cloned())
                    };

                    if let Some(item) = item_opt {
                        if let Some(bmp_b64) = &item.image_bmp_base64 {
                            clipboard::paste_image(bmp_b64, target_hwnd_raw);
                        } else if let Some(text) = &item.text {
                            clipboard::paste_text(text, target_hwnd_raw);
                        }
                    }
                }
                UserEvent::ClipboardTogglePin(id) => {
                    let items = {
                        let mut s = clipboard_state.lock().unwrap();
                        s.toggle_pin(id);
                        s.items.clone()
                    };
                    if let (Some(ref clipboard_webview), Ok(json)) =
                        (&clipboard_webview, serde_json::to_string(&items))
                    {
                        evaluate_script(clipboard_webview, &format!("updateItems({})", json));
                    }
                }
                UserEvent::ClipboardDelete(id) => {
                    let items = {
                        let mut s = clipboard_state.lock().unwrap();
                        s.delete_item(id);
                        s.items.clone()
                    };
                    if let (Some(ref clipboard_webview), Ok(json)) =
                        (&clipboard_webview, serde_json::to_string(&items))
                    {
                        evaluate_script(clipboard_webview, &format!("updateItems({})", json));
                    }
                }
                UserEvent::ClipboardUpdateText(id, new_text) => {
                    let items = {
                        let mut s = clipboard_state.lock().unwrap();
                        s.update_text(id, new_text);
                        s.items.clone()
                    };
                    if let (Some(ref clipboard_webview), Ok(json)) =
                        (&clipboard_webview, serde_json::to_string(&items))
                    {
                        evaluate_script(clipboard_webview, &format!("updateItems({})", json));
                    }
                }
                UserEvent::ClipboardClose => {
                    let hwnd = clipboard_window.hwnd() as *mut std::ffi::c_void;
                    unsafe {
                        let _ = ShowWindow(HWND(hwnd), SW_HIDE);
                    }
                    clipboard_window_visible = false;
                    clipboard_shown_at = None;
                }
                UserEvent::WindowAction(action) => {
                    match action {
                        WindowAction::Show => {
                            // if mode indicator is already shown, hide it
                            cancel_indicator_popup(
                                &task_guard,
                                &indicator_window,
                                &mut indicator_window_shown,
                                &mut indicator_generation,
                            );

                            current_window_visible = true;
                            if candidate_list_should_show(current_window_visible, current_candidate_list_visible, candidate_ready, &latest_candidates) {
                                set_window_shown_no_activate(&candidate_window, &mut candidate_window_shown);
                            } else {
                                set_window_hidden(&candidate_window, &mut candidate_window_shown);
                            }
                            show_ruby_window_if_ready(
                                &ruby_window,
                                &mut ruby_window_shown,
                                current_window_visible,
                                &current_reading,
                                current_ruby_size_ready,
                                last_candidate_rect.is_some(),
                            );
                        }
                        WindowAction::Hide => {
                            current_window_visible = false;
                            current_reading.clear();
                            current_ruby_size_ready = false;
                            current_ruby_measured_size = None;
                            if should_render_reading(&mut last_rendered_reading, "") {
                                let request_id =
                                    next_ruby_size_request_id(&mut current_ruby_size_request_id);
                                update_ruby_reading(&ruby_webview, "", request_id);
                            }
                            set_window_hidden(&candidate_window, &mut candidate_window_shown);
                            set_window_hidden(&ruby_window, &mut ruby_window_shown);
                        }
                        WindowAction::SetPosition {
                            top,
                            left,
                            bottom,
                            right,
                        } => {
                            let rect = CandidateRect::new(top, left, bottom, right);
                            last_candidate_rect = Some(rect);

                            if current_window_visible || indicator_window_shown || !current_reading.is_empty() {
                                keep_windows_topmost(&candidate_window, &ruby_window, indicator_hwnd);
                            }
                            if current_ruby_size_ready && !current_reading.is_empty() {
                                if let Some(measured_size) = current_ruby_measured_size {
                                    set_and_place_ruby_window(
                                        &ruby_window,
                                        rect,
                                        measured_size,
                                        current_reading_vertical_adjustment,
                                    );
                                }
                            }
                            place_candidate_windows(
                                &candidate_window,
                                &indicator_window,
                                rect,
                                ruby_clearance(
                                    &ruby_window,
                                    &current_reading,
                                    current_candidate_list_visible,
                                    current_ruby_size_ready,
                                    current_reading_vertical_adjustment,
                                ),
                            );
                            if current_ruby_size_ready && !current_reading.is_empty() {
                                place_ruby_window(
                                    &ruby_window,
                                    rect,
                                    current_reading_vertical_adjustment,
                                );
                            }
                            show_ruby_window_if_ready(
                                &ruby_window,
                                &mut ruby_window_shown,
                                current_window_visible,
                                &current_reading,
                                current_ruby_size_ready,
                                last_candidate_rect.is_some(),
                            );
                        }
                        WindowAction::SetCandidate { candidates } => {
                            latest_candidates = candidates.clone();
                            current_candidate_list_visible = true;
                            if should_render_candidate_list_visible(
                                &mut last_rendered_candidate_list_visible,
                                true,
                            ) {
                                set_candidate_list_visible(&candidate_webview, true);
                            }
                            let (width_changed, list_changed) = candidate_list_update_diff(
                                &mut last_rendered_candidates,
                                &candidates,
                                None,
                            );
                            if width_changed {
                                set_candidate_window_width(&candidate_window, &candidates);
                            }
                            if list_changed {
                                update_candidate_list(&candidate_webview, &candidates, None);
                                last_rendered_selection = None;
                            }
                            if let Some(rect) = last_candidate_rect {
                                place_candidate_windows(
                                    &candidate_window,
                                    &indicator_window,
                                    rect,
                                    ruby_clearance(
                                        &ruby_window,
                                        &current_reading,
                                        current_candidate_list_visible,
                                        current_ruby_size_ready,
                                        current_reading_vertical_adjustment,
                                    ),
                                );
                                if current_ruby_size_ready && !current_reading.is_empty() {
                                    place_ruby_window(
                                        &ruby_window,
                                        rect,
                                        current_reading_vertical_adjustment,
                                    );
                                }
                            }
                        }
                        WindowAction::SetSelection { index } => {
                            latest_selection = index;
                            send_user_event(&event_loop_proxy, UserEvent::UpdateSelection(index));
                        }
                        WindowAction::SetInputMode(input_method) => {
                            let mode_changed = current_input_method.as_ref() != Some(&input_method);
                            let was_already_set = current_input_method.is_some();
                            current_input_method = Some(input_method.clone());

                            if mode_changed {
                                update_indicator(&indicator_webview, &input_method);
                            }

                            if mode_changed && was_already_set {
                                schedule_indicator_popup(
                                    &task_guard,
                                    &indicator_window,
                                    &mut indicator_window_shown,
                                    &mut indicator_generation,
                                    &event_loop_proxy,
                                );
                            }
                        }
                        WindowAction::UpdateCandidateWindow {
                            visible,
                            position,
                            candidates,
                            selected_index,
                            input_mode,
                            reading,
                            candidate_list_visible,
                            reading_vertical_adjustment,
                            engine_hwnd,
                        } => {
                            if let Some(engine_hwnd) = engine_hwnd {
                                current_engine_hwnd = engine_hwnd;
                            }
                            if let Some(reading_vertical_adjustment) = reading_vertical_adjustment {
                                current_reading_vertical_adjustment = reading_vertical_adjustment;
                            }

                            // The visibility state has to be applied before anything that
                            // positions or reveals a window. The floating "原文" (live
                            // conversion reading) window is only shown while the candidate
                            // window is visible, and evaluating that condition against a
                            // stale flag kept the floating window hidden until the next
                            // unrelated update arrived.
                            if let Some(visible) = visible {
                                current_window_visible = visible;
                                if !visible {
                                    current_reading.clear();
                                    current_ruby_size_ready = false;
                                    current_ruby_measured_size = None;
                                    if should_render_reading(&mut last_rendered_reading, "") {
                                        let request_id = next_ruby_size_request_id(
                                            &mut current_ruby_size_request_id,
                                        );
                                        update_ruby_reading(&ruby_webview, "", request_id);
                                    }
                                }
                            }

                            if let Some(candidate_list_visible) = candidate_list_visible {
                                current_candidate_list_visible = candidate_list_visible;
                                if should_render_candidate_list_visible(
                                    &mut last_rendered_candidate_list_visible,
                                    candidate_list_visible,
                                ) {
                                    set_candidate_list_visible(
                                        &candidate_webview,
                                        candidate_list_visible,
                                    );
                                }
                            }

                            if let Some(ref reading) = reading {
                                current_reading = reading.clone();
                                if should_render_reading(&mut last_rendered_reading, reading) {
                                    let request_id = next_ruby_size_request_id(
                                        &mut current_ruby_size_request_id,
                                    );
                                    update_ruby_reading(&ruby_webview, reading, request_id);
                                }
                                if reading.is_empty() {
                                    current_ruby_size_ready = false;
                                    current_ruby_measured_size = None;
                                    set_window_hidden(&ruby_window, &mut ruby_window_shown);
                                }
                            }

                            let previous_rendered_selection = last_rendered_selection;
                            if let Some(index) = selected_index {
                                latest_selection = index;
                            }
                            if let Some(ref candidates) = candidates {
                                latest_candidates = candidates.clone();
                                let (width_changed, list_changed) = candidate_list_update_diff(
                                    &mut last_rendered_candidates,
                                    candidates,
                                    selected_index,
                                );
                                if width_changed {
                                    set_candidate_window_width(&candidate_window, candidates);
                                }
                                if list_changed {
                                    update_candidate_list(
                                        &candidate_webview,
                                        candidates,
                                        selected_index,
                                    );
                                    last_rendered_selection = selected_index;
                                }
                            }

                            if candidates.is_none() {
                                if let Some(index) = selected_index {
                                    if previous_rendered_selection != Some(index) {
                                        evaluate_script(
                                            &candidate_webview,
                                            &format!("updateSelection({})", index),
                                        );
                                        last_rendered_selection = Some(index);
                                        if let Some((_, ref mut sel)) = last_rendered_candidates {
                                            *sel = Some(index);
                                        }
                                    }
                                }
                            }

                            if let Some(position) = position {
                                let rect = CandidateRect::new(
                                    position.top,
                                    position.left,
                                    position.bottom,
                                    position.right,
                                );
                                last_candidate_rect = Some(rect);
                                if current_window_visible
                                    || indicator_window_shown
                                    || !current_reading.is_empty()
                                {
                                    keep_windows_topmost(
                                        &candidate_window,
                                        &ruby_window,
                                        indicator_hwnd,
                                    );
                                }
                                if !current_reading.is_empty() {
                                    let measured_size = current_ruby_measured_size.unwrap_or_else(|| {
                                        let char_count = current_reading.chars().count();
                                        RubyMeasuredSize::new((char_count as f64 * 16.0 + 80.0).max(140.0), 34.0)
                                    });
                                    set_and_place_ruby_window(
                                        &ruby_window,
                                        rect,
                                        measured_size,
                                        current_reading_vertical_adjustment,
                                    );
                                }
                                place_candidate_windows(
                                    &candidate_window,
                                    &indicator_window,
                                    rect,
                                    ruby_clearance(
                                        &ruby_window,
                                        &current_reading,
                                        current_candidate_list_visible,
                                        current_ruby_size_ready,
                                        current_reading_vertical_adjustment,
                                    ),
                                );
                                if !current_reading.is_empty() {
                                    place_ruby_window(
                                        &ruby_window,
                                        rect,
                                        current_reading_vertical_adjustment,
                                    );
                                }
                                show_ruby_window_if_ready(
                                    &ruby_window,
                                    &mut ruby_window_shown,
                                    current_window_visible,
                                    &current_reading,
                                    current_ruby_size_ready,
                                    last_candidate_rect.is_some(),
                                );
                            } else if candidates.is_some() || reading.is_some() {
                                if let Some(rect) = last_candidate_rect {
                                    place_candidate_windows(
                                        &candidate_window,
                                        &indicator_window,
                                        rect,
                                        ruby_clearance(
                                            &ruby_window,
                                            &current_reading,
                                            current_candidate_list_visible,
                                            current_ruby_size_ready,
                                            current_reading_vertical_adjustment,
                                        ),
                                    );
                                    if current_ruby_size_ready && !current_reading.is_empty() {
                                        place_ruby_window(
                                            &ruby_window,
                                            rect,
                                            current_reading_vertical_adjustment,
                                        );
                                    }
                                    show_ruby_window_if_ready(
                                        &ruby_window,
                                        &mut ruby_window_shown,
                                        current_window_visible,
                                        &current_reading,
                                        current_ruby_size_ready,
                                        last_candidate_rect.is_some(),
                                    );
                                }
                            }

                            if let Some(input_method) = input_mode {
                                let mode_changed = current_input_method.as_ref() != Some(&input_method);
                                let was_already_set = current_input_method.is_some();
                                current_input_method = Some(input_method.clone());

                                if mode_changed {
                                    update_indicator(&indicator_webview, &input_method);
                                }

                                if mode_changed && was_already_set {
                                    schedule_indicator_popup(
                                        &task_guard,
                                        &indicator_window,
                                        &mut indicator_window_shown,
                                        &mut indicator_generation,
                                        &event_loop_proxy,
                                    );
                                }
                            }

                            if let Some(visible) = visible {
                                if visible {
                                    cancel_indicator_popup(
                                        &task_guard,
                                        &indicator_window,
                                        &mut indicator_window_shown,
                                        &mut indicator_generation,
                                    );

                                    if candidate_list_should_show(current_window_visible, current_candidate_list_visible, candidate_ready, &latest_candidates) {
                                        set_window_shown_no_activate(&candidate_window, &mut candidate_window_shown);
                                    } else {
                                        set_window_hidden(&candidate_window, &mut candidate_window_shown);
                                    }

                                    show_ruby_window_if_ready(
                                        &ruby_window,
                                        &mut ruby_window_shown,
                                        current_window_visible,
                                        &current_reading,
                                        current_ruby_size_ready,
                                        last_candidate_rect.is_some(),
                                    );
                                } else {
                                    set_window_hidden(&candidate_window, &mut candidate_window_shown);
                                    set_window_hidden(&ruby_window, &mut ruby_window_shown);
                                }
                            }

                            if !candidate_list_should_show(current_window_visible, current_candidate_list_visible, candidate_ready, &latest_candidates) {
                                set_window_hidden(&candidate_window, &mut candidate_window_shown);
                            }

                            // Always re-evaluate the floating "原文" window once every state
                            // update has been applied, so that it becomes visible as soon as
                            // its measured size is known, regardless of the order in which
                            // the client sent the individual fields.
                            show_ruby_window_if_ready(
                                &ruby_window,
                                &mut ruby_window_shown,
                                current_window_visible,
                                &current_reading,
                                current_ruby_size_ready,
                                last_candidate_rect.is_some(),
                            );
                        }
                    }
                }
            },
            _ => (),
        }
    });
}

#[cfg(test)]
mod tests {
    use super::{
        candidate_window_width, logical_width_for_physical_width, CANDIDATE_WINDOW_MAX_WIDTH,
        CANDIDATE_WINDOW_MIN_WIDTH,
    };

    #[test]
    fn empty_or_hidden_candidate_list_never_reveals_a_frame() {
        let values = vec!["hello".to_string()];
        assert!(!super::candidate_list_should_show(
            true, false, true, &values
        ));
        assert!(!super::candidate_list_should_show(
            true, true, false, &values
        ));
        assert!(!super::candidate_list_should_show(true, true, true, &[]));
        assert!(super::candidate_list_should_show(true, true, true, &values));
        assert!(!super::candidate_list_should_show(
            false, true, true, &values
        ));
    }

    #[test]
    fn candidate_window_width_uses_minimum_for_short_candidates() {
        let candidates = vec!["候補".to_string(), "short".to_string()];

        assert_eq!(
            candidate_window_width(&candidates),
            CANDIDATE_WINDOW_MIN_WIDTH
        );
    }

    #[test]
    fn candidate_window_width_is_capped_for_unusually_long_candidates() {
        let candidates = vec!["候補".repeat(1_000)];

        assert_eq!(
            candidate_window_width(&candidates),
            CANDIDATE_WINDOW_MAX_WIDTH
        );
    }

    #[test]
    fn candidate_window_resize_preserves_physical_width_at_high_dpi() {
        assert_eq!(logical_width_for_physical_width(300, 1.0), 300.0);
        assert_eq!(logical_width_for_physical_width(300, 1.25), 240.0);
        assert_eq!(logical_width_for_physical_width(300, 1.5), 200.0);
        assert_eq!(logical_width_for_physical_width(300, 2.0), 150.0);
    }

    #[test]
    fn candidate_window_resize_uses_safe_scale_for_invalid_dpi() {
        assert_eq!(logical_width_for_physical_width(300, 0.0), 300.0);
        assert_eq!(logical_width_for_physical_width(300, f64::NAN), 300.0);
    }

    #[test]
    fn ui_render_caches_skip_unchanged_updates() {
        let mut list_visible = None;
        assert!(super::should_render_candidate_list_visible(
            &mut list_visible,
            true
        ));
        assert!(!super::should_render_candidate_list_visible(
            &mut list_visible,
            true
        ));
        assert!(super::should_render_candidate_list_visible(
            &mut list_visible,
            false
        ));

        let mut reading = None;
        assert!(super::should_render_reading(&mut reading, ""));
        assert!(!super::should_render_reading(&mut reading, ""));
        assert!(super::should_render_reading(&mut reading, "きょう"));
        assert!(!super::should_render_reading(&mut reading, "きょう"));

        let mut candidates = None;
        let first = vec!["今日".to_string(), "京".to_string()];
        assert_eq!(
            super::candidate_list_update_diff(&mut candidates, &first, Some(0)),
            (true, true)
        );
        assert_eq!(
            super::candidate_list_update_diff(&mut candidates, &first, Some(0)),
            (false, false)
        );
        assert_eq!(
            super::candidate_list_update_diff(&mut candidates, &first, Some(1)),
            (false, true)
        );
    }
}
