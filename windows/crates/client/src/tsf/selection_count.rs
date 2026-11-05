use anyhow::Result;
use selection_ui::{Command, PopupThread};
use std::rc::Rc;
use windows::{
    core::AsImpl as _,
    Win32::{
        Foundation::{HWND, RECT},
        UI::{
            TextServices::{
                ITfContext, ITfEditRecord, ITfTextEditSink_Impl, ITfTextInputProcessor,
                TF_AE_START, TF_ANCHOR_END, TF_ANCHOR_START, TF_DEFAULT_SELECTION, TF_SELECTION,
            },
            WindowsAndMessaging::{GetAncestor, GetForegroundWindow, GA_ROOT},
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

/// TSF owns only a bounded command sender. All HWND/GDI/network work lives on
/// another thread, so showing or clicking a popup cannot take an input edit lock.
#[derive(Debug, Default)]
pub(super) struct SelectionCountWindow {
    visible: bool,
    popup: Option<PopupThread>,
}

impl SelectionCountWindow {
    pub(super) fn is_visible(&self) -> bool {
        self.visible
    }
    pub(super) fn trigger_translation(&mut self) {
        if let Some(popup) = &self.popup {
            popup.send(Command::Translate);
        }
    }
    pub(super) fn hide(&mut self) {
        if self.visible {
            if let Some(popup) = &self.popup {
                popup.send(Command::Hide);
            }
            self.visible = false;
        }
    }
    fn show(
        &mut self,
        owner: HWND,
        anchor: RECT,
        count: SelectionCount,
        text: String,
    ) -> Result<()> {
        if self.popup.is_none() {
            self.popup = Some(PopupThread::start()?);
        }
        self.popup.as_ref().unwrap().send(Command::Selection {
            owner: owner.0 as usize,
            anchor,
            count: count.characters,
            truncated: count.truncated,
            text,
        });
        self.visible = true;
        Ok(())
    }
    pub(super) fn destroy(&mut self) {
        self.popup = None;
        self.visible = false;
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

    pub(crate) fn trigger_selection_translation(&self) -> bool {
        if let Ok(mut service) = self.borrow_mut() {
            if service.selection_count.is_visible() {
                service.selection_count.trigger_translation();
                return true;
            }
        }
        false
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
            let raw_text = read_range_text_prefix(cookie, &readable, MAX_SELECTION_UTF16_UNITS)?;
            let count = selection_count(&raw_text)?;
            if count.characters == 0 {
                self.hide_selection_count();
                return Ok(());
            }
            let text_string = String::from_utf16_lossy(&raw_text);
            self.borrow_mut()?
                .selection_count
                .show(owner, anchor, count, text_string)?;
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
    fn counts_utf16_characters_and_reports_bounds() {
        let sample: Vec<u16> = "123\r\n45\u{1F600}".encode_utf16().collect();
        let counted = selection_count(&sample).unwrap();
        assert_eq!(counted.characters, 7);
        assert!(!counted.truncated);
    }
}
