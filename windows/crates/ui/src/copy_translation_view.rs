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

pub fn create_window(event_loop: &EventLoop<crate::UserEvent>) -> Result<Window> {
    let window = WindowBuilder::new()
        .with_decorations(false)
        .with_title("ZeroConvert Copy Translation")
        .with_focused(false)
        .with_visible(false)
        .with_undecorated_shadow(true)
        .with_transparent(true)
        .with_inner_size(LogicalSize::new(420.0, 120.0))
        .build(event_loop)
        .context("Failed to create copy translation window")?;

    let hwnd = window.hwnd() as *mut std::ffi::c_void;
    unsafe {
        let exnewstyle = WS_EX_TOOLWINDOW.0 | WS_EX_TOPMOST.0 | WS_EX_NOACTIVATE.0;
        SetWindowLongW(HWND(hwnd), GWL_EXSTYLE, exnewstyle as i32);
        let style = WS_POPUP.0;
        SetWindowLongW(HWND(hwnd), GWL_STYLE, style as i32);
    }
    Ok(window)
}

pub fn create_webview(
    window: &Window,
    web_context: &mut WebContext,
    proxy: tao::event_loop::EventLoopProxy<crate::UserEvent>,
) -> Result<WebView> {
    let html = include_str!("copy_translation.html");
    let webview = WebViewBuilder::with_web_context(web_context)
        .with_transparent(true)
        .with_html(html)
        .with_ipc_handler(move |message| {
            let msg = message.body();
            if let Ok(val) = serde_json::from_str::<serde_json::Value>(msg) {
                let action = val.get("action").and_then(|v| v.as_str()).unwrap_or("");
                match action {
                    "translate" => {
                        let _ = proxy.send_event(crate::UserEvent::CopyTranslationTranslate);
                    }
                    "copy" => {
                        let _ = proxy.send_event(crate::UserEvent::CopyTranslationCopyResult);
                    }
                    "close" => {
                        let _ = proxy.send_event(crate::UserEvent::CopyTranslationClose);
                    }
                    _ => {}
                }
            }
        })
        .build(window)
        .context("Failed to create copy translation webview")?;
    Ok(webview)
}
