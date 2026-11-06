//! Selection UI is isolated from the target application's TSF/message thread.
use super::popup_position;
use anyhow::Result;
use shared::translation_session::TranslationSessionGuard;
use std::{
    sync::{
        atomic::{AtomicUsize, Ordering},
        mpsc, Arc,
    },
    time::{Duration, Instant},
};
use windows::{
    core::w,
    Win32::{
        Foundation::{COLORREF, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM},
        Graphics::Gdi::*,
        UI::{
            HiDpi::GetDpiForWindow,
            Input::KeyboardAndMouse::{GetAsyncKeyState, VK_LBUTTON, VK_MBUTTON, VK_RBUTTON},
            WindowsAndMessaging::*,
        },
    },
};

#[derive(Debug)]
pub(super) enum Command {
    Selection {
        owner: usize,
        anchor: RECT,
        count: usize,
        truncated: bool,
        text: String,
    },
    Hide,
    Translate,
}
#[derive(Debug)]
pub(super) struct PopupThread {
    sender: mpsc::SyncSender<Command>,
}
impl PopupThread {
    pub fn start() -> Result<Self> {
        // A detached popup/network thread may outlive TSF deactivation. Keep its
        // code mapped until the input process exits; never execute an unloaded DLL.
        unsafe {
            use windows::Win32::System::LibraryLoader::{
                GetModuleHandleExW, GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
                GET_MODULE_HANDLE_EX_FLAG_PIN,
            };
            let mut module = Default::default();
            GetModuleHandleExW(
                GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN,
                windows::core::PCWSTR(run as *const () as *const u16),
                &mut module,
            )?;
        }
        let (sender, receiver) = mpsc::sync_channel(32);
        std::thread::Builder::new()
            .name("ime-selection-ui".into())
            .spawn(move || unsafe { run(receiver) })?;
        Ok(Self { sender })
    }
    pub fn send(&self, command: Command) {
        let _ = self.sender.try_send(command);
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    Loading,
    Translating,
    Done,
    Error,
}
struct Update {
    generation: usize,
    phase: Phase,
    text: String,
}
struct View {
    selection: HWND,
    result: HWND,
    owner: HWND,
    anchor: RECT,
    count: String,
    source: String,
    result_source: String,
    output: String,
    phase: Phase,
    font: HFONT,
    small: HFONT,
    scale: i32,
    enabled: bool,
    colors: [COLORREF; 6],
    generation: Arc<AtomicUsize>,
    guard: Option<TranslationSessionGuard>,
    action: u8,
    updates: mpsc::Sender<Update>,
}
impl View {
    fn px(&self, value: i32) -> i32 {
        value * self.scale / 96
    }
    unsafe fn configure(&mut self) {
        let config = shared::AppConfig::read().unwrap_or_default();
        self.enabled = config.general.experimental_translation;
        let id = serde_json::to_value(config.appearance.ime_theme).unwrap_or_default();
        let themes: serde_json::Value =
            serde_json::from_str(include_str!("../../../shared/themes.json")).unwrap();
        if let Some(theme) = themes
            .as_array()
            .and_then(|list| list.iter().find(|theme| theme["id"] == id))
        {
            for (i, key) in ["background", "surface", "text", "muted", "accent", "border"]
                .iter()
                .enumerate()
            {
                if let Some(hex) = theme[key]
                    .as_str()
                    .and_then(|v| u32::from_str_radix(v.trim_start_matches('#'), 16).ok())
                {
                    self.colors[i] =
                        COLORREF(((hex & 255) << 16) | (hex & 0xff00) | ((hex >> 16) & 255));
                }
            }
        }
        self.scale = GetDpiForWindow(self.owner).max(96) as i32;
        for font in [self.font, self.small] {
            if !font.is_invalid() {
                let _ = DeleteObject(font);
            }
        }
        self.font = CreateFontW(
            -self.px(14),
            0,
            0,
            0,
            400,
            0,
            0,
            0,
            DEFAULT_CHARSET.0 as u32,
            0,
            0,
            CLEARTYPE_QUALITY.0 as u32,
            0,
            w!("Segoe UI"),
        );
        self.small = CreateFontW(
            -self.px(12),
            0,
            0,
            0,
            400,
            0,
            0,
            0,
            DEFAULT_CHARSET.0 as u32,
            0,
            0,
            CLEARTYPE_QUALITY.0 as u32,
            0,
            w!("Segoe UI"),
        );
    }
    unsafe fn place(&self, hwnd: HWND, width: i32, height: i32) {
        let mut monitor = MONITORINFO {
            cbSize: std::mem::size_of::<MONITORINFO>() as u32,
            ..Default::default()
        };
        if GetMonitorInfoW(
            MonitorFromRect(&self.anchor, MONITOR_DEFAULTTONEAREST),
            &mut monitor,
        )
        .as_bool()
        {
            let (width, height) = (self.px(width), self.px(height));
            let (x, y) = popup_position(self.anchor, monitor.rcWork, width, height, self.px(8));
            let region = CreateRoundRectRgn(0, 0, width + 1, height + 1, self.px(18), self.px(18));
            if SetWindowRgn(hwnd, region, false) == 0 {
                let _ = DeleteObject(region);
            }
            let _ = SetWindowPos(
                hwnd,
                HWND_TOPMOST,
                x,
                y,
                width,
                height,
                SWP_NOACTIVATE | SWP_SHOWWINDOW | SWP_ASYNCWINDOWPOS,
            );
            let _ = InvalidateRect(hwnd, None, false);
        }
    }
    unsafe fn close_result(&mut self) {
        self.generation.fetch_add(1, Ordering::AcqRel);
        self.guard = None;
        let _ = ShowWindow(self.result, SW_HIDE);
    }
    unsafe fn translate(&mut self) {
        if !self.enabled || self.source.is_empty() {
            return;
        }
        if self.guard.is_some() && self.result_source == self.source {
            return;
        }

        self.close_result();
        self.result_source = self.source.clone();
        self.output.clear();
        self.phase = Phase::Loading;
        let _ = ShowWindow(self.selection, SW_HIDE);
        self.place(self.result, 420, 176);
        let source = self.source.split_whitespace().collect::<Vec<_>>().join(" ");
        if source.chars().count() > 512 || source.contains("<|") {
            self.phase = Phase::Error;
            self.output = "翻訳する範囲を512文字以内にしてください。".into();
            return;
        }
        match TranslationSessionGuard::start() {
            Ok(guard) => self.guard = Some(guard),
            Err(_) => {
                self.phase = Phase::Error;
                self.output = "翻訳サーバーを開始できませんでした。".into();
                return;
            }
        }
        let generation = self.generation.load(Ordering::Acquire);
        let version = self.generation.clone();
        let sender = self.updates.clone();
        std::thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(30);
            while version.load(Ordering::Acquire) == generation && Instant::now() < deadline {
                if shared::translation_http::ready() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(150));
            }
            if version.load(Ordering::Acquire) != generation {
                return;
            }
            if Instant::now() >= deadline {
                let _ = sender.send(Update {
                    generation,
                    phase: Phase::Error,
                    text: "モデルの読み込みが完了しませんでした。閉じて再試行してください。".into(),
                });
                return;
            }
            let _ = sender.send(Update {
                generation,
                phase: Phase::Translating,
                text: String::new(),
            });
            let japanese = source
                .chars()
                .any(|c| ('ぁ'..='ヿ').contains(&c) || ('一'..='鿿').contains(&c));
            let body =
                shared::translation_http::completion(&source, if japanese { "en" } else { "ja" });
            let result = shared::translation_http::request(
                shared::TRANSLATION_SERVER_PORT,
                Some(&body),
                Duration::from_secs(10),
            )
            .filter(|(status, value)| {
                *status == 200
                    && value["stopped_limit"] != true
                    && value["truncated"] != true
                    && value["stop_type"] != "limit"
            })
            .and_then(|(_, value)| value["content"].as_str().map(|text| text.trim().to_owned()))
            .filter(|text| !text.is_empty() && !text.contains("<|") && text.chars().count() <= 512);
            let (phase, text) = result.map(|text| (Phase::Done, text)).unwrap_or((
                Phase::Error,
                "翻訳できませんでした。閉じて再試行してください。".into(),
            ));
            let _ = sender.send(Update {
                generation,
                phase,
                text,
            });
        });
    }
    fn card_height(&self) -> i32 {
        let lines = self
            .output
            .chars()
            .map(|c| if c.is_ascii() { 1 } else { 2 })
            .sum::<usize>()
            .div_ceil(48);
        (176 + lines.saturating_sub(2) as i32 * 20).min(416)
    }
    unsafe fn paint(&self, hwnd: HWND) {
        let mut paint = PAINTSTRUCT::default();
        let dc = BeginPaint(hwnd, &mut paint);
        let mut rect = RECT::default();
        let _ = GetClientRect(hwnd, &mut rect);
        let brush = CreateSolidBrush(self.colors[0]);
        FillRect(dc, &rect, brush);
        let pen = CreatePen(PS_SOLID, self.px(1).max(1), self.colors[5]);
        let old_pen = SelectObject(dc, pen);
        let old_brush = SelectObject(dc, brush);
        let _ = RoundRect(
            dc,
            0,
            0,
            rect.right - 1,
            rect.bottom - 1,
            self.px(18),
            self.px(18),
        );
        SelectObject(dc, old_brush);
        SelectObject(dc, old_pen);
        let _ = DeleteObject(pen);
        let _ = DeleteObject(brush);
        let _ = SetBkMode(dc, TRANSPARENT);
        if hwnd == self.selection {
            self.text(dc, &self.count, (12, 0, 94, 38), 3, true);
            if self.enabled {
                let button = RECT {
                    left: self.px(100),
                    top: self.px(5),
                    right: self.px(216),
                    bottom: self.px(33),
                };
                let brush = CreateSolidBrush(self.colors[1]);
                FillRect(dc, &button, brush);
                let _ = DeleteObject(brush);
                self.text(dc, "翻訳  Ctrl+T", (108, 0, 210, 38), 4, true);
            }
        } else {
            let status = match self.phase {
                Phase::Loading => "モデルを読み込み中…",
                Phase::Translating => "翻訳中…",
                Phase::Done => "翻訳結果",
                Phase::Error => "翻訳できませんでした",
            };
            self.text(dc, status, (16, 14, 366, 40), 4, false);
            self.text(dc, "×", (384, 8, 414, 40), 3, true);
            self.text(dc, &self.result_source, (16, 48, 402, 76), 3, true);
            let text = if self.output.is_empty() {
                match self.phase {
                    Phase::Loading => {
                        "準備ができると自動で翻訳します。入力はそのまま続けられます。"
                    }
                    _ => "選択した文章を翻訳しています…",
                }
            } else {
                &self.output
            };
            let height = self.card_height();
            self.text(dc, text, (16, 84, 402, height - 48), 2, false);
            if self.phase == Phase::Done {
                let button = RECT {
                    left: self.px(312),
                    top: self.px(height - 40),
                    right: self.px(404),
                    bottom: self.px(height - 10),
                };
                let brush = CreateSolidBrush(self.colors[1]);
                FillRect(dc, &button, brush);
                let _ = DeleteObject(brush);
                self.text(dc, "コピー", (330, height - 40, 400, height - 10), 4, true);
            }
        }
        let _ = EndPaint(hwnd, &paint);
    }
    unsafe fn text(
        &self,
        dc: HDC,
        text: &str,
        area: (i32, i32, i32, i32),
        color: usize,
        small: bool,
    ) {
        let old = SelectObject(dc, if small { self.small } else { self.font });
        let _ = SetTextColor(dc, self.colors[color]);
        let mut rect = RECT {
            left: self.px(area.0),
            top: self.px(area.1),
            right: self.px(area.2),
            bottom: self.px(area.3),
        };
        let mut wide: Vec<_> = text.encode_utf16().collect();
        let flags = DT_NOPREFIX
            | if small {
                DT_SINGLELINE | DT_VCENTER | DT_END_ELLIPSIS
            } else {
                DT_WORDBREAK | DT_END_ELLIPSIS
            };
        DrawTextW(dc, &mut wide, &mut rect, flags);
        SelectObject(dc, old);
    }
    unsafe fn copy(&self) {
        use windows::Win32::{
            Foundation::HANDLE,
            System::{DataExchange::*, Memory::*},
        };
        if self.phase != Phase::Done || OpenClipboard(self.result).is_err() {
            return;
        }
        let wide: Vec<_> = self.output.encode_utf16().chain(Some(0)).collect();
        if let Ok(memory) = GlobalAlloc(GMEM_MOVEABLE, wide.len() * 2) {
            let ptr = GlobalLock(memory);
            if !ptr.is_null() {
                std::ptr::copy_nonoverlapping(wide.as_ptr(), ptr as *mut u16, wide.len());
                let _ = GlobalUnlock(memory);
                let _ = EmptyClipboard();
                if SetClipboardData(13, HANDLE(memory.0)).is_err() {
                    let _ = windows::Win32::Foundation::GlobalFree(memory);
                }
            } else {
                let _ = windows::Win32::Foundation::GlobalFree(memory);
            }
        }
        let _ = CloseClipboard();
    }
}

#[cfg(target_pointer_width = "64")]
unsafe fn get_user_data(hwnd: HWND) -> isize {
    GetWindowLongPtrW(hwnd, GWLP_USERDATA)
}
#[cfg(target_pointer_width = "32")]
unsafe fn get_user_data(hwnd: HWND) -> isize {
    GetWindowLongW(hwnd, GWLP_USERDATA) as isize
}
#[cfg(target_pointer_width = "64")]
unsafe fn set_user_data(hwnd: HWND, value: isize) {
    SetWindowLongPtrW(hwnd, GWLP_USERDATA, value);
}
#[cfg(target_pointer_width = "32")]
unsafe fn set_user_data(hwnd: HWND, value: isize) {
    SetWindowLongW(hwnd, GWLP_USERDATA, value as i32);
}

#[cfg(target_pointer_width = "64")]
unsafe fn set_owner(hwnd: HWND, owner: isize) {
    SetWindowLongPtrW(hwnd, GWLP_HWNDPARENT, owner);
}
#[cfg(target_pointer_width = "32")]
unsafe fn set_owner(hwnd: HWND, owner: isize) {
    SetWindowLongW(hwnd, GWLP_HWNDPARENT, owner as i32);
}

unsafe fn cursor_inside(hwnd: HWND) -> bool {
    let mut pt = POINT::default();
    let mut rect = RECT::default();
    let _ = GetCursorPos(&mut pt);
    let _ = GetWindowRect(hwnd, &mut rect);
    pt.x >= rect.left && pt.x <= rect.right && pt.y >= rect.top && pt.y <= rect.bottom
}

unsafe extern "system" fn wndproc(hwnd: HWND, msg: u32, wp: WPARAM, lp: LPARAM) -> LRESULT {
    let ptr = get_user_data(hwnd) as *mut View;
    if !ptr.is_null() {
        match msg {
            WM_PAINT => {
                (*ptr).paint(hwnd);
                return LRESULT(0);
            }
            WM_ERASEBKGND => return LRESULT(1),
            WM_MOUSEACTIVATE => return LRESULT(MA_NOACTIVATE as isize),
            WM_CLOSE => {
                (*ptr).action = 2;
                return LRESULT(0);
            }
            WM_LBUTTONUP => {
                let x = (lp.0 as u16 as i16 as i32) * 96 / (*ptr).scale;
                let y = ((lp.0 >> 16) as u16 as i16 as i32) * 96 / (*ptr).scale;
                (*ptr).action = if hwnd == (*ptr).selection && x >= 100 {
                    1
                } else if hwnd == (*ptr).result && x >= 378 && y < 44 {
                    2
                } else if hwnd == (*ptr).result && x >= 300 && y >= (*ptr).card_height() - 44 {
                    3
                } else {
                    0
                };
                return LRESULT(0);
            }
            _ => {}
        }
    }
    DefWindowProcW(hwnd, msg, wp, lp)
}

unsafe fn run(receiver: mpsc::Receiver<Command>) {
    let (send, updates) = mpsc::channel();
    let mut view = Box::new(View {
        selection: HWND::default(),
        result: HWND::default(),
        owner: HWND::default(),
        anchor: RECT::default(),
        count: String::new(),
        source: String::new(),
        result_source: String::new(),
        output: String::new(),
        phase: Phase::Loading,
        font: HFONT::default(),
        small: HFONT::default(),
        scale: 96,
        enabled: false,
        colors: [
            COLORREF(0x1c1918),
            COLORREF(0x292523),
            COLORREF(0xf4f2f1),
            COLORREF(0xbdb4b0),
            COLORREF(0xffb574),
            COLORREF(0x514945),
        ],
        generation: Arc::new(AtomicUsize::new(0)),
        guard: None,
        action: 0,
        updates: send,
    });
    let class = w!("ZeroConSelectionPopupV2");
    let wc = WNDCLASSW {
        lpfnWndProc: Some(wndproc),
        lpszClassName: class,
        hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
        ..Default::default()
    };
    let _ = RegisterClassW(&wc);
    for result in [false, true] {
        let Ok(hwnd) = CreateWindowExW(
            WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TOPMOST,
            class,
            if result {
                w!("ZeroCon 選択翻訳")
            } else {
                w!("ZeroCon 選択ツール")
            },
            WS_POPUP,
            0,
            0,
            0,
            0,
            None,
            None,
            None,
            None,
        ) else {
            return;
        };
        set_user_data(hwnd, &mut *view as *mut View as isize);
        if result {
            view.result = hwnd;
        } else {
            view.selection = hwnd;
        }
    }
    let mut was_mouse_down = false;
    let mut result_shown_at: Option<Instant> = None;
    loop {
        match receiver.recv_timeout(Duration::from_millis(16)) {
            Ok(command) => match command {
                Command::Selection {
                    owner,
                    anchor,
                    count,
                    truncated,
                    text,
                } => {
                    view.owner = HWND(owner as *mut _);
                    for hwnd in [view.selection, view.result] {
                        set_owner(hwnd, owner as isize);
                    }
                    view.anchor = anchor;
                    view.count = format!("{}文字{}", count, if truncated { "+" } else { "" });
                    view.source = text;
                    view.configure();
                    view.place(view.selection, if view.enabled { 224 } else { 104 }, 38);
                }
                Command::Hide => {
                    let _ = ShowWindow(view.selection, SW_HIDE);
                }
                Command::Translate => {
                    view.translate();
                    if IsWindowVisible(view.result).as_bool() {
                        result_shown_at = Some(Instant::now());
                    }
                }
            },
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
            Err(mpsc::RecvTimeoutError::Timeout) => {}
        }
        for _ in 0..128 {
            let mut msg = MSG::default();
            if !PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                break;
            }
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        match std::mem::take(&mut view.action) {
            1 => {
                view.translate();
                if IsWindowVisible(view.result).as_bool() {
                    result_shown_at = Some(Instant::now());
                }
            }
            2 => view.close_result(),
            3 => {
                view.copy();
                view.close_result();
            }
            _ => {}
        }
        for update in updates.try_iter().take(32) {
            if update.generation == view.generation.load(Ordering::Acquire) {
                view.phase = update.phase;
                view.output = update.text;
                if matches!(view.phase, Phase::Done | Phase::Error) {
                    view.guard = None;
                }
                view.place(view.result, 420, view.card_height());
            }
        }
        let is_mouse_down = (GetAsyncKeyState(VK_LBUTTON.0 as i32) as u16 & 0x8000 != 0)
            || (GetAsyncKeyState(VK_RBUTTON.0 as i32) as u16 & 0x8000 != 0)
            || (GetAsyncKeyState(VK_MBUTTON.0 as i32) as u16 & 0x8000 != 0);
        if IsWindowVisible(view.result).as_bool() {
            if is_mouse_down && !was_mouse_down {
                if let Some(shown_at) = result_shown_at {
                    if shown_at.elapsed() >= Duration::from_millis(150)
                        && !cursor_inside(view.result)
                        && !(IsWindowVisible(view.selection).as_bool()
                            && cursor_inside(view.selection))
                    {
                        view.close_result();
                    }
                }
            }
            was_mouse_down = is_mouse_down;
        } else {
            was_mouse_down = false;
            result_shown_at = None;
        }
        if !view.owner.is_invalid() && !IsWindow(view.owner).as_bool() {
            break;
        }
    }
    view.close_result();
    for hwnd in [view.selection, view.result] {
        set_user_data(hwnd, 0);
        let _ = DestroyWindow(hwnd);
    }
    for font in [view.font, view.small] {
        let _ = DeleteObject(font);
    }
}
