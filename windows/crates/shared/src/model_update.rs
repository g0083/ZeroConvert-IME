//! Short-lived handshake for releasing the prediction model before replacing it.
//! Handles are owned by the settings process, so a crash cannot leave a pause set.
use std::{
    io,
    os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle},
    time::Duration,
};
use windows::{
    core::PCWSTR,
    Win32::{
        Foundation::{GetLastError, ERROR_ALREADY_EXISTS, HANDLE, WAIT_OBJECT_0},
        System::Threading::{
            CreateEventW, OpenEventW, SetEvent, WaitForSingleObject, EVENT_MODIFY_STATE,
            SYNCHRONIZATION_ACCESS_RIGHTS,
        },
    },
};

fn names() -> io::Result<(String, String)> {
    let mut session = 0;
    unsafe {
        windows::Win32::System::RemoteDesktop::ProcessIdToSessionId(
            std::process::id(),
            &mut session,
        )
    }?;
    let prefix = format!(r"Local\azookey_model_update_s{session}");
    Ok((format!("{prefix}_request"), format!("{prefix}_ready")))
}
fn create(name: &str) -> io::Result<OwnedHandle> {
    let wide: Vec<_> = name.encode_utf16().chain(Some(0)).collect();
    let handle = unsafe { CreateEventW(None, true, false, PCWSTR(wide.as_ptr())) }?;
    let exists = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
    let handle = unsafe { OwnedHandle::from_raw_handle(handle.0) };
    if exists {
        return Err(io::Error::other("A model update is already in progress"));
    }
    Ok(handle)
}
fn open(name: &str, access: SYNCHRONIZATION_ACCESS_RIGHTS) -> Option<OwnedHandle> {
    let wide: Vec<_> = name.encode_utf16().chain(Some(0)).collect();
    let handle = unsafe { OpenEventW(access, false, PCWSTR(wide.as_ptr())) }.ok()?;
    Some(unsafe { OwnedHandle::from_raw_handle(handle.0) })
}
fn raw(handle: &OwnedHandle) -> HANDLE {
    HANDLE(handle.as_raw_handle())
}

pub struct ModelUpdateGuard {
    _request: OwnedHandle,
    _ready: OwnedHandle,
    _conversion_ready: OwnedHandle,
}
impl ModelUpdateGuard {
    pub fn begin(timeout: Duration) -> io::Result<Self> {
        Self::begin_named(names()?, timeout)
    }
    fn begin_named((request, ready): (String, String), timeout: Duration) -> io::Result<Self> {
        let conversion_ready = create(&format!("{ready}_conversion"))?;
        let ready = create(&ready)?;
        let request = create(&request)?;
        unsafe { SetEvent(raw(&request)) }?;
        let deadline = std::time::Instant::now() + timeout;
        for ready_handle in [&ready, &conversion_ready] {
            if unsafe {
                WaitForSingleObject(
                    raw(ready_handle),
                    deadline
                        .saturating_duration_since(std::time::Instant::now())
                        .as_millis()
                        .min(u32::MAX as u128) as u32,
                )
            } != WAIT_OBJECT_0
            {
                return Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "Model processes did not release the weights; restart the IME and retry",
                ));
            }
        }
        Ok(Self {
            _request: request,
            _ready: ready,
            _conversion_ready: conversion_ready,
        })
    }
}

/// Called only on background supervisor threads, never during input handling.
pub fn requested() -> bool {
    names().is_ok_and(|(request, _)| requested_named(&request))
}
fn requested_named(request: &str) -> bool {
    open(request, SYNCHRONIZATION_ACCESS_RIGHTS(0x0010_0000))
        .is_some_and(|handle| unsafe { WaitForSingleObject(raw(&handle), 0) } == WAIT_OBJECT_0)
}
pub fn acknowledge_prediction_stopped() {
    if let Ok((_, ready)) = names() {
        acknowledge_named(&ready);
    }
}
pub fn acknowledge_conversion_stopped() {
    if let Ok((_, ready)) = names() {
        acknowledge_named(&format!("{ready}_conversion"));
    }
}
fn acknowledge_named(ready: &str) {
    if let Some(handle) = open(ready, EVENT_MODIFY_STATE) {
        let _ = unsafe { SetEvent(raw(&handle)) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn handshake_requires_stop_ack_and_releases_pause_on_drop_or_timeout() {
        let prefix = format!(r"Local\azookey_model_update_test_{}", std::process::id());
        let names = (format!("{prefix}_request"), format!("{prefix}_ready"));
        let worker_names = names.clone();
        let worker = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + Duration::from_secs(2);
            while !requested_named(&worker_names.0) {
                assert!(std::time::Instant::now() < deadline);
                std::thread::yield_now();
            }
            acknowledge_named(&worker_names.1);
            acknowledge_named(&format!("{}_conversion", worker_names.1));
        });
        let guard = ModelUpdateGuard::begin_named(names.clone(), Duration::from_secs(2)).unwrap();
        worker.join().unwrap();
        assert!(requested_named(&names.0));
        assert!(ModelUpdateGuard::begin_named(names.clone(), Duration::ZERO).is_err());
        drop(guard);
        assert!(!requested_named(&names.0));
        assert!(ModelUpdateGuard::begin_named(names.clone(), Duration::from_millis(1)).is_err());
        assert!(!requested_named(&names.0));
    }
}
