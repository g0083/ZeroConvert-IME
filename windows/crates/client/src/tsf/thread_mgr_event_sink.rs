use std::{cell::Cell, rc::Rc};

use windows::{
    core::AsImpl as _,
    Win32::UI::TextServices::{
        ITfContext, ITfDocumentMgr, ITfTextInputProcessor, ITfThreadFocusSink_Impl,
        ITfThreadMgrEventSink_Impl, TF_DEFAULT_SELECTION, TF_SELECTION,
    },
};

use anyhow::Result;

use crate::engine::{
    composition::CompositionState,
    input_mode::InputMode,
    input_scope_mode::mode_for_scopes,
    state::{keyboard_disabled_from_context, IMEState},
};

use super::edit_session::{has_same_com_identity, request_async_read_edit_session};
use super::factory::{TextServiceFactory, TextServiceFactory_Impl};

fn ensure_ipc_service_for_tsf_event(event: &str) {
    match IMEState::ensure_ipc_service() {
        Ok(true) => tracing::debug!(event, "Initialized IPC service during TSF event"),
        Ok(false) => {}
        Err(error) => tracing::warn!(?error, event, "IPC service is unavailable during TSF event"),
    }
}

/// Input mode implied by the InputScope of the current selection, if any.
fn input_scope_mode_for_cookie(context: &ITfContext, cookie: u32) -> Option<InputMode> {
    let mut selection = [TF_SELECTION::default()];
    let mut fetched = 0;
    unsafe {
        context
            .GetSelection(cookie, TF_DEFAULT_SELECTION, &mut selection, &mut fetched)
            .ok()?;
    }
    if fetched == 0 {
        return None;
    }
    let range = selection[0].range.as_ref()?;
    let range = unsafe { range.Clone().ok()? };
    let scopes = TextServiceFactory::range_input_scopes(cookie, context, &range).ok()?;
    mode_for_scopes(&scopes)
}

impl TextServiceFactory {
    fn refresh_selection_context(&self) -> Result<()> {
        let thread_mgr = self.borrow()?.thread_mgr()?;
        let Ok(document) = (unsafe { thread_mgr.GetFocus() }) else {
            return Ok(());
        };
        let context = unsafe { document.GetTop()? };
        let current = self.borrow()?.layout_sink_context.clone();
        if !current
            .as_ref()
            .is_some_and(|current| has_same_com_identity(current, &context))
        {
            self.borrow_mut()?.advise_text_layout_sink(document)?;
        }
        self.request_selection_count_update(&context)
    }

    pub fn set_keyboard_disabled_state(&self, disabled: bool) -> Result<()> {
        if disabled {
            self.stop_translation_mode();
            self.hide_selection_count();
            self.cancel_typo_assist();
            self.cancel_jev_conversion();
        }
        let (changed, ipc_service) = IMEState::set_keyboard_disabled_and_clone_ipc(disabled)?;

        if let Some(mut ipc_service) = ipc_service {
            if let Ok(delivery) =
                ipc_service.update_candidate_window(Some(false), None, Some(vec![]), Some(0), None)
            {
                self.remember_candidate_window_visibility_if_sent(delivery, Some(false));
            }

            IMEState::set_ipc_service(ipc_service)?;
        }

        if changed {
            self.update_lang_bar()?;
        }

        Ok(())
    }

    pub(crate) fn set_keyboard_disabled_for_document_mgr(
        &self,
        focus: Option<&ITfDocumentMgr>,
    ) -> Result<()> {
        let disabled = match focus {
            Some(focus) => unsafe {
                focus
                    .GetTop()
                    .map(|context| keyboard_disabled_from_context(&context))
                    .unwrap_or(true)
            },
            None => true,
        };

        self.set_keyboard_disabled_state(disabled)
    }

    /// Align the input mode with the focused field's InputScope.
    ///
    /// This runs only when input starts, so a mode the user picks while typing is
    /// never overwritten. The scope is read in one asynchronous edit session and
    /// applied from its completion, never from inside the edit session.
    pub(crate) fn request_input_scope_mode_switch(
        &self,
        focus: Option<&ITfDocumentMgr>,
    ) -> Result<()> {
        let Some(focus) = focus else {
            return Ok(());
        };
        if !self.follow_input_scope_mode_enabled()? || IMEState::keyboard_disabled()? {
            return Ok(());
        }
        let (tid, this, context) = {
            let text_service = self.borrow()?;
            let composing = text_service
                .borrow_composition()
                .is_ok_and(|composition| composition.state != CompositionState::None);
            if composing || text_service.tid == 0 {
                return Ok(());
            }
            let context = unsafe { focus.GetTop()? };
            if keyboard_disabled_from_context(&context) {
                return Ok(());
            }
            (
                text_service.tid,
                text_service.this::<ITfTextInputProcessor>()?,
                context,
            )
        };

        let requested = Rc::new(Cell::new(None));
        let callback = Rc::new({
            let context = context.clone();
            let requested = requested.clone();
            move |cookie| {
                requested.set(input_scope_mode_for_cookie(&context, cookie));
                Ok(())
            }
        });
        let completion = Rc::new({
            let this = this.clone();
            let context = context.clone();
            move || {
                let Some(mode) = requested.take() else {
                    return;
                };
                let factory = unsafe { this.as_impl() };
                let Ok(mut text_service) = factory.borrow_mut() else {
                    return;
                };
                // A newer focus target must not receive this field's mode.
                let still_focused = text_service
                    .layout_sink_context
                    .as_ref()
                    .is_some_and(|current| has_same_com_identity(current, &context));
                let composing = text_service
                    .borrow_composition()
                    .is_ok_and(|composition| composition.state != CompositionState::None);
                if still_focused && !composing {
                    // The mode switch resolves its target context from here; focus
                    // arrives before the first key, which would otherwise set it.
                    text_service.context = Some(context.clone());
                }
                drop(text_service);
                if !still_focused || composing || IMEState::keyboard_disabled().unwrap_or(true) {
                    return;
                }
                if IMEState::input_mode().is_ok_and(|current| current == mode) {
                    return;
                }
                if let Err(error) = factory.request_input_mode_switch_after_composition(mode) {
                    tracing::debug!(?error, "Skip InputScope input mode switch");
                }
            }
        });
        request_async_read_edit_session(tid, context, callback, completion)
    }
}

impl ITfThreadMgrEventSink_Impl for TextServiceFactory_Impl {
    #[macros::anyhow]
    fn OnInitDocumentMgr(&self, _pdim: Option<&ITfDocumentMgr>) -> Result<()> {
        Ok(())
    }

    #[macros::anyhow]
    fn OnUninitDocumentMgr(&self, _pdim: Option<&ITfDocumentMgr>) -> Result<()> {
        Ok(())
    }

    #[macros::anyhow]
    fn OnSetFocus(
        &self,
        focus: Option<&ITfDocumentMgr>,
        _prevfocus: Option<&ITfDocumentMgr>,
    ) -> Result<()> {
        // A modifier key-up can be delivered to the newly focused thread or
        // document instead of this text service.
        self.clear_tracked_modifier_key_state();

        // if focus is changed, the text layout sink should be updated
        if let Some(focus) = focus {
            self.borrow_mut()?.advise_text_layout_sink(focus.clone())?;
        }
        self.stop_translation_mode();
        self.end_composition_for_tsf_event();
        self.set_keyboard_disabled_for_document_mgr(focus)?;
        ensure_ipc_service_for_tsf_event("OnSetFocus");
        if let Err(error) = self.request_input_scope_mode_switch(focus) {
            tracing::debug!(?error, "Skip InputScope input mode switch on focus");
        }

        if focus.is_none() {
            let mut text_service = self.borrow_mut()?;
            let _ = text_service.unadvise_text_layout_sink();
            text_service.context = None;
        }

        Ok(())
    }

    #[macros::anyhow]
    fn OnPushContext(&self, _pic: Option<&ITfContext>) -> Result<()> {
        let _ = self.refresh_selection_context();
        Ok(())
    }

    #[macros::anyhow]
    fn OnPopContext(&self, _pic: Option<&ITfContext>) -> Result<()> {
        let _ = self.refresh_selection_context();
        Ok(())
    }
}

impl ITfThreadFocusSink_Impl for TextServiceFactory_Impl {
    #[macros::anyhow]
    fn OnSetThreadFocus(&self) -> Result<()> {
        self.clear_tracked_modifier_key_state();
        let focus = {
            let text_service = self.borrow()?;
            let thread_mgr = text_service.thread_mgr()?;
            unsafe { thread_mgr.GetFocus().ok() }
        };
        ensure_ipc_service_for_tsf_event("OnSetThreadFocus");
        self.set_keyboard_disabled_for_document_mgr(focus.as_ref())?;
        let _ = self.refresh_selection_context();

        Ok(())
    }

    #[macros::anyhow]
    fn OnKillThreadFocus(&self) -> Result<()> {
        self.clear_tracked_modifier_key_state();
        self.stop_translation_mode();
        self.end_composition_for_tsf_event();
        self.set_keyboard_disabled_state(true)?;

        Ok(())
    }
}
