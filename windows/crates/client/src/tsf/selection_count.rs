use anyhow::Result;
use std::rc::Rc;
use windows::{
    core::{w, AsImpl as _, PCWSTR},
    Win32::{
        Foundation::{COLORREF, HWND, LPARAM, RECT, WPARAM},
        Graphics::Gdi::{
            GetMonitorInfoW, GetStockObject, MonitorFromRect, DEFAULT_GUI_FONT, MONITORINFO,
            MONITOR_DEFAULTTONEAREST,
        },
        System::SystemServices::{SS_CENTER, SS_CENTERIMAGE},
        UI::{
            HiDpi::GetDpiForWindow,
            TextServices::{
                ITfContext, ITfEditRecord, ITfTextEditSink_Impl, ITfTextInputProcessor,
                TF_AE_START, TF_ANCHOR_END, TF_ANCHOR_START, TF_DEFAULT_SELECTION, TF_SELECTION,
            },
            WindowsAndMessaging::{
                CreateWindowExW, DestroyWindow, GetAncestor, GetForegroundWindow, IsWindow,
                SendMessageW, SetLayeredWindowAttributes, SetWindowPos, SetWindowTextW, ShowWindow,
                GA_ROOT, HMENU, HWND_TOPMOST, LWA_ALPHA, SWP_NOACTIVATE, SWP_SHOWWINDOW, SW_HIDE,
                WINDOW_STYLE, WM_SETFONT, WS_BORDER, WS_EX_LAYERED, WS_EX_NOACTIVATE,
                WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_EX_TRANSPARENT, WS_POPUP,
            },
        },
    },
};

use super::{
    edit_session::{
        has_same_com_identity, read_range_text_prefix, request_async_read_edit_session,
    },
    factory::{TextServiceFactory, TextServiceFactory_Impl},
};
use crate::engine::{
    composition::CompositionState,
    state::{keyboard_disabled_from_context, IMEState},
};

// Keep selection callbacks bounded even when an application selects a whole document.
const MAX_SELECTION_UTF16_UNITS: usize = 65_536;

#[derive(Debug, PartialEq, Eq)]
struct SelectionCount {
    characters: usize,
    truncated: bool,
}

fn selection_count(text: &[u16]) -> Result<SelectionCount> {
    let truncated = text.len() > MAX_SELECTION_UTF16_UNITS;
    let mut end = text.len().min(MAX_SELECTION_UTF16_UNITS);
    if truncated && end > 0 && (0xd800..=0xdbff).contains(&text[end - 1]) {
        end -= 1;
    }
    let mut characters = 0;
    let mut previous_cr = false;
    for ch in char::decode_utf16(text[..end].iter().copied()) {
        let ch = ch?;
        if ch != '\n' || !previous_cr {
            characters += 1;
        }
        previous_cr = ch == '\r';
    }
    Ok(SelectionCount {
        characters,
        truncated,
    })
}

fn popup_position(anchor: RECT, work: RECT, width: i32, height: i32, gap: i32) -> (i32, i32) {
    let left = anchor
        .left
        .clamp(work.left, (work.right - width).max(work.left));
    let top = if anchor.bottom + gap + height <= work.bottom {
        anchor.bottom + gap
    } else {
        anchor.top - height - gap
    }
    .clamp(work.top, (work.bottom - height).max(work.top));
    (left, top)
}

#[derive(Debug, Default)]
pub(super) struct SelectionCountWindow {
    hwnd: HWND,
    owner: HWND,
    visible: bool,
}

impl SelectionCountWindow {
    pub(super) fn is_visible(&self) -> bool {
        self.visible
    }

    pub(super) fn hide(&mut self) {
        if self.visible {
            unsafe {
                let _ = ShowWindow(self.hwnd, SW_HIDE);
            }
            self.visible = false;
        }
    }

    fn show(&mut self, owner: HWND, anchor: RECT, count: SelectionCount) -> Result<()> {
        unsafe {
            if self.owner != owner || !IsWindow(self.hwnd).as_bool() {
                self.destroy();
                self.hwnd = CreateWindowExW(
                    WS_EX_TOOLWINDOW
                        | WS_EX_NOACTIVATE
                        | WS_EX_TOPMOST
                        | WS_EX_TRANSPARENT
                        | WS_EX_LAYERED,
                    w!("STATIC"),
                    w!(""),
                    WS_POPUP | WS_BORDER | WINDOW_STYLE(SS_CENTER.0 | SS_CENTERIMAGE.0),
                    0,
                    0,
                    0,
                    0,
                    owner,
                    HMENU::default(),
                    None,
                    None,
                )?;
                self.owner = owner;
                SetLayeredWindowAttributes(self.hwnd, COLORREF(0), 255, LWA_ALPHA)?;
                let font = GetStockObject(DEFAULT_GUI_FONT);
                SendMessageW(self.hwnd, WM_SETFONT, WPARAM(font.0 as usize), LPARAM(0));
            }
            let label = format!(
                "{}文字{}",
                count.characters,
                if count.truncated { "以上" } else { "" }
            );
            let label: Vec<_> = label.encode_utf16().chain(Some(0)).collect();
            SetWindowTextW(self.hwnd, PCWSTR(label.as_ptr()))?;
            let dpi = GetDpiForWindow(owner).max(96) as i32;
            let width = 160 * dpi / 96;
            let height = 30 * dpi / 96;
            let gap = 6 * dpi / 96;
            let mut monitor = MONITORINFO {
                cbSize: std::mem::size_of::<MONITORINFO>() as u32,
                ..Default::default()
            };
            if !GetMonitorInfoW(
                MonitorFromRect(&anchor, MONITOR_DEFAULTTONEAREST),
                &mut monitor,
            )
            .as_bool()
            {
                self.hide();
                return Ok(());
            }
            let (left, top) = popup_position(anchor, monitor.rcWork, width, height, gap);
            SetWindowPos(
                self.hwnd,
                HWND_TOPMOST,
                left,
                top,
                width,
                height,
                SWP_NOACTIVATE | SWP_SHOWWINDOW,
            )?;
            self.visible = true;
        }
        Ok(())
    }

    pub(super) fn destroy(&mut self) {
        if !self.hwnd.is_invalid() {
            unsafe {
                let _ = DestroyWindow(self.hwnd);
            }
        }
        self.hwnd = HWND::default();
        self.owner = HWND::default();
        self.visible = false;
    }
}

impl Drop for SelectionCountWindow {
    fn drop(&mut self) {
        self.destroy();
    }
}

impl TextServiceFactory {
    pub(crate) fn hide_selection_count(&self) {
        if let Ok(mut service) = self.borrow_mut() {
            service.selection_count.hide();
            service.selection_count_generation = service.selection_count_generation.wrapping_add(1);
            service.selection_count_pending = false;
        }
    }

    pub(super) fn request_selection_count_update(&self, context: &ITfContext) -> Result<()> {
        let (tid, this, generation) = {
            let mut service = self.borrow_mut()?;
            let composing = {
                let composition = service.borrow_composition()?;
                composition.state != CompositionState::None || composition.tip_composition.is_some()
            };
            if composing {
                return Ok(());
            }
            if service.tid == 0
                || service.selection_count_pending
                || !service
                    .layout_sink_context
                    .as_ref()
                    .is_some_and(|current| has_same_com_identity(current, context))
            {
                return Ok(());
            }
            let this = service.this::<ITfTextInputProcessor>()?;
            service.selection_count_pending = true;
            service.selection_count_generation = service.selection_count_generation.wrapping_add(1);
            (service.tid, this, service.selection_count_generation)
        };
        let callback = Rc::new({
            let this = this.clone();
            let context = context.clone();
            move |cookie| {
                let factory = unsafe { this.as_impl() };
                if !factory
                    .borrow()
                    .is_ok_and(|service| service.selection_count_generation == generation)
                {
                    return Ok(());
                }
                if factory.update_selection_count(&context, cookie).is_err() {
                    factory.hide_selection_count();
                }
                Ok(())
            }
        });
        let completion = Rc::new(move || {
            let factory = unsafe { this.as_impl() };
            if let Ok(mut service) = factory.borrow_mut() {
                if service.selection_count_generation == generation {
                    service.selection_count_pending = false;
                }
            }
        });
        request_async_read_edit_session(tid, context.clone(), callback, completion)
    }

    fn update_selection_count(&self, context: &ITfContext, cookie: u32) -> Result<()> {
        let idle = {
            let service = self.borrow()?;
            let composition = service.borrow_composition()?;
            composition.state == CompositionState::None
                && composition.tip_composition.is_none()
                && service
                    .layout_sink_context
                    .as_ref()
                    .is_some_and(|current| has_same_com_identity(current, context))
        };
        if !idle || IMEState::keyboard_disabled()? || keyboard_disabled_from_context(context) {
            self.hide_selection_count();
            return Ok(());
        }
        unsafe {
            let mut selection = [TF_SELECTION::default()];
            let mut fetched = 0;
            context.GetSelection(cookie, TF_DEFAULT_SELECTION, &mut selection, &mut fetched)?;
            let Some(range) = (fetched > 0).then(|| selection[0].range.as_ref()).flatten() else {
                self.hide_selection_count();
                return Ok(());
            };
            if range.IsEmpty(cookie)?.as_bool()
                || Self::range_has_sensitive_input_scope(cookie, context, range).unwrap_or(false)
            {
                self.hide_selection_count();
                return Ok(());
            }
            let view = context.GetActiveView()?;
            let owner = GetAncestor(view.GetWnd()?, GA_ROOT);
            if owner.is_invalid() || owner != GetAncestor(GetForegroundWindow(), GA_ROOT) {
                self.hide_selection_count();
                return Ok(());
            }
            let caret = range.Clone()?;
            caret.Collapse(
                cookie,
                if selection[0].style.ase == TF_AE_START {
                    TF_ANCHOR_START
                } else {
                    TF_ANCHOR_END
                },
            )?;
            let mut anchor = RECT::default();
            let mut clipped = false.into();
            view.GetTextExt(cookie, &caret, &mut anchor, &mut clipped)?;
            if clipped.as_bool() {
                self.hide_selection_count();
                return Ok(());
            }
            // Read a clone: counting must not move or replace the application's selection.
            let readable = range.Clone()?;
            let count = selection_count(&read_range_text_prefix(
                cookie,
                &readable,
                MAX_SELECTION_UTF16_UNITS,
            )?)?;
            if count.characters == 0 {
                self.hide_selection_count();
                return Ok(());
            }
            self.borrow_mut()?
                .selection_count
                .show(owner, anchor, count)?;
        }
        Ok(())
    }
}

impl ITfTextEditSink_Impl for TextServiceFactory_Impl {
    #[macros::anyhow]
    fn OnEndEdit(
        &self,
        context: Option<&ITfContext>,
        _cookie: u32,
        record: Option<&ITfEditRecord>,
    ) -> Result<()> {
        let Some(context) = context else {
            return Ok(());
        };
        if record.is_some_and(|record| unsafe {
            record
                .GetSelectionStatus()
                .is_ok_and(|changed| !changed.as_bool())
        }) && !self
            .borrow()
            .is_ok_and(|service| service.selection_count.visible)
        {
            return Ok(());
        }
        // Coalesce drag events into an asynchronous read of the latest selection;
        // never wait on conversion/UI IPC or a synchronous edit session here.
        if self.request_selection_count_update(context).is_err() {
            self.hide_selection_count();
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_unicode_scalars_and_treats_crlf_as_one_character() {
        let text = "日本😀 かな\r\n次\n".encode_utf16().collect::<Vec<_>>();
        assert_eq!(
            selection_count(&text).unwrap(),
            SelectionCount {
                characters: 9,
                truncated: false
            }
        );
        assert_eq!(selection_count(&[]).unwrap().characters, 0);
        assert!(selection_count(&[0xd800]).is_err());
    }

    #[test]
    fn large_selection_reports_lower_bound_without_splitting_surrogate_pair() {
        let mut text = vec![b'a' as u16; MAX_SELECTION_UTF16_UNITS - 1];
        text.extend("😀z".encode_utf16());
        assert_eq!(
            selection_count(&text).unwrap(),
            SelectionCount {
                characters: MAX_SELECTION_UTF16_UNITS - 1,
                truncated: true
            }
        );
    }

    #[test]
    fn popup_stays_inside_work_area_on_negative_coordinate_monitor() {
        let work = RECT {
            left: -1920,
            top: 0,
            right: 0,
            bottom: 1040,
        };
        let anchor = RECT {
            left: -30,
            top: 1010,
            right: -10,
            bottom: 1030,
        };
        assert_eq!(popup_position(anchor, work, 160, 30, 6), (-160, 974));
    }

    #[test]
    fn selection_context_teardown_invalidates_queued_count_updates() {
        let mut service = super::super::text_service::TextService::default();
        service.selection_count_pending = true;
        let queued_generation = service.selection_count_generation;
        service.unadvise_text_layout_sink().unwrap();
        assert!(!service.selection_count_pending);
        assert_ne!(service.selection_count_generation, queued_generation);
        assert!(!service.selection_count.is_visible());
    }
}
