//! WASI's only host callback. The shared engine owns the request and response policy.
use std::ffi::{c_char, c_void};
use zerocon_engine::bridge::zerocon_assist_request;

#[link(wasm_import_module = "zerocon_host")]
extern "C" {
    fn complete(payload: *const c_char, timeout_ms: u32) -> *mut c_char;
}

extern "C" {
    fn free(value: *mut c_void);
}

unsafe extern "C" fn host_complete(
    _: *mut c_void,
    _: bool,
    payload: *const c_char,
    timeout_ms: u64,
) -> *mut c_char {
    complete(payload, timeout_ms.min(5000) as u32)
}

unsafe extern "C" fn host_release(_: *mut c_void, value: *mut c_char) {
    free(value.cast());
}

/// # Safety
/// The input is NUL-terminated UTF-8; release the response with zerocon_live_free.
#[no_mangle]
pub unsafe extern "C" fn zerocon_web_assist(payload: *const c_char) -> *mut c_char {
    zerocon_assist_request(payload, host_complete, host_release, std::ptr::null_mut())
}
