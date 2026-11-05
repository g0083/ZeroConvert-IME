use windows::core::{Interface, GUID};
use windows::Win32::Foundation::{HWND, POINT};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER,
    COINIT_APARTMENTTHREADED,
};
use windows::Win32::UI::Accessibility::{
    IUIAutomation, IUIAutomationValuePattern, UIA_DocumentControlTypeId, UIA_EditControlTypeId,
    UIA_PaneControlTypeId, UIA_TextControlTypeId, UIA_ValuePatternId,
};

use crate::copy_translation::CopySource;

const CLSID_CUIAUTOMATION: GUID = GUID::from_u128(0xff48dba4_60ef_4201_aa87_54103eef594e);

pub fn classify(foreground_raw: isize, mouse: POINT) -> CopySource {
    let foreground = HWND(foreground_raw as *mut std::ffi::c_void);
    let _ = foreground;
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
        let result = classify_inner(mouse);
        CoUninitialize();
        result
    }
}

fn classify_inner(mouse: POINT) -> CopySource {
    unsafe {
        let automation: IUIAutomation =
            match CoCreateInstance(&CLSID_CUIAUTOMATION, None, CLSCTX_INPROC_SERVER) {
                Ok(a) => a,
                Err(_) => return CopySource::Unknown,
            };
        let walker = automation.ControlViewWalker().ok();
        let element = match automation.ElementFromPoint(mouse) {
            Ok(e) => e,
            Err(_) => return CopySource::Unknown,
        };

        let mut editable = false;
        let mut control_type = 0i32;
        let mut current = Some(element);
        for _ in 0..8 {
            let Some(el) = current.take() else { break };
            if let Ok(ct) = el.CurrentControlType() {
                control_type = ct.0;
            }
            if control_type == UIA_EditControlTypeId.0 {
                editable = true;
            }
            if let Ok(unk) = el.GetCurrentPattern(UIA_ValuePatternId) {
                if let Ok(vp) = unk.cast::<IUIAutomationValuePattern>() {
                    if let Ok(ro) = vp.CurrentIsReadOnly() {
                        if !ro.as_bool() {
                            editable = true;
                        }
                    }
                }
            }
            current = walker.as_ref().and_then(|w| w.GetParentElement(&el).ok());
        }

        if editable {
            return CopySource::Editable;
        }
        if control_type == UIA_DocumentControlTypeId.0
            || control_type == UIA_TextControlTypeId.0
            || control_type == UIA_PaneControlTypeId.0
        {
            CopySource::ReadOnlyDocument
        } else {
            CopySource::Unknown
        }
    }
}

pub fn classify_with_timeout(foreground_raw: isize, mouse: POINT, timeout_ms: u64) -> CopySource {
    use std::sync::mpsc;
    use std::thread;
    use std::time::Duration;
    let (tx, rx) = mpsc::channel();
    let pt = mouse;
    thread::spawn(move || {
        let r = classify(foreground_raw, pt);
        let _ = tx.send(r);
    });
    match rx.recv_timeout(Duration::from_millis(timeout_ms)) {
        Ok(r) => r,
        Err(_) => CopySource::Unknown,
    }
}
