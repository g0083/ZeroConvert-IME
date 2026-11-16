use serde::Deserialize;
use std::{
    cell::RefCell,
    collections::VecDeque,
    ffi::{c_char, CStr, CString},
};
use zerocon_engine::conversion_policy::{
    find_auto_commit_live_conversion_snapshot, record_live_conversion_prefix_snapshot,
    LiveConversionPrefixSnapshot,
};

#[derive(Default)]
struct History {
    snapshots: Vec<LiveConversionPrefixSnapshot>,
    surfaces: VecDeque<String>,
}

thread_local! {
    static LIVE: RefCell<History> = RefCell::new(History::default());
}

#[derive(Deserialize)]
struct Observation {
    raw: String,
    reading: String,
    preview: String,
    suffix: String,
}

/// # Safety
/// `payload` is a valid C string. Release the result with `zerocon_live_free`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_live_observe(payload: *const c_char) -> *mut c_char {
    if payload.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(reading) = serde_json::from_slice::<Observation>(CStr::from_ptr(payload).to_bytes())
    else {
        return std::ptr::null_mut();
    };
    let value = LIVE.with_borrow_mut(|History { snapshots, surfaces }| {
        let Observation { raw, reading, preview, suffix } = reading;
        record_live_conversion_prefix_snapshot(&raw, &reading, &preview, &suffix, snapshots, surfaces);
        find_auto_commit_live_conversion_snapshot(&raw, &reading, &preview, &suffix, snapshots, surfaces)
            .map(|result| serde_json::json!({"rawCount": result.raw_input_len, "surface": result.preview}))
    });
    CString::new(serde_json::to_vec(&value).expect("serialize snapshot"))
        .expect("JSON contains no literal NUL")
        .into_raw()
}

#[no_mangle]
pub extern "C" fn zerocon_live_reset() {
    LIVE.with_borrow_mut(|history| *history = History::default());
}

/// # Safety
/// `value` must be an outstanding result of `zerocon_live_observe`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_live_free(value: *mut c_char) {
    if !value.is_null() {
        drop(CString::from_raw(value));
    }
}
