use anyhow::{Context, Result};
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::PathBuf,
    sync::atomic::{AtomicU32, Ordering},
    sync::{Arc, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};
use tao::{
    dpi::LogicalSize,
    event_loop::{EventLoop, EventLoopProxy},
    platform::windows::{WindowBuilderExtWindows, WindowExtWindows},
    window::{Window, WindowBuilder},
};
use windows::Win32::{
    Foundation::{HANDLE, HGLOBAL, HWND, LPARAM, LRESULT, POINT, WPARAM},
    System::{
        DataExchange::{
            AddClipboardFormatListener, CloseClipboard, EmptyClipboard, GetClipboardData,
            GetClipboardSequenceNumber, IsClipboardFormatAvailable, OpenClipboard,
            RegisterClipboardFormatW, RemoveClipboardFormatListener, SetClipboardData,
        },
        Memory::{GlobalAlloc, GlobalLock, GlobalSize, GlobalUnlock, GMEM_MOVEABLE},
    },
    UI::{
        Input::KeyboardAndMouse::{
            RegisterHotKey, SendInput, UnregisterHotKey, HOT_KEY_MODIFIERS, INPUT, INPUT_0,
            INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP, MOD_ALT, MOD_CONTROL, MOD_SHIFT,
            VIRTUAL_KEY, VK_CONTROL,
        },
        WindowsAndMessaging::{
            CreateWindowExW, DefWindowProcW, DestroyWindow, DispatchMessageW, GetCursorPos,
            GetForegroundWindow, GetMessageW, PostQuitMessage, RegisterClassW, SetForegroundWindow,
            SetWindowLongW, TranslateMessage, GWL_EXSTYLE, GWL_STYLE, MSG, WINDOW_EX_STYLE,
            WINDOW_STYLE, WM_CLIPBOARDUPDATE, WM_DESTROY, WM_HOTKEY, WNDCLASSW, WS_EX_TOOLWINDOW,
            WS_EX_TOPMOST, WS_POPUP,
        },
    },
};
use wry::{WebContext, WebView, WebViewBuilder};

use crate::{data_paths, UserEvent};

static LAST_SELF_CLIPBOARD_SEQUENCE: AtomicU32 = AtomicU32::new(0);

const CF_DIB: u32 = 8;
const CF_UNICODETEXT: u32 = 13;
const MAX_RECENT_ITEMS: usize = 30;
const HOTKEY_ID: i32 = 0x4342; // 'CB'
const VK_KEY_V: VIRTUAL_KEY = VIRTUAL_KEY(0x56);

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClipboardItem {
    pub id: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_bmp_base64: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub image_dims: Option<(u32, u32)>,
    pub timestamp: u64,
    pub pinned: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PinnedStore {
    pub items: Vec<ClipboardItem>,
}

pub struct ClipboardState {
    pub items: Vec<ClipboardItem>,
    pub next_id: u64,
    pub pinned_file: PathBuf,
    pub target_hwnd_raw: isize,
}

impl ClipboardState {
    pub fn new() -> Self {
        let base_dir = data_paths::ui_webview_data_directory()
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| PathBuf::from("."));
        let _ = fs::create_dir_all(&base_dir);
        let pinned_file = base_dir.join("clipboard_pinned.json");

        let mut items = Vec::new();
        if let Ok(content) = fs::read_to_string(&pinned_file) {
            if let Ok(store) = serde_json::from_str::<PinnedStore>(&content) {
                for mut item in store.items {
                    item.pinned = true;
                    items.push(item);
                }
            }
        }

        let max_id = items.iter().map(|i| i.id).max().unwrap_or(0);

        Self {
            items,
            next_id: max_id + 1,
            pinned_file,
            target_hwnd_raw: 0,
        }
    }

    fn now_millis() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }

    pub fn save_pinned(&self) {
        let pinned_items: Vec<ClipboardItem> =
            self.items.iter().filter(|i| i.pinned).cloned().collect();
        let store = PinnedStore {
            items: pinned_items,
        };
        if let Ok(json) = serde_json::to_string_pretty(&store) {
            let _ = fs::write(&self.pinned_file, json);
        }
    }

    pub fn add_text(&mut self, text: String) -> bool {
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return false;
        }

        // If top item is identical text, just update timestamp
        if let Some(first) = self.items.first_mut() {
            if first.text.as_deref() == Some(&text) {
                first.timestamp = Self::now_millis();
                return true;
            }
        }

        self.items
            .retain(|i| i.pinned || i.text.as_deref() != Some(&text));

        let id = self.next_id;
        self.next_id += 1;

        let item = ClipboardItem {
            id,
            text: Some(text),
            image_bmp_base64: None,
            image_dims: None,
            timestamp: Self::now_millis(),
            pinned: false,
        };

        let insert_idx = self
            .items
            .iter()
            .position(|i| !i.pinned)
            .unwrap_or(self.items.len());
        self.items.insert(insert_idx, item);

        while self.items.iter().filter(|i| !i.pinned).count() > MAX_RECENT_ITEMS {
            if let Some(idx) = self.items.iter().rposition(|i| !i.pinned) {
                self.items.remove(idx);
            } else {
                break;
            }
        }

        true
    }

    pub fn add_image(&mut self, bmp_base64: String, width: u32, height: u32) -> bool {
        let id = self.next_id;
        self.next_id += 1;

        let item = ClipboardItem {
            id,
            text: None,
            image_bmp_base64: Some(bmp_base64),
            image_dims: Some((width, height)),
            timestamp: Self::now_millis(),
            pinned: false,
        };

        let insert_idx = self
            .items
            .iter()
            .position(|i| !i.pinned)
            .unwrap_or(self.items.len());
        self.items.insert(insert_idx, item);

        while self.items.iter().filter(|i| !i.pinned).count() > MAX_RECENT_ITEMS {
            if let Some(idx) = self.items.iter().rposition(|i| !i.pinned) {
                self.items.remove(idx);
            } else {
                break;
            }
        }

        true
    }

    pub fn toggle_pin(&mut self, id: u64) {
        if let Some(idx) = self.items.iter().position(|i| i.id == id) {
            let mut item = self.items.remove(idx);
            item.pinned = !item.pinned;
            if item.pinned {
                self.items.insert(0, item);
            } else {
                let insert_idx = self
                    .items
                    .iter()
                    .position(|i| !i.pinned)
                    .unwrap_or(self.items.len());
                self.items.insert(insert_idx, item);
            }
            self.save_pinned();
        }
    }

    pub fn update_text(&mut self, id: u64, new_text: String) {
        if let Some(item) = self.items.iter_mut().find(|i| i.id == id) {
            item.text = Some(new_text);
            if item.pinned {
                self.save_pinned();
            }
        }
    }

    pub fn delete_item(&mut self, id: u64) {
        if let Some(idx) = self.items.iter().position(|i| i.id == id) {
            let item = self.items.remove(idx);
            if item.pinned {
                self.save_pinned();
            }
        }
    }

    pub fn cleanup_expired(&mut self, retention_hours: u32) {
        if retention_hours == 0 {
            return;
        }
        let cutoff = Self::now_millis().saturating_sub(retention_hours as u64 * 3600 * 1000);
        self.items.retain(|i| i.pinned || i.timestamp >= cutoff);
    }
}

pub fn parse_hotkey(trigger_key: &str) -> Option<(HOT_KEY_MODIFIERS, u32)> {
    match trigger_key {
        "ctrl_b" => Some((MOD_CONTROL, 0x42)),
        "ctrl_shift_v" => Some((MOD_CONTROL | MOD_SHIFT, 0x56)),
        "alt_v" => Some((MOD_ALT, 0x56)),
        "ctrl_q" => Some((MOD_CONTROL, 0x51)),
        _ => None,
    }
}

pub fn dib_to_bmp_bytes(dib_bytes: &[u8]) -> Option<(Vec<u8>, u32, u32)> {
    if dib_bytes.len() < 40 {
        return None;
    }
    let bi_size = u32::from_le_bytes(dib_bytes[0..4].try_into().ok()?);
    let bi_width = i32::from_le_bytes(dib_bytes[4..8].try_into().ok()?).abs() as u32;
    let bi_height = i32::from_le_bytes(dib_bytes[8..12].try_into().ok()?).abs() as u32;
    let bi_bit_count = u16::from_le_bytes(dib_bytes[14..16].try_into().ok()?);
    let bi_clr_used = if bi_size >= 40 && dib_bytes.len() >= 36 {
        u32::from_le_bytes(dib_bytes[32..36].try_into().ok()?)
    } else {
        0
    };

    let color_table_size = if bi_clr_used > 0 {
        bi_clr_used * 4
    } else if bi_bit_count <= 8 {
        (1 << bi_bit_count) * 4
    } else {
        0
    };

    let off_bits = 14 + bi_size + color_table_size;
    let file_size = 14 + dib_bytes.len() as u32;

    let mut bmp = Vec::with_capacity(file_size as usize);
    bmp.extend_from_slice(b"BM");
    bmp.extend_from_slice(&file_size.to_le_bytes());
    bmp.extend_from_slice(&0u32.to_le_bytes());
    bmp.extend_from_slice(&off_bits.to_le_bytes());
    bmp.extend_from_slice(dib_bytes);

    Some((bmp, bi_width, bi_height))
}

pub fn check_clipboard(state: &Arc<Mutex<ClipboardState>>) -> bool {
    unsafe {
        let fmt_exclude = RegisterClipboardFormatW(windows::core::w!(
            "ExcludeClipboardContentFromMonitorProcessing"
        ));
        let fmt_ignore = RegisterClipboardFormatW(windows::core::w!("Clipboard Viewer Ignore"));

        if fmt_exclude != 0 && IsClipboardFormatAvailable(fmt_exclude).is_ok() {
            return false;
        }
        if fmt_ignore != 0 && IsClipboardFormatAvailable(fmt_ignore).is_ok() {
            return false;
        }

        if OpenClipboard(HWND(std::ptr::null_mut())).is_err() {
            return false;
        }

        let mut updated = false;

        // 1. Try DIB (Image)
        if IsClipboardFormatAvailable(CF_DIB).is_ok() {
            if let Ok(handle) = GetClipboardData(CF_DIB) {
                let hmem = HGLOBAL(handle.0 as _);
                let size = GlobalSize(hmem);
                if size > 40 {
                    let ptr = GlobalLock(hmem) as *const u8;
                    if !ptr.is_null() {
                        let dib_slice = std::slice::from_raw_parts(ptr, size);
                        if let Some((bmp_bytes, w, h)) = dib_to_bmp_bytes(dib_slice) {
                            let b64 = base64::engine::general_purpose::STANDARD.encode(&bmp_bytes);
                            let mut s = state.lock().unwrap();
                            updated = s.add_image(b64, w, h);
                        }
                        let _ = GlobalUnlock(hmem);
                    }
                }
            }
        } else if IsClipboardFormatAvailable(CF_UNICODETEXT).is_ok() {
            // 2. Try Unicode Text
            if let Ok(handle) = GetClipboardData(CF_UNICODETEXT) {
                let hmem = HGLOBAL(handle.0 as _);
                let ptr = GlobalLock(hmem) as *const u16;
                if !ptr.is_null() {
                    let mut len = 0;
                    while *ptr.add(len) != 0 {
                        len += 1;
                    }
                    let slice = std::slice::from_raw_parts(ptr, len);
                    let text = String::from_utf16_lossy(slice);
                    let _ = GlobalUnlock(hmem);

                    let mut s = state.lock().unwrap();
                    updated = s.add_text(text);
                }
            }
        }

        let _ = CloseClipboard();
        updated
    }
}

pub fn paste_text(text: &str, target_hwnd_raw: isize) {
    unsafe {
        let target_hwnd = HWND(target_hwnd_raw as *mut std::ffi::c_void);
        if !target_hwnd.0.is_null() {
            let _ = SetForegroundWindow(target_hwnd);
        }
        std::thread::sleep(std::time::Duration::from_millis(30));

        if OpenClipboard(HWND(std::ptr::null_mut())).is_ok() {
            let _ = EmptyClipboard();
            let utf16: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
            let size = utf16.len() * 2;
            if let Ok(hmem) = GlobalAlloc(GMEM_MOVEABLE, size) {
                let ptr = GlobalLock(hmem) as *mut u16;
                if !ptr.is_null() {
                    std::ptr::copy_nonoverlapping(utf16.as_ptr(), ptr, utf16.len());
                    let _ = GlobalUnlock(hmem);
                    let _ = SetClipboardData(CF_UNICODETEXT, HANDLE(hmem.0 as _));
                }
            }
            let _ = CloseClipboard();
        }

        mark_self_clipboard_write();

        send_ctrl_v();
    }
}

pub fn paste_image(bmp_base64: &str, target_hwnd_raw: isize) {
    if let Ok(bmp_bytes) = base64::engine::general_purpose::STANDARD.decode(bmp_base64) {
        if bmp_bytes.len() > 14 && &bmp_bytes[0..2] == b"BM" {
            let dib_bytes = &bmp_bytes[14..];
            unsafe {
                let target_hwnd = HWND(target_hwnd_raw as *mut std::ffi::c_void);
                if !target_hwnd.0.is_null() {
                    let _ = SetForegroundWindow(target_hwnd);
                }
                std::thread::sleep(std::time::Duration::from_millis(30));

                if OpenClipboard(HWND(std::ptr::null_mut())).is_ok() {
                    let _ = EmptyClipboard();
                    if let Ok(hmem) = GlobalAlloc(GMEM_MOVEABLE, dib_bytes.len()) {
                        let ptr = GlobalLock(hmem) as *mut u8;
                        if !ptr.is_null() {
                            std::ptr::copy_nonoverlapping(dib_bytes.as_ptr(), ptr, dib_bytes.len());
                            let _ = GlobalUnlock(hmem);
                            let _ = SetClipboardData(CF_DIB, HANDLE(hmem.0 as _));
                        }
                    }
                    let _ = CloseClipboard();
                }

                mark_self_clipboard_write();

                send_ctrl_v();
            }
        }
    }
}

unsafe fn send_ctrl_v() {
    std::thread::sleep(std::time::Duration::from_millis(30));
    let inputs = [
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: VK_CONTROL,
                    wScan: 0,
                    dwFlags: windows::Win32::UI::Input::KeyboardAndMouse::KEYBD_EVENT_FLAGS(0),
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        },
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: VK_KEY_V,
                    wScan: 0,
                    dwFlags: windows::Win32::UI::Input::KeyboardAndMouse::KEYBD_EVENT_FLAGS(0),
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        },
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: VK_KEY_V,
                    wScan: 0,
                    dwFlags: KEYEVENTF_KEYUP,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        },
        INPUT {
            r#type: INPUT_KEYBOARD,
            Anonymous: INPUT_0 {
                ki: KEYBDINPUT {
                    wVk: VK_CONTROL,
                    wScan: 0,
                    dwFlags: KEYEVENTF_KEYUP,
                    time: 0,
                    dwExtraInfo: 0,
                },
            },
        },
    ];
    let _ = SendInput(&inputs, std::mem::size_of::<INPUT>() as i32);
}

pub fn read_clipboard_text() -> Option<String> {
    unsafe {
        let fmt_exclude = RegisterClipboardFormatW(windows::core::w!(
            "ExcludeClipboardContentFromMonitorProcessing"
        ));
        let fmt_ignore = RegisterClipboardFormatW(windows::core::w!("Clipboard Viewer Ignore"));
        if fmt_exclude != 0 && IsClipboardFormatAvailable(fmt_exclude).is_ok() {
            return None;
        }
        if fmt_ignore != 0 && IsClipboardFormatAvailable(fmt_ignore).is_ok() {
            return None;
        }
        if OpenClipboard(HWND(std::ptr::null_mut())).is_err() {
            return None;
        }
        let mut text = None;
        if IsClipboardFormatAvailable(CF_UNICODETEXT).is_ok() {
            if let Ok(handle) = GetClipboardData(CF_UNICODETEXT) {
                let hmem = HGLOBAL(handle.0 as _);
                let ptr = GlobalLock(hmem) as *const u16;
                if !ptr.is_null() {
                    let mut len = 0usize;
                    while *ptr.add(len) != 0 {
                        len += 1;
                        if len > 65_536 {
                            break;
                        }
                    }
                    let slice = std::slice::from_raw_parts(ptr, len);
                    text = Some(String::from_utf16_lossy(slice));
                    let _ = GlobalUnlock(hmem);
                }
            }
        }
        let _ = CloseClipboard();
        text
    }
}

pub fn mark_self_clipboard_write() {
    unsafe {
        LAST_SELF_CLIPBOARD_SEQUENCE.store(GetClipboardSequenceNumber(), Ordering::SeqCst);
    }
}

pub fn is_self_clipboard_write(seq_before: u32) -> bool {
    // seq_before is the sequence number captured before our write? We'll compare current to recorded.
    let _ = seq_before;
    let recorded = LAST_SELF_CLIPBOARD_SEQUENCE.load(Ordering::SeqCst);
    recorded != 0 && recorded == unsafe { GetClipboardSequenceNumber() }
}

pub fn current_clipboard_sequence() -> u32 {
    unsafe { GetClipboardSequenceNumber() }
}
pub fn set_clipboard_text(text: &str) {
    unsafe {
        if OpenClipboard(HWND(std::ptr::null_mut())).is_ok() {
            let _ = EmptyClipboard();
            let utf16: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
            let size = utf16.len() * 2;
            if let Ok(hmem) = GlobalAlloc(GMEM_MOVEABLE, size) {
                let ptr = GlobalLock(hmem) as *mut u16;
                if !ptr.is_null() {
                    std::ptr::copy_nonoverlapping(utf16.as_ptr(), ptr, utf16.len());
                    let _ = GlobalUnlock(hmem);
                    let _ = SetClipboardData(CF_UNICODETEXT, HANDLE(hmem.0 as _));
                }
            }
            let _ = CloseClipboard();
        }
        mark_self_clipboard_write();
    }
}

pub fn create_clipboard_window(event_loop: &EventLoop<UserEvent>) -> Result<Window> {
    let window = WindowBuilder::new()
        .with_focused(false)
        .with_visible(false)
        .with_undecorated_shadow(true)
        .with_transparent(true)
        .with_inner_size(LogicalSize::new(440.0, 380.0))
        .build(event_loop)
        .context("Failed to create clipboard window")?;

    let hwnd = window.hwnd() as *mut std::ffi::c_void;
    unsafe {
        let exnewstyle = WS_EX_TOOLWINDOW.0 | WS_EX_TOPMOST.0;
        SetWindowLongW(HWND(hwnd), GWL_EXSTYLE, exnewstyle as i32);
        let style = WS_POPUP.0;
        SetWindowLongW(HWND(hwnd), GWL_STYLE, style as i32);
    }

    Ok(window)
}

pub fn create_clipboard_webview(
    window: &Window,
    web_context: &mut WebContext,
    proxy: EventLoopProxy<UserEvent>,
) -> Result<WebView> {
    let html = clipboard_html();
    let webview = WebViewBuilder::with_web_context(web_context)
        .with_transparent(true)
        .with_html(html)
        .with_ipc_handler(move |message| {
            let msg = message.body();
            if let Ok(val) = serde_json::from_str::<serde_json::Value>(msg) {
                let action = val.get("action").and_then(|v| v.as_str()).unwrap_or("");
                match action {
                    "paste" => {
                        let id = val.get("id").and_then(|v| v.as_u64()).unwrap_or(0);
                        let _ = proxy.send_event(UserEvent::ClipboardPaste(id));
                    }
                    "toggle_pin" => {
                        let id = val.get("id").and_then(|v| v.as_u64()).unwrap_or(0);
                        let _ = proxy.send_event(UserEvent::ClipboardTogglePin(id));
                    }
                    "delete" => {
                        let id = val.get("id").and_then(|v| v.as_u64()).unwrap_or(0);
                        let _ = proxy.send_event(UserEvent::ClipboardDelete(id));
                    }
                    "update_text" => {
                        let id = val.get("id").and_then(|v| v.as_u64()).unwrap_or(0);
                        let text = val
                            .get("text")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let _ = proxy.send_event(UserEvent::ClipboardUpdateText(id, text));
                    }
                    "close" => {
                        let _ = proxy.send_event(UserEvent::ClipboardClose);
                    }
                    _ => {}
                }
            }
        })
        .build(window)
        .context("Failed to create clipboard webview")?;

    Ok(webview)
}

pub fn start_clipboard_listener(
    state: Arc<Mutex<ClipboardState>>,
    proxy: EventLoopProxy<UserEvent>,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || unsafe {
        let class_name = windows::core::w!("ZeroConvertClipboardListenerClass");
        let wndclass = WNDCLASSW {
            lpfnWndProc: Some(listener_wndproc),
            lpszClassName: class_name,
            ..Default::default()
        };
        let _ = RegisterClassW(&wndclass);

        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            class_name,
            windows::core::w!("ZeroConvertClipboardListener"),
            WINDOW_STYLE(0),
            0,
            0,
            0,
            0,
            HWND(std::ptr::null_mut()),
            None,
            None,
            None,
        );

        if hwnd.is_err() {
            return;
        }
        let hwnd = hwnd.unwrap();

        let _ = AddClipboardFormatListener(hwnd);

        let config = shared::AppConfig::read().unwrap_or_default();
        let retention_hours = config.clipboard.retention_hours;
        {
            let mut s = state.lock().unwrap();
            s.cleanup_expired(retention_hours);
        }

        if let Some((mods, vk)) = parse_hotkey(&config.clipboard.trigger_key) {
            let _ = RegisterHotKey(hwnd, HOTKEY_ID, mods, vk);
        }

        // Initial check
        if check_clipboard(&state) {
            let _ = proxy.send_event(UserEvent::ClipboardUpdated);
        }

        let mut msg = MSG::default();
        while GetMessageW(&mut msg, HWND(std::ptr::null_mut()), 0, 0).into() {
            if msg.message == WM_CLIPBOARDUPDATE {
                if check_clipboard(&state) {
                    let _ = proxy.send_event(UserEvent::ClipboardUpdated);
                }
                // Copy-translation candidate handling (best-effort, bounded).
                if !is_self_clipboard_write(0) {
                    let config = shared::AppConfig::read().unwrap_or_default();
                    if config.general.experimental_translation {
                        if let Some(text) = read_clipboard_text() {
                            if !text.trim().is_empty() {
                                let fg = GetForegroundWindow();
                                let mut pt = POINT::default();
                                let _ = GetCursorPos(&mut pt);
                                let source = crate::copy_source::classify_with_timeout(
                                    fg.0 as isize,
                                    pt,
                                    300,
                                );
                                if source == crate::copy_translation::CopySource::ReadOnlyDocument {
                                    let _ = proxy.send_event(UserEvent::CopyTranslationShow {
                                        text,
                                        owner: fg.0 as isize,
                                        x: pt.x,
                                        y: pt.y,
                                    });
                                } else {
                                    let _ = proxy.send_event(UserEvent::CopyTranslationHide);
                                }
                            }
                        }
                    }
                }
            } else if msg.message == WM_HOTKEY && msg.wParam.0 as i32 == HOTKEY_ID {
                let foreground = GetForegroundWindow();
                {
                    let mut s = state.lock().unwrap();
                    s.target_hwnd_raw = foreground.0 as isize;
                }
                let _ = proxy.send_event(UserEvent::ToggleClipboardWindow);
            }
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }

        let _ = UnregisterHotKey(hwnd, HOTKEY_ID);
        let _ = RemoveClipboardFormatListener(hwnd);
        let _ = DestroyWindow(hwnd);
    })
}

unsafe extern "system" fn listener_wndproc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_DESTROY => {
            PostQuitMessage(0);
            LRESULT(0)
        }
        _ => DefWindowProcW(hwnd, msg, wparam, lparam),
    }
}

fn clipboard_html() -> &'static str {
    r#"<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
* {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
    user-select: none;
}
html, body {
    width: 100%;
    height: 100%;
    overflow: hidden;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Yu Gothic UI", sans-serif;
    font-size: 13px;
    background: transparent;
}
.container {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    background: #FFFFFF;
    border: 1px solid rgba(0, 0, 0, 0.12);
    border-radius: 12px;
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.18);
    overflow: hidden;
    color: #1E293B;
}
.header {
    padding: 10px 14px;
    font-weight: 600;
    font-size: 12px;
    letter-spacing: 0.02em;
    color: #64748B;
    background: #F8FAFC;
    border-bottom: 1px solid rgba(0, 0, 0, 0.06);
    display: flex;
    align-items: center;
    justify-content: space-between;
}
.list {
    flex: 1;
    overflow-y: auto;
    padding: 6px;
    display: flex;
    flex-direction: column;
    gap: 3px;
}
.list::-webkit-scrollbar {
    width: 5px;
}
.list::-webkit-scrollbar-thumb {
    background: rgba(0, 0, 0, 0.16);
    border-radius: 4px;
}
.section-title {
    font-size: 11px;
    font-weight: 700;
    color: #94A3B8;
    padding: 6px 8px 3px 8px;
    text-transform: uppercase;
    letter-spacing: 0.04em;
}
.item {
    display: flex;
    align-items: center;
    padding: 7px 10px;
    border-radius: 7px;
    cursor: pointer;
    gap: 8px;
    transition: background 0.1s ease;
    font-size: 13px;
}
.item:hover {
    background: #F1F5F9;
}
.item.selected {
    background: #E2E8F0;
    color: #0F172A;
    font-weight: 600;
}
.badge {
    font-size: 11px;
    font-weight: 700;
    padding: 2px 5px;
    border-radius: 4px;
    flex-shrink: 0;
}
.badge-pin {
    background: #FEF3C7;
    color: #B45309;
}
.badge-img {
    background: #E0E7FF;
    color: #4338CA;
}
.index-num {
    font-size: 11px;
    font-weight: 600;
    color: #64748B;
    width: 16px;
    text-align: center;
    flex-shrink: 0;
}
.item.selected .index-num {
    color: #0F172A;
}
.content {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
.thumbnail {
    width: 38px;
    height: 24px;
    object-fit: cover;
    border-radius: 3px;
    border: 1px solid rgba(0,0,0,0.1);
    flex-shrink: 0;
}
.actions {
    display: flex;
    gap: 4px;
    opacity: 0;
    transition: opacity 0.12s ease;
}
.item:hover .actions, .item.selected .actions {
    opacity: 1;
}
.btn-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border-radius: 4px;
    border: none;
    background: transparent;
    color: #64748B;
    cursor: pointer;
}
.btn-icon:hover {
    background: rgba(0, 0, 0, 0.08);
    color: #0F172A;
}
.edit-input-row {
    display: flex;
    gap: 6px;
    padding: 4px 6px;
    background: #F8FAFC;
    border-radius: 6px;
    border: 1px solid #3B82F6;
}
.edit-input {
    flex: 1;
    font-size: 13px;
    padding: 4px 8px;
    border: 1px solid #CBD5E1;
    border-radius: 4px;
    outline: none;
}
.btn-save {
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 600;
    background: #2563EB;
    color: #FFFFFF;
    border: none;
    border-radius: 4px;
    cursor: pointer;
}
.btn-cancel {
    padding: 4px 8px;
    font-size: 12px;
    background: #E2E8F0;
    color: #475569;
    border: none;
    border-radius: 4px;
    cursor: pointer;
}
.footer {
    padding: 8px 12px;
    background: #F8FAFC;
    border-top: 1px solid rgba(0, 0, 0, 0.06);
    font-size: 11px;
    color: #64748B;
    display: flex;
    justify-content: space-between;
}
.key-guide {
    display: flex;
    gap: 8px;
}
.key {
    font-weight: 600;
    color: #475569;
}
.empty-msg {
    padding: 30px 10px;
    text-align: center;
    color: #94A3B8;
}

@media (prefers-color-scheme: dark) {
    .container {
        background: #181A20;
        border-color: rgba(255, 255, 255, 0.12);
        box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
        color: #F1F5F9;
    }
    .header {
        background: #13151B;
        border-color: rgba(255, 255, 255, 0.08);
        color: #94A3B8;
    }
    .list::-webkit-scrollbar-thumb {
        background: rgba(255, 255, 255, 0.16);
    }
    .item:hover {
        background: #262A34;
    }
    .item.selected {
        background: #333846;
        color: #FFFFFF;
    }
    .badge-pin {
        background: #78350F;
        color: #FDE68A;
    }
    .badge-img {
        background: #312E81;
        color: #C7D2FE;
    }
    .btn-icon {
        color: #94A3B8;
    }
    .btn-icon:hover {
        background: rgba(255, 255, 255, 0.12);
        color: #FFFFFF;
    }
    .edit-input-row {
        background: #1E2129;
        border-color: #60A5FA;
    }
    .edit-input {
        background: #13151B;
        border-color: #475569;
        color: #FFFFFF;
    }
    .btn-cancel {
        background: #334155;
        color: #E2E8F0;
    }
    .footer {
        background: #13151B;
        border-color: rgba(255, 255, 255, 0.08);
        color: #94A3B8;
    }
    .key {
        color: #CBD5E1;
    }
}
</style>
</head>
<body>
<div class="container">
    <div class="header">
        <span>クリップボード履歴</span>
        <span id="item-count" style="font-weight: normal; font-size: 11px;">0件</span>
    </div>
    <div class="list" id="item-list">
        <div class="empty-msg">履歴はありません</div>
    </div>
    <div class="footer">
        <div class="key-guide">
            <span><span class="key">Space / ↓</span> 次</span>
            <span><span class="key">Enter</span> 貼付</span>
            <span><span class="key">E</span> 編集</span>
            <span><span class="key">P</span> 固定</span>
            <span><span class="key">Del</span> 削除</span>
        </div>
        <div>
            <span><span class="key">Esc</span> 閉じる</span>
        </div>
    </div>
</div>

<script>
let items = [];
let selectedIndex = 0;
let editingId = null;

const pinIconSvg = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 3l1 1-4 4 3 7-4 1-5-5-5 5-1-1 5-5-5-5 1-4 7 3 4-4 1 1z"/></svg>';
const editIconSvg = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>';
const trashIconSvg = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>';

function render() {
    const listEl = document.getElementById("item-list");
    const countEl = document.getElementById("item-count");
    countEl.textContent = items.length + "件";

    if (items.length === 0) {
        listEl.innerHTML = '<div class="empty-msg">履歴はありません</div>';
        return;
    }

    if (selectedIndex < 0) selectedIndex = 0;
    if (selectedIndex >= items.length) selectedIndex = items.length - 1;

    let html = "";
    let inPinned = false;
    let inRecent = false;

    items.forEach((item, idx) => {
        if (item.pinned && !inPinned) {
            html += '<div class="section-title">固定</div>';
            inPinned = true;
        } else if (!item.pinned && inPinned && !inRecent) {
            html += '<div class="section-title">最近</div>';
            inRecent = true;
        } else if (!item.pinned && !inPinned && !inRecent && idx === 0) {
            html += '<div class="section-title">最近</div>';
            inRecent = true;
        }

        const isSelected = (idx === selectedIndex);

        if (editingId === item.id) {
            html += `
            <div class="edit-input-row" id="editing-row">
                <input type="text" class="edit-input" id="edit-field" value="${escapeHtml(item.text || '')}">
                <button class="btn-save" onclick="saveEdit(${item.id})">保存</button>
                <button class="btn-cancel" onclick="cancelEdit()">取消</button>
            </div>`;
            return;
        }

        const numDisplay = (idx < 9) ? (idx + 1) : "";
        let contentHtml = "";

        if (item.image_bmp_base64) {
            const dims = item.image_dims ? `${item.image_dims[0]}×${item.image_dims[1]}` : "";
            contentHtml = `
                <img class="thumbnail" src="data:image/bmp;base64,${item.image_bmp_base64}">
                <span class="badge badge-img">[画像]</span>
                <span class="content">${dims}</span>
            `;
        } else {
            const preview = (item.text || "").replace(/\r?\n/g, " ");
            contentHtml = `<span class="content">${escapeHtml(preview)}</span>`;
        }

        const pinBadge = item.pinned ? '<span class="badge badge-pin">[固定]</span>' : "";
        const editBtn = item.text ? `<button class="btn-icon" title="編集 (E)" onclick="startEdit(${item.id}, event)">${editIconSvg}</button>` : "";

        html += `
        <div class="item ${isSelected ? 'selected' : ''}" data-index="${idx}" onclick="selectItem(${idx}); pasteCurrent();">
            <span class="index-num">${numDisplay}</span>
            ${pinBadge}
            ${contentHtml}
            <div class="actions">
                ${editBtn}
                <button class="btn-icon" title="固定切替 (P)" onclick="togglePin(${item.id}, event)">${pinIconSvg}</button>
                <button class="btn-icon" title="削除 (Del)" onclick="deleteItem(${item.id}, event)">${trashIconSvg}</button>
            </div>
        </div>`;
    });

    listEl.innerHTML = html;

    if (editingId !== null) {
        const input = document.getElementById("edit-field");
        if (input) {
            input.focus();
            input.setSelectionRange(input.value.length, input.value.length);
        }
    } else {
        const selEl = listEl.querySelector(".item.selected");
        if (selEl) {
            selEl.scrollIntoView({ block: "nearest" });
        }
    }
}

function escapeHtml(str) {
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function selectItem(idx) {
    if (editingId !== null) return;
    selectedIndex = idx;
    render();
}

function updateItems(newItems) {
    items = newItems;
    if (selectedIndex >= items.length) selectedIndex = Math.max(0, items.length - 1);
    render();
}

function pasteCurrent() {
    if (editingId !== null) return;
    if (selectedIndex >= 0 && selectedIndex < items.length) {
        const item = items[selectedIndex];
        window.ipc.postMessage(JSON.stringify({ action: "paste", id: item.id }));
    }
}

function startEdit(id, event) {
    if (event) event.stopPropagation();
    editingId = id;
    render();
}

function cancelEdit() {
    editingId = null;
    render();
}

function saveEdit(id) {
    const input = document.getElementById("edit-field");
    if (input) {
        const newText = input.value;
        window.ipc.postMessage(JSON.stringify({ action: "update_text", id: id, text: newText }));
        const it = items.find(i => i.id === id);
        if (it) it.text = newText;
    }
    editingId = null;
    render();
}

function togglePin(id, event) {
    if (event) event.stopPropagation();
    window.ipc.postMessage(JSON.stringify({ action: "toggle_pin", id: id }));
}

function deleteItem(id, event) {
    if (event) event.stopPropagation();
    window.ipc.postMessage(JSON.stringify({ action: "delete", id: id }));
}

window.addEventListener("keydown", (e) => {
    if (editingId !== null) {
        if (e.key === "Enter") {
            e.preventDefault();
            saveEdit(editingId);
        } else if (e.key === "Escape") {
            e.preventDefault();
            cancelEdit();
        }
        return;
    }

    if (e.key === "ArrowDown" || (e.key === " " && !e.shiftKey)) {
        e.preventDefault();
        selectedIndex = (selectedIndex + 1) % Math.max(1, items.length);
        render();
    } else if (e.key === "ArrowUp" || (e.key === " " && e.shiftKey)) {
        e.preventDefault();
        selectedIndex = (selectedIndex - 1 + items.length) % Math.max(1, items.length);
        render();
    } else if (e.key === "Enter") {
        e.preventDefault();
        pasteCurrent();
    } else if (e.key === "Escape") {
        e.preventDefault();
        window.ipc.postMessage(JSON.stringify({ action: "close" }));
    } else if (e.key === "e" || e.key === "E") {
        if (selectedIndex >= 0 && selectedIndex < items.length) {
            const it = items[selectedIndex];
            if (it.text) {
                e.preventDefault();
                startEdit(it.id);
            }
        }
    } else if (e.key === "p" || e.key === "P") {
        if (selectedIndex >= 0 && selectedIndex < items.length) {
            e.preventDefault();
            togglePin(items[selectedIndex].id);
        }
    } else if (e.key === "Delete") {
        if (selectedIndex >= 0 && selectedIndex < items.length) {
            e.preventDefault();
            deleteItem(items[selectedIndex].id);
        }
    } else if (e.key >= "1" && e.key <= "9") {
        const num = parseInt(e.key, 10) - 1;
        if (num < items.length) {
            e.preventDefault();
            selectedIndex = num;
            pasteCurrent();
        }
    }
});

window.addEventListener("blur", () => {
    setTimeout(() => {
        if (!document.hasFocus()) {
            window.ipc.postMessage(JSON.stringify({ action: "close" }));
        }
    }, 120);
});
</script>
</body>
</html>"#
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_clipboard_state_add_text_and_duplicate() {
        let mut state = ClipboardState {
            items: Vec::new(),
            next_id: 1,
            pinned_file: PathBuf::from("dummy_pinned.json"),
            target_hwnd_raw: 0,
        };

        assert!(state.add_text("テスト1".to_string()));
        assert_eq!(state.items.len(), 1);
        assert_eq!(state.items[0].text.as_deref(), Some("テスト1"));

        // Adding exact same text should not duplicate, only update
        assert!(state.add_text("テスト1".to_string()));
        assert_eq!(state.items.len(), 1);

        // Adding new text
        assert!(state.add_text("テスト2".to_string()));
        assert_eq!(state.items.len(), 2);
        assert_eq!(state.items[0].text.as_deref(), Some("テスト2"));
        assert_eq!(state.items[1].text.as_deref(), Some("テスト1"));
    }

    #[test]
    fn test_clipboard_state_pinning_and_ordering() {
        let mut state = ClipboardState {
            items: Vec::new(),
            next_id: 1,
            pinned_file: PathBuf::from("dummy_pinned.json"),
            target_hwnd_raw: 0,
        };

        state.add_text("通常1".to_string());
        state.add_text("通常2".to_string());
        let id_to_pin = state.items[1].id; // "通常1"

        state.toggle_pin(id_to_pin);

        // Pinned item must move to top
        assert!(state.items[0].pinned);
        assert_eq!(state.items[0].text.as_deref(), Some("通常1"));
        assert!(!state.items[1].pinned);
        assert_eq!(state.items[1].text.as_deref(), Some("通常2"));

        // Unpinning
        state.toggle_pin(id_to_pin);
        assert!(!state.items[0].pinned);
    }

    #[test]
    fn test_clipboard_state_update_text() {
        let mut state = ClipboardState {
            items: Vec::new(),
            next_id: 1,
            pinned_file: PathBuf::from("dummy_pinned.json"),
            target_hwnd_raw: 0,
        };

        state.add_text("修正前".to_string());
        let id = state.items[0].id;
        state.update_text(id, "修正後".to_string());

        assert_eq!(state.items[0].text.as_deref(), Some("修正後"));
    }

    #[test]
    fn test_clipboard_state_delete_item() {
        let mut state = ClipboardState {
            items: Vec::new(),
            next_id: 1,
            pinned_file: PathBuf::from("dummy_pinned.json"),
            target_hwnd_raw: 0,
        };

        state.add_text("アイテム1".to_string());
        state.add_text("アイテム2".to_string());
        let id_to_delete = state.items[0].id;

        state.delete_item(id_to_delete);
        assert_eq!(state.items.len(), 1);
        assert_eq!(state.items[0].text.as_deref(), Some("アイテム1"));
    }

    #[test]
    fn test_dib_to_bmp_bytes_invalid() {
        // Less than 40 bytes should return None
        assert!(dib_to_bmp_bytes(&[0u8; 30]).is_none());
    }

    #[test]
    fn test_clipboard_state_cleanup_expired() {
        let mut state = ClipboardState {
            items: Vec::new(),
            next_id: 1,
            pinned_file: PathBuf::from("dummy_pinned.json"),
            target_hwnd_raw: 0,
        };

        state.add_text("古い項目".to_string());
        // 古い項目のタイムスタンプを2時間前に設定
        state.items[0].timestamp = ClipboardState::now_millis() - 2 * 3600 * 1000 - 1000;
        state.add_text("新しい項目".to_string());

        // 1時間経過した項目を削除
        state.cleanup_expired(1);
        assert_eq!(state.items.len(), 1);
        assert_eq!(state.items[0].text.as_deref(), Some("新しい項目"));
    }

    #[test]
    fn test_parse_hotkey() {
        assert!(parse_hotkey("ctrl_b").is_some());
        assert!(parse_hotkey("ctrl_shift_v").is_some());
        assert!(parse_hotkey("alt_v").is_some());
        assert!(parse_hotkey("ctrl_q").is_some());
        assert!(parse_hotkey("disabled").is_none());
    }
}
