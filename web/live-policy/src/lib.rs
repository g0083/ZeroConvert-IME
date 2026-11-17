use serde::Deserialize;
use std::{
    cell::RefCell,
    collections::VecDeque,
    ffi::{c_char, CStr, CString},
};
use zerocon_engine::conversion_policy::{
    decide_backspace_action, decide_cursor_commit_action, decide_delete_forward_action,
    find_auto_commit_live_conversion_snapshot, raw_input_has_pending_romaji_tail,
    record_live_conversion_prefix_snapshot, CursorCommitAction, CursorDeleteAction,
    LiveConversionPrefixSnapshot,
};
use zerocon_engine::session_policy::{resolve_candidate_index, CandidateSelectionMove};

#[cfg(target_os = "wasi")]
mod assist_host;

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

#[derive(Deserialize)]
struct SelectionRequest {
    current: i32,
    candidates: u32,
    direction: String,
    #[serde(default)]
    index: i32,
}

/// Resolves a candidate-selection move with the same clamping as the Windows
/// client, so the browser Worker picks the row the TSF client would.
///
/// # Safety
/// `payload` is a valid C string. Release the result with `zerocon_live_free`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_session_selection(payload: *const c_char) -> *mut c_char {
    if payload.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(request) =
        serde_json::from_slice::<SelectionRequest>(CStr::from_ptr(payload).to_bytes())
    else {
        return std::ptr::null_mut();
    };
    let movement = match request.direction.as_str() {
        "next" => CandidateSelectionMove::Next,
        "previous" => CandidateSelectionMove::Previous,
        "absolute" => CandidateSelectionMove::Absolute(request.index),
        _ => return std::ptr::null_mut(),
    };
    let count = request.candidates.max(0) as usize;
    let value = resolve_candidate_index(request.current, count, movement);
    CString::new(serde_json::to_vec(&value).expect("serialize selection"))
        .expect("JSON contains no literal NUL")
        .into_raw()
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

#[derive(Deserialize)]
struct DecisionRequest {
    action: String,
    #[serde(default)]
    cursor: u32,
    #[serde(default)]
    total: u32,
    #[serde(default)]
    raw: String,
    #[serde(default)]
    reading: String,
    #[serde(default)]
    has_prefix: bool,
    #[serde(default)]
    has_suffix: bool,
}

/// Resolves the accept and deletion decisions with the same shared policy as
/// the Windows client, so the browser end a composition where the TSF client
/// would and cancels the same edits.
///
/// # Safety
/// `payload` is a valid C string. Release the result with `zerocon_live_free`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_session_decision(payload: *const c_char) -> *mut c_char {
    if payload.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(request) = serde_json::from_slice::<DecisionRequest>(CStr::from_ptr(payload).to_bytes())
    else {
        return std::ptr::null_mut();
    };
    let cursor = request.cursor as usize;
    let total = request.total as usize;
    let value = match request.action.as_str() {
        "accept" => serde_json::json!({
            "accept": match decide_cursor_commit_action(request.has_prefix, request.has_suffix) {
                CursorCommitAction::CommitWholeAndEnd => "whole",
                CursorCommitAction::CommitPrefixKeepSuffix => "prefix",
                CursorCommitAction::CommitSuffixAndEnd => "suffix",
            }
        }),
        "backspace" => deletions(decide_backspace_action(cursor, total)),
        "delete" => deletions(decide_delete_forward_action(cursor, total)),
        "pendingRomaji" => serde_json::json!({
            "pending": raw_input_has_pending_romaji_tail(&request.raw, &request.reading)
        }),
        _ => return std::ptr::null_mut(),
    };
    CString::new(serde_json::to_vec(&value).expect("serialize decision"))
        .expect("JSON contains no literal NUL")
        .into_raw()
}

fn deletions(action: CursorDeleteAction) -> serde_json::Value {
    match action {
        CursorDeleteAction::NoOp => serde_json::json!({ "delete": "none" }),
        CursorDeleteAction::DeleteBackward => serde_json::json!({ "delete": "backward" }),
        CursorDeleteAction::DeleteForward => serde_json::json!({ "delete": "forward" }),
    }
}

#[derive(Deserialize)]
struct LearningRequest {
    mode: String,
    #[serde(default)]
    target: bool,
}

/// Reports what the current learning mode permits, so the browser enables the
/// same learning operations the Windows client does.
///
/// # Safety
/// `payload` is a valid C string. Release the result with `zerocon_live_free`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_session_learning(payload: *const c_char) -> *mut c_char {
    if payload.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(request) = serde_json::from_slice::<LearningRequest>(CStr::from_ptr(payload).to_bytes())
    else {
        return std::ptr::null_mut();
    };
    let capability = zerocon_engine::learning_policy::capability(&request.mode);
    let value = serde_json::json!({
        "mode": zerocon_engine::learning_policy::normalize_mode(&request.mode),
        "record": capability.record,
        "read": capability.read,
        "forget": capability.forget,
        "clear": capability.clear,
        "recordCommit": zerocon_engine::learning_policy::should_record_commit(&request.mode, request.target),
    });
    CString::new(value.to_string())
        .expect("JSON contains no literal NUL")
        .into_raw()
}

/// Runs one shared assistance decision (`plan`, `choose`, `ready`, `adopt` or a
/// correction-state operation) so the browser merges corrections and
/// predictions with the Windows priority rules instead of its own.
///
/// # Safety
/// `payload` is a valid C string. Release the result with `zerocon_live_free`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_session_assist(payload: *const c_char) -> *mut c_char {
    if payload.is_null() {
        return std::ptr::null_mut();
    }
    let Ok(request) = CStr::from_ptr(payload).to_str() else {
        return std::ptr::null_mut();
    };
    let value = match zerocon_engine::assist_policy::dispatch(request) {
        Ok(value) => value,
        Err(error) => serde_json::json!({ "error": error }),
    };
    CString::new(value.to_string())
        .expect("JSON contains no literal NUL")
        .into_raw()
}

/// # Safety
/// `value` must be an outstanding result of `zerocon_live_observe`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_live_free(value: *mut c_char) {
    if !value.is_null() {
        drop(CString::from_raw(value));
    }
}
