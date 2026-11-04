use windows::{
    core::Interface as _,
    Win32::UI::TextServices::{
        ITfContext, ITfContextView, ITfDocumentMgr, ITfSource, ITfTextEditSink, ITfTextLayoutSink,
        ITfTextLayoutSink_Impl, TfLayoutCode,
    },
};

use anyhow::Result;

use super::{factory::TextServiceFactory_Impl, text_service::TextService};

impl ITfTextLayoutSink_Impl for TextServiceFactory_Impl {
    // This function is called when the text display position changes when the IME is enabled.
    // However, this function **will not be called** in Microsoft Store applications such as Notepad, so be careful.
    #[macros::anyhow]
    fn OnLayoutChange(
        &self,
        pic: Option<&ITfContext>,
        _lcode: TfLayoutCode,
        _pview: Option<&ITfContextView>,
    ) -> Result<()> {
        if self
            .borrow()
            .is_ok_and(|service| service.selection_count.is_visible())
        {
            if let Some(context) = pic {
                let _ = self.request_selection_count_update(context);
            }
        }
        let should_skip = match self.borrow_mut() {
            Ok(mut text_service) => text_service
                .update_pos_state
                .should_skip_layout_change(std::time::Instant::now()),
            Err(error) => {
                tracing::warn!("Skip OnLayoutChange due to borrow conflict: {error:?}");
                true
            }
        };

        if should_skip {
            tracing::debug!("Skip layout-triggered update_pos to avoid feedback loop");
            return Ok(());
        }

        if let Err(error) = self.request_update_pos_async(pic) {
            tracing::warn!("Failed to request position update from OnLayoutChange: {error:?}");
        }

        Ok(())
    }
}

impl TextService {
    pub fn advise_text_layout_sink(&mut self, doc_mgr: ITfDocumentMgr) -> Result<()> {
        if self.layout_sink_context.is_some() {
            self.unadvise_text_layout_sink()?;
        }

        unsafe {
            let context = doc_mgr.GetTop()?;

            self.layout_sink_context = Some(context.clone());

            let cookie = context
                .cast::<ITfSource>()?
                .AdviseSink(&ITfTextLayoutSink::IID, &self.this::<ITfTextLayoutSink>()?)?;

            self.sink_cookies.insert(ITfTextLayoutSink::IID, cookie);

            // Selection changes (including mouse drags) are delivered independently
            // of layout notifications, which some hosts omit entirely.
            match context
                .cast::<ITfSource>()?
                .AdviseSink(&ITfTextEditSink::IID, &self.this::<ITfTextEditSink>()?)
            {
                Ok(cookie) => {
                    self.sink_cookies.insert(ITfTextEditSink::IID, cookie);
                }
                Err(error) => {
                    tracing::debug!(?error, "Host does not support selection notifications")
                }
            }

            Ok(())
        }
    }

    pub fn unadvise_text_layout_sink(&mut self) -> Result<()> {
        self.selection_count.destroy();
        self.selection_count_pending = false;
        self.selection_count_generation = self.selection_count_generation.wrapping_add(1);
        unsafe {
            if let Some(context) = self.layout_sink_context.take() {
                if let Some(cookie) = self.sink_cookies.remove(&ITfTextEditSink::IID) {
                    let _ = context.cast::<ITfSource>()?.UnadviseSink(cookie);
                }
                if let Some(cookie) = self.sink_cookies.remove(&ITfTextLayoutSink::IID) {
                    context.cast::<ITfSource>()?.UnadviseSink(cookie)?;
                }
            }

            Ok(())
        }
    }
}
