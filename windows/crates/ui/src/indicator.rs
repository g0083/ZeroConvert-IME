use anyhow::{Context as _, Result};
use tao::{
    dpi::LogicalSize,
    event_loop::EventLoop,
    platform::windows::{WindowBuilderExtWindows, WindowExtWindows},
    window::{Window, WindowBuilder},
};
use windows::Win32::{
    Foundation::HWND,
    UI::WindowsAndMessaging::{
        SetWindowLongW, GWL_EXSTYLE, GWL_STYLE, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
        WS_POPUP,
    },
};
use wry::{WebContext, WebView, WebViewBuilder};

use crate::UserEvent;

pub fn create_indicator_window(event_loop: &EventLoop<UserEvent>) -> Result<Window> {
    let window = WindowBuilder::new()
        .with_decorations(false)
        .with_title("Indicator")
        .with_focused(false)
        .with_visible(false)
        .with_undecorated_shadow(false)
        .with_transparent(true)
        .build(event_loop)
        .context("Failed to create window")?;

    window.set_inner_size(LogicalSize::new(56.0, 56.0));

    let hwnd = window.hwnd() as *mut std::ffi::c_void;

    // set extended window style
    // https://docs.microsoft.com/en-us/windows/win32/winmsg/extended-window-styles
    // https://docs.microsoft.com/en-us/windows/win32/winmsg/window-styles
    unsafe {
        let exnewstyle = WS_EX_TOOLWINDOW.0 | WS_EX_NOACTIVATE.0 | WS_EX_TOPMOST.0;
        SetWindowLongW(HWND(hwnd), GWL_EXSTYLE, exnewstyle as i32);

        let style = WS_POPUP.0;
        SetWindowLongW(HWND(hwnd), GWL_STYLE, style as i32);
    };

    Ok(window)
}

pub fn create_indicator_webview(
    window: &Window,
    web_context: &mut WebContext,
    theme_script: &str,
) -> Result<WebView> {
    let webview = WebViewBuilder::with_web_context(web_context)
        .with_transparent(true)
        .with_initialization_script(theme_script)
        .with_html(
            r##"
        <html>
            <head>
                <style>
                    body, html {
                        overscroll-behavior: none;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Yu Gothic UI", sans-serif;
                        color: #0F172A;
                        user-select: none;
                    }
                    body {
                        margin: 0;
                        padding: 7px;
                        filter: drop-shadow(0 4px 12px rgba(0, 0, 0, 0.12));
                    }
                    main {
                        width: 100%;
                        height: 100%;
                        border: 1px solid rgba(0, 0, 0, 0.08);
                        border-radius: 8px;
                        background-color: #FFFFFF;
                        box-sizing: border-box;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        font-weight: 600;
                    }

                    @media (prefers-color-scheme: dark) {
                        body, html {
                            color: #F1F5F9;
                        }
                        body {
                            filter: drop-shadow(0 4px 12px rgba(0, 0, 0, 0.4));
                        }
                        main {
                            border: 1px solid rgba(255, 255, 255, 0.12);
                            background-color: #13151B;
                        }
                    }
                </style>
                <script>
                    function updateInputMethod(text) {
                        document.querySelector('main').innerText = text;
                    }
                </script>
            </head>
            <body style="margin: 0;">
                <main>
                    あ
                </main>
            </body>
        </html>"##,
        )
        .build(&window)
        .context("Failed to create webview")?;

    Ok(webview)
}
