use windows::Win32::UI::TextServices::{
    ITfContext, ITfDocumentMgr, ITfThreadFocusSink_Impl, ITfThreadMgrEventSink_Impl,
};

use anyhow::Result;

use crate::engine::state::{keyboard_disabled_from_context, IMEState};

use super::edit_session::has_same_com_identity;
use super::factory::{TextServiceFactory, TextServiceFactory_Impl};

fn ensure_ipc_service_for_tsf_event(event: &str) {
    match IMEState::ensure_ipc_service() {
        Ok(true) => tracing::debug!(event, "Initialized IPC service during TSF event"),
        Ok(false) => {}
        Err(error) => tracing::warn!(?error, event, "IPC service is unavailable during TSF event"),
    }
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
        self.end_composition_for_tsf_event();
        self.set_keyboard_disabled_for_document_mgr(focus)?;
        ensure_ipc_service_for_tsf_event("OnSetFocus");

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
        self.end_composition_for_tsf_event();
        self.set_keyboard_disabled_state(true)?;

        Ok(())
    }
}
