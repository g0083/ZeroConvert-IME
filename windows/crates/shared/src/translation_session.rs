//! Lifecycle guard for on-demand translation server synchronization.
//!
//! When the user enters translation mode (or requests a floating translation),
//! a session guard is opened. The launcher observes this named semaphore and only
//! launches the translation llama-server process while an active session exists,
//! releasing CPU/RAM/VRAM immediately when translation mode exits.
use std::{
    io,
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
};
use windows::{
    core::PCWSTR,
    Win32::{
        Foundation::HANDLE,
        System::Threading::{
            CreateSemaphoreW, OpenSemaphoreW, ReleaseSemaphore, WaitForSingleObject,
            SYNCHRONIZATION_ACCESS_RIGHTS,
        },
    },
};

fn session_event_name() -> io::Result<String> {
    let mut session = 0;
    unsafe {
        windows::Win32::System::RemoteDesktop::ProcessIdToSessionId(
            std::process::id(),
            &mut session,
        )
    }?;
    Ok(format!(r"Local\azookey_translation_sessions_v2_s{session}"))
}

fn open_session(name: &str, access: SYNCHRONIZATION_ACCESS_RIGHTS) -> Option<OwnedHandle> {
    let wide: Vec<_> = name.encode_utf16().chain(Some(0)).collect();
    let handle = unsafe { OpenSemaphoreW(access, false, PCWSTR(wide.as_ptr())) }.ok()?;
    Some(unsafe { OwnedHandle::from_raw_handle(handle.0) })
}

/// Returns true if an active translation session has at least one owner.
pub fn is_translation_active() -> bool {
    let Ok(name) = session_event_name() else {
        return false;
    };
    is_translation_active_named(&name)
}

pub fn is_translation_active_named(name: &str) -> bool {
    // SYNCHRONIZE | SEMAPHORE_MODIFY_STATE
    let Some(handle) = open_session(name, SYNCHRONIZATION_ACCESS_RIGHTS(0x0010_0002)) else {
        return false;
    };
    let raw = HANDLE(handle.as_raw_handle());
    // Add/remove an observer token; the previous count is the number of owners.
    // Unlike a reset event, dropping one owner cannot turn off another session.
    let mut previous = 0;
    unsafe {
        if ReleaseSemaphore(raw, 1, Some(&mut previous)).is_err() {
            return false;
        }
        let _ = WaitForSingleObject(raw, 0);
    }
    previous > 0
}

/// Guard holding the translation active state. While this exists, `is_translation_active` returns true.
#[derive(Debug)]
pub struct TranslationSessionGuard {
    event: OwnedHandle,
}

impl TranslationSessionGuard {
    pub fn start() -> io::Result<Self> {
        Self::start_named(&session_event_name()?)
    }

    pub fn start_named(name: &str) -> io::Result<Self> {
        let wide: Vec<_> = name.encode_utf16().chain(Some(0)).collect();
        let handle = unsafe { CreateSemaphoreW(None, 0, i32::MAX, PCWSTR(wide.as_ptr())) }?;
        let owned = unsafe { OwnedHandle::from_raw_handle(handle.0) };
        unsafe {
            ReleaseSemaphore(HANDLE(owned.as_raw_handle()), 1, None)?;
        }
        Ok(Self { event: owned })
    }

    pub fn end(self) {
        drop(self);
    }
}

impl Drop for TranslationSessionGuard {
    fn drop(&mut self) {
        unsafe {
            let _ = WaitForSingleObject(HANDLE(self.event.as_raw_handle()), 0);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn translation_guard_controls_signaled_state() {
        let name = format!(r"Local\azookey_translation_test_{}", std::process::id());
        assert!(!is_translation_active_named(&name));

        let guard = TranslationSessionGuard::start_named(&name).unwrap();
        assert!(is_translation_active_named(&name));

        let second = TranslationSessionGuard::start_named(&name).unwrap();
        drop(guard);
        assert!(is_translation_active_named(&name));
        second.end();
        assert!(!is_translation_active_named(&name));
    }
}
