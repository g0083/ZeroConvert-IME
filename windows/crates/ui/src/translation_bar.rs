//! On-demand translation bar UI.
//!
//! A floating Gboard-like bar positioned right above/below the caret showing:
//! - Currently typed Japanese
//! - Real-time English translation
//! - Shortcut guides (Enter: Commit, Esc: Cancel)
//!
//! Configured with WS_EX_NOACTIVATE so focus remains on the target application,
//! allowing TSF to directly stream the translated text into the app in real-time.
use anyhow::{Context, Result};
use tao::dpi::LogicalSize;
use tao::event_loop::EventLoop;
use tao::platform::windows::{WindowBuilderExtWindows, WindowExtWindows};
use tao::window::{Window, WindowBuilder};
use windows::Win32::Foundation::HWND;
use windows::Win32::UI::WindowsAndMessaging::{
    SetWindowLongW, GWL_EXSTYLE, GWL_STYLE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
    WS_POPUP,
};
use wry::{WebContext, WebView, WebViewBuilder};

pub fn create_translation_window(event_loop: &EventLoop<crate::UserEvent>) -> Result<Window> {
    let window = WindowBuilder::new()
        .with_decorations(false)
        .with_title("ZeroConvert Translation")
        .with_focused(false)
        .with_visible(false)
        .with_undecorated_shadow(true)
        .with_transparent(true)
        .with_inner_size(LogicalSize::new(420.0, 108.0))
        .build(event_loop)
        .context("Failed to create translation window")?;

    let hwnd = window.hwnd() as *mut std::ffi::c_void;
    unsafe {
        let exnewstyle = WS_EX_TOOLWINDOW.0 | WS_EX_TOPMOST.0 | WS_EX_NOACTIVATE.0;
        SetWindowLongW(HWND(hwnd), GWL_EXSTYLE, exnewstyle as i32);
        let style = WS_POPUP.0;
        SetWindowLongW(HWND(hwnd), GWL_STYLE, style as i32);
    }
    Ok(window)
}

pub fn create_translation_webview(
    window: &Window,
    web_context: &mut WebContext,
    theme_script: &str,
) -> Result<WebView> {
    let html = include_str!("translation_bar.html");

    let webview = WebViewBuilder::with_web_context(web_context)
        .with_transparent(true)
        .with_initialization_script(theme_script)
        .with_html(html)
        .build(window)
        .context("Failed to build translation webview")?;

    Ok(webview)
}
