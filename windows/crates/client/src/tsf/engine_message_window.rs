use std::cell::RefCell;

use anyhow::{Context as _, Result};
use windows::{
    core::{w, AsImpl as _},
    Win32::{
        Foundation::{
            GetLastError, ERROR_CLASS_ALREADY_EXISTS, HINSTANCE, HWND, LPARAM, LRESULT, WPARAM,
        },
        System::LibraryLoader::GetModuleHandleW,
        UI::TextServices::ITfTextInputProcessor,
        UI::WindowsAndMessaging::{
            ChangeWindowMessageFilterEx, CreateWindowExW, DefWindowProcW, DestroyWindow,
            RegisterClassW, HMENU, HWND_MESSAGE, MSGFLT_ALLOW, WINDOW_EX_STYLE, WINDOW_STYLE,
            WNDCLASSW,
        },
    },
};

use super::factory::TextServiceFactory;

/// The candidate UI posts this message to the engine message window when the
/// user clicks a candidate. wParam carries the zero-based candidate index.
pub(crate) const ENGINE_CANDIDATE_SELECT_MESSAGE: u32 = shared::ENGINE_CANDIDATE_SELECT_MESSAGE;

struct EngineMessageTarget {
    hwnd: HWND,
    instance: ITfTextInputProcessor,
}

thread_local! {
    static ENGINE_TARGET: RefCell<Option<EngineMessageTarget>> = const { RefCell::new(None) };
}

/// Creates (once per thread) a message-only window that adopts the candidate
/// selected by a click in the candidate UI. Returns the window handle to
/// advertise to the UI process.
pub(crate) fn ensure_engine_message_window(instance: ITfTextInputProcessor) -> Result<u64> {
    if let Some(hwnd) = current_thread_engine_hwnd() {
        return Ok(hwnd);
    }

    let hmodule = unsafe { GetModuleHandleW(None) }.context("Failed to get module handle")?;
    let hinstance = HINSTANCE(hmodule.0);
    let class_name = w!("AzookeyEngineCandidateSelect");
    let window_class = WNDCLASSW {
        lpfnWndProc: Some(engine_message_window_proc),
        hInstance: hinstance,
        lpszClassName: class_name,
        ..Default::default()
    };

    let hwnd = unsafe {
        let atom = RegisterClassW(&window_class);
        if atom == 0 {
            let error = GetLastError();
            if error != ERROR_CLASS_ALREADY_EXISTS {
                return Err(anyhow::anyhow!(
                    "Failed to register engine message window class: {error:?}"
                ));
            }
        }

        let hwnd = CreateWindowExW(
            WINDOW_EX_STYLE::default(),
            class_name,
            w!(""),
            WINDOW_STYLE::default(),
            0,
            0,
            0,
            0,
            HWND_MESSAGE,
            HMENU::default(),
            hinstance,
            None,
        )
        .context("Failed to create engine message window")?;

        // A non-elevated candidate UI may lose the UIAccess token at startup
        // (see prepare_uiaccess_token); widen the receiver filter so its click
        // requests still reach elevated application windows.
        let _ =
            ChangeWindowMessageFilterEx(hwnd, ENGINE_CANDIDATE_SELECT_MESSAGE, MSGFLT_ALLOW, None);

        hwnd
    };

    ENGINE_TARGET.with(|cell| {
        *cell.borrow_mut() = Some(EngineMessageTarget { hwnd, instance });
    });

    Ok(hwnd.0 as u64)
}

/// Destroys the thread's engine message window, if any.
pub(crate) fn dispose_engine_message_window() {
    ENGINE_TARGET.with(|cell| {
        if let Some(target) = cell.borrow_mut().take() {
            unsafe {
                let _ = DestroyWindow(target.hwnd);
            }
        }
    });
}

/// The handle advertised to the UI process for the current thread, if the
/// window exists.
pub(crate) fn current_thread_engine_hwnd() -> Option<u64> {
    ENGINE_TARGET.with(|cell| cell.borrow().as_ref().map(|target| target.hwnd.0 as u64))
}

fn dispatch_candidate_selection(index: i32) {
    ENGINE_TARGET.with(|cell| {
        let target = cell.borrow();
        let Some(target) = target.as_ref() else {
            return;
        };
        let factory: &TextServiceFactory = unsafe { target.instance.as_impl() };
        if let Err(error) = factory.handle_external_candidate_selection(index) {
            tracing::warn!(?error, index, "Candidate click adoption failed");
        }
    });
}

unsafe extern "system" fn engine_message_window_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if msg == ENGINE_CANDIDATE_SELECT_MESSAGE {
        let index = i32::try_from(wparam.0).unwrap_or(-1);
        if index >= 0 {
            dispatch_candidate_selection(index);
        }
        return LRESULT(0);
    }

    DefWindowProcW(hwnd, msg, wparam, lparam)
}
