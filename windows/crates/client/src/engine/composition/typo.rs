//! Reversible correction and cancellable snapshot assistance on the TSF thread.
use super::super::ipc_service::InputLedgerSnapshot;
use super::*;
use std::{
    cell::{Cell, RefCell},
    sync::mpsc,
};
use windows::Win32::{
    Foundation::HWND,
    UI::WindowsAndMessaging::{KillTimer, SetTimer},
};

#[derive(Clone, Debug, Default)]
pub(super) struct TypoState {
    pub applied: Option<Rc<AppliedCorrection>>,
    pub rejected_surface: Option<String>,
    pub hold_auto_commit: bool,
    pub primary_assist: Option<u64>,
    pub prediction_continuation: String,
    accepted: Vec<(String, String)>,
}

#[derive(Debug)]
pub(super) struct AppliedCorrection {
    original: Composition,
    ledger: InputLedgerSnapshot,
    pub source: String,
    pub surface: String,
}

struct Pending {
    generation: u64,
    timer: usize,
    started: Instant,
    receiver: mpsc::Receiver<Option<shared::proto::GetAssistCandidatesResponse>>,
    task: tokio::task::JoinHandle<()>,
    correction_task: Option<tokio::task::JoinHandle<()>>,
    correction_receiver: Option<mpsc::Receiver<Option<shared::proto::GetAssistCandidatesResponse>>>,
    correction_result: Option<shared::proto::GetAssistCandidatesResponse>,
    prediction_result: Option<shared::proto::GetAssistCandidatesResponse>,
    prediction_done: bool,
    correction_done: bool,
    owner: ITfTextInputProcessor,
    context: ITfContext,
    composition: ITfComposition,
    raw_input: String,
    candidates: Candidates,
    context_key: (u64, u64, u64),
    model: (String, String),
}

impl Drop for Pending {
    fn drop(&mut self) {
        self.task.abort();
        if let Some(task) = &self.correction_task {
            task.abort();
        }
        unsafe {
            let _ = KillTimer(None, self.timer);
        }
    }
}
thread_local! {
    static PENDING: RefCell<Option<Pending>> = const { RefCell::new(None) };
    static GENERATION: Cell<u64> = const { Cell::new(0) };
}

fn receive(
    receiver: &mpsc::Receiver<Option<shared::proto::GetAssistCandidatesResponse>>,
) -> Option<Option<shared::proto::GetAssistCandidatesResponse>> {
    match receiver.try_recv() {
        Ok(result) => Some(result),
        Err(mpsc::TryRecvError::Disconnected) => Some(None),
        Err(mpsc::TryRecvError::Empty) => None,
    }
}

unsafe extern "system" fn poll(_window: HWND, _message: u32, id: usize, _time: u32) {
    let Some(mut pending) = PENDING.with(|slot| slot.borrow_mut().take()) else {
        return;
    };
    if pending.timer != id {
        PENDING.with(|slot| *slot.borrow_mut() = Some(pending));
        return;
    }
    if pending.started.elapsed() >= Duration::from_millis(1600) {
        return;
    }
    let mut results = Vec::new();
    if !pending.prediction_done {
        if let Some(result) = receive(&pending.receiver) {
            pending.prediction_done = true;
            pending.prediction_result = result;
        }
    }
    if !pending.correction_done {
        if let Some(result) = pending.correction_receiver.as_ref().and_then(receive) {
            pending.correction_done = true;
            pending.correction_result = result;
        }
    }
    // Briefly coalesce first results so fast continuation does not permanently
    // hide a stronger keyboard correction. Timers keep input fully asynchronous.
    // A displayed Tab target remains stable; late results still enter the list.
    if first_assist_ready(
        pending.prediction_done,
        pending.correction_done,
        pending
            .correction_result
            .as_ref()
            .is_some_and(|r| r.auto_apply),
        pending.started.elapsed(),
    ) {
        if let Some(result) = pending.prediction_result.take() {
            results.push((2, result));
        }
        if let Some(result) = pending.correction_result.take() {
            results.push((1, result));
        }
    }
    let factory = unsafe { pending.owner.as_impl() };
    if !results.is_empty() {
        if factory.apply_typo_assist_result(&pending, results).is_err() {
            return;
        }
        if pending.generation != GENERATION.with(Cell::get) {
            return;
        }
        // Only our own append is allowed to advance this immutable snapshot.
        if let Ok(service) = factory.borrow() {
            if let Ok(state) = service.borrow_composition() {
                pending.candidates = state.candidates.clone();
            }
        }
    }
    if !(pending.prediction_done && pending.correction_done) {
        PENDING.with(|slot| *slot.borrow_mut() = Some(pending));
    }
}

fn first_assist_ready(
    prediction_done: bool,
    correction_done: bool,
    automatic: bool,
    elapsed: Duration,
) -> bool {
    automatic || (prediction_done && (correction_done || elapsed >= Duration::from_millis(200)))
}

fn merge_results(
    state: &mut Composition,
    responses: Vec<(u32, shared::proto::GetAssistCandidatesResponse)>,
) -> Option<String> {
    let rejected = state.typo.rejected_surface.clone();
    let mut choices = Vec::new();
    let mut auto_apply = false;
    for (kind, response) in responses {
        let id = if kind == 1 {
            shared::ai_candidate::TYPO_CORRECTION
        } else {
            shared::ai_candidate::PREDICTIVE
        };
        auto_apply |= response.auto_apply;
        if kind == 2 {
            state.typo.prediction_continuation = response.continuation;
        }
        append_suggestions(
            &mut state.candidates,
            response.suggestions,
            rejected.as_deref(),
        );
        if state.candidates.candidate_ids.contains(&id) {
            choices.push((response.relevance, id));
        }
    }
    // Keep an already displayed Tab target stable until the next edit.
    if state.typo.primary_assist.is_none() {
        state.typo.primary_assist = choose_primary(&choices);
    }
    auto_apply
        .then(|| {
            state
                .candidates
                .candidate_ids
                .iter()
                .position(|id| *id == shared::ai_candidate::TYPO_CORRECTION)
                .and_then(|i| state.candidates.texts.get(i))
                .cloned()
        })
        .flatten()
}

fn choose_primary(choices: &[(u32, u64)]) -> Option<u64> {
    choices
        .iter()
        .max_by_key(|(score, id)| (*score, *id == shared::ai_candidate::PREDICTIVE))
        .map(|(_, id)| *id)
}

fn editable_composition(composition: &Composition) -> bool {
    composition.state != CompositionState::None
        && TextServiceFactory::jev_composition_eligible(composition, false)
        && composition
            .candidates
            .hiragana
            .chars()
            .any(|c| ('ぁ'..='ん').contains(&c) || composition.typo.applied.is_some())
        && (2..=512).contains(&composition.candidates.hiragana.chars().count())
}

fn eligible(composition: &Composition) -> bool {
    editable_composition(composition)
        && composition.state == CompositionState::Composing
        && composition.selection_index == 0
}

fn matches_snapshot(
    composition: &Composition,
    raw_input: &str,
    candidates: &Candidates,
    requested_generation: u64,
    current_generation: u64,
) -> bool {
    requested_generation == current_generation
        && eligible(composition)
        && composition.raw_input == raw_input
        && composition.candidates == *candidates
}

fn append_suggestions(
    candidates: &mut Candidates,
    suggestions: Vec<shared::proto::Suggestion>,
    rejected: Option<&str>,
) {
    for suggestion in suggestions.into_iter().take(8) {
        if suggestion.text.is_empty()
            || suggestion.text.chars().count() > 512
            || candidates.texts.first() == Some(&suggestion.text)
            || (candidates.texts.contains(&suggestion.text)
                && suggestion.candidate_id != shared::ai_candidate::TYPO_CORRECTION)
            || !shared::ai_candidate::is_ai_assist_candidate(suggestion.candidate_id)
            || rejected == Some(suggestion.text.as_str())
        {
            continue;
        }
        let at = if suggestion.candidate_id == shared::ai_candidate::TYPO_CORRECTION {
            usize::from(!candidates.texts.is_empty())
        } else {
            candidates.texts.len()
        };
        candidates.texts.insert(at, suggestion.text);
        // RPC subtext is a label/continuation, not an unconverted composition suffix.
        candidates.sub_texts.insert(at, String::new());
        candidates
            .corresponding_count
            .insert(at, suggestion.corresponding_count);
        candidates.candidate_ids.insert(at, suggestion.candidate_id);
    }
}

fn select_surface(candidates: &mut Candidates, surface: &str) {
    let count = candidates
        .corresponding_count
        .iter()
        .copied()
        .max()
        .unwrap_or(surface.chars().count() as i32);
    // An explicit surface stays at the top until the next ordinary edit.
    candidates.texts.insert(0, surface.into());
    candidates.sub_texts.insert(0, String::new());
    candidates.corresponding_count.insert(0, count);
    candidates.candidate_ids.insert(0, 0);
}

impl TextServiceFactory {
    pub(crate) fn cancel_typo_assist(&self) {
        GENERATION.with(|generation| generation.set(generation.get().wrapping_add(1)));
        let pending = PENDING.with(|pending| pending.borrow_mut().take());
        drop(pending);
    }

    pub(super) fn begin_typo_assist(&self) -> Result<()> {
        let config = IMEState::app_config_snapshot()?;
        let config = config.app_config();
        if !config.general.experimental_ai_assist
            || IMEState::input_mode()? == InputMode::Latin
            || IMEState::keyboard_disabled().unwrap_or(true)
        {
            return Ok(());
        }
        let (owner, context, composition, raw_input, candidates, corrected) = {
            let service = self.borrow()?;
            let state = service.borrow_composition()?;
            if !eligible(&state)
                || service.disabled_context_observed
                || state
                    .candidates
                    .candidate_ids
                    .iter()
                    .any(|id| shared::ai_candidate::is_ai_assist_candidate(*id))
            {
                return Ok(());
            }
            (
                service.this::<ITfTextInputProcessor>()?,
                service.context::<ITfContext>()?,
                state.tip_composition.clone().context("No composition")?,
                state.raw_input.clone(),
                state.candidates.clone(),
                state.typo.applied.is_some(),
            )
        };
        if self.current_context_has_sensitive_input_scope() {
            return Ok(());
        }
        // Direct replacement changes the input-ledger epoch. Resend context now,
        // before capturing its revision, rather than invalidating our own result.
        if corrected {
            let preview = self.borrow()?.borrow_composition()?.preview.clone();
            self.update_context(&preview)?;
        }
        let ipc = IMEState::ipc_service()?.context("No IPC service")?;
        let (sender, receiver) = mpsc::channel();
        let timer = unsafe { SetTimer(None, 0, 25, Some(poll)) };
        anyhow::ensure!(timer != 0, "Cannot schedule assistance completion");
        let request = shared::proto::GetAssistCandidatesRequest {
            reading: candidates.hiragana.clone(),
            raw_input: raw_input.clone(),
            baseline: format!(
                "{}{}",
                candidates.texts.first().map(String::as_str).unwrap_or(""),
                candidates
                    .sub_texts
                    .first()
                    .map(String::as_str)
                    .unwrap_or("")
            ),
            input_count: candidates
                .corresponding_count
                .iter()
                .copied()
                .max()
                .unwrap_or(0),
            request_id: timer as u64,
            kind: 2,
        };
        let task = ipc.assist_candidates_background(request.clone(), sender);
        let (correction_task, correction_receiver) = if corrected {
            (None, None)
        } else {
            let (sender, receiver) = mpsc::channel();
            let mut correction = request;
            correction.kind = 1;
            (
                Some(ipc.assist_candidates_background(correction, sender)),
                Some(receiver),
            )
        };
        PENDING.with(|pending| {
            *pending.borrow_mut() = Some(Pending {
                generation: GENERATION.with(Cell::get),
                timer,
                started: Instant::now(),
                receiver,
                task,
                correction_task,
                correction_receiver,
                correction_result: None,
                prediction_result: None,
                prediction_done: false,
                correction_done: corrected,
                owner,
                context,
                composition,
                raw_input,
                candidates,
                context_key: ipc.assist_snapshot_key(),
                model: (
                    config.general.experimental_ai_engine.clone(),
                    config.zenzai.backend.clone(),
                ),
            })
        });
        Ok(())
    }

    fn apply_typo_assist_result(
        &self,
        pending: &Pending,
        responses: Vec<(u32, shared::proto::GetAssistCandidatesResponse)>,
    ) -> Result<()> {
        let config = IMEState::app_config_snapshot()?;
        let config = config.app_config();
        let mut ipc = IMEState::ipc_service()?.context("No IPC service")?;
        if !config.general.experimental_ai_assist
            || IMEState::keyboard_disabled().unwrap_or(true)
            || self.current_context_has_sensitive_input_scope()
            || pending.context_key != ipc.assist_snapshot_key()
            || pending.model
                != (
                    config.general.experimental_ai_engine.clone(),
                    config.zenzai.backend.clone(),
                )
        {
            return Ok(());
        }
        if !self.pending_typo_is_current(pending)? {
            return Ok(());
        }
        let preview = self.borrow()?.borrow_composition()?.preview.clone();
        // Re-read surrounding text once at completion. Programmatic document
        // changes can alter the left context without a keyboard edit.
        self.update_context(&preview)?;
        ipc = IMEState::ipc_service()?.context("No IPC service")?;
        if pending.context_key != ipc.assist_snapshot_key()
            || !self.pending_typo_is_current(pending)?
        {
            return Ok(());
        }
        let (candidates, auto_surface) = {
            let service = self.borrow()?;
            let mut state = service.borrow_mut_composition()?;
            let auto_surface = merge_results(&mut state, responses);
            (state.candidates.clone(), auto_surface)
        };
        if let Some(surface) = auto_surface.filter(|_| !config.general.jev_conversion) {
            self.apply_typo_correction(&surface, false)?;
            return self.begin_typo_assist();
        }
        self.sync_candidate_window_after_text_update(
            &mut ipc,
            &candidates,
            0,
            config,
            &CompositionState::Composing,
        )
    }

    fn pending_typo_is_current(&self, pending: &Pending) -> Result<bool> {
        let service = self.borrow()?;
        let state = service.borrow_composition()?;
        Ok(!service.disabled_context_observed
            && has_same_com_identity(&service.context::<ITfContext>()?, &pending.context)
            && matches_snapshot(
                &state,
                &pending.raw_input,
                &pending.candidates,
                pending.generation,
                GENERATION.with(Cell::get),
            )
            && state
                .tip_composition
                .as_ref()
                .is_some_and(|tip| has_same_com_identity(tip, &pending.composition)))
    }

    pub(super) fn apply_typo_correction(&self, surface: &str, explicit: bool) -> Result<()> {
        self.cancel_typo_assist();
        if !IMEState::app_config_snapshot()?
            .app_config()
            .general
            .experimental_ai_assist
            || IMEState::keyboard_disabled().unwrap_or(true)
        {
            return Ok(());
        }
        let mut original = self.borrow()?.borrow_composition()?.clone();
        if !editable_composition(&original)
            || original.typo.applied.is_some()
            || !original
                .candidates
                .candidate_ids
                .iter()
                .enumerate()
                .any(|(i, id)| {
                    *id == shared::ai_candidate::TYPO_CORRECTION
                        && original
                            .candidates
                            .texts
                            .get(i)
                            .is_some_and(|s| s == surface)
                })
        {
            return Ok(());
        }
        if original
            .candidates
            .candidate_ids
            .get(original.selection_index.max(0) as usize)
            == Some(&shared::ai_candidate::TYPO_CORRECTION)
        {
            // A highlighted correction is already previewed. Undo must restore
            // the normal baseline rather than previewing that same correction again.
            if let Some(baseline) = Self::select_candidate(&original.candidates, 0) {
                original.preview = baseline.text;
                original.suffix = baseline.sub_text;
                original.selection_index = 0;
                original.corresponding_count = baseline.corresponding_count;
            }
        }
        let mut ipc = IMEState::ipc_service()?.context("No IPC service")?;
        let ledger = ipc.input_ledger_snapshot();
        let source = original.candidates.hiragana.clone();
        let mut candidates = match ipc.replace_with_direct_surface(surface) {
            Ok(candidates) => candidates,
            Err(error) => {
                // The old ledger and displayed composition remain authoritative.
                // Shared IPC recovery reconstructs them before the next ordinary edit.
                tracing::debug!(?error, "Correction replacement unavailable; retain input");
                return Ok(());
            }
        };
        select_surface(&mut candidates, surface);
        // Persist canonical converter input before any reentrant TSF edit.
        let mut accepted = original.typo.accepted.clone();
        original.typo = TypoState {
            rejected_surface: Some(surface.into()),
            accepted: accepted.clone(),
            ..Default::default()
        };
        if explicit {
            accepted.push((source.clone(), surface.into()));
            accepted.truncate(8);
        }
        let applied = Rc::new(AppliedCorrection {
            original,
            ledger,
            source,
            surface: surface.into(),
        });
        {
            let service = self.borrow()?;
            let mut state = service.borrow_mut_composition()?;
            state.state = CompositionState::Composing;
            state.preview = surface.into();
            state.suffix.clear();
            state.raw_input = surface.into();
            state.raw_hiragana = candidates.hiragana.clone();
            state.corresponding_count = candidates.corresponding_count[0];
            state.selection_index = 0;
            state.candidates = candidates.clone();
            state.live_conversion_history.clear();
            state.live_conversion_snapshots.clear();
            state.typo = TypoState {
                applied: Some(applied),
                hold_auto_commit: true,
                rejected_surface: None,
                accepted,
                ..Default::default()
            };
        }
        IMEState::set_ipc_service(ipc.clone())?;
        if let Err(error) = self.set_text(surface, "") {
            // Timer completions can race a TSF write lock. Restore the converter
            // and client snapshot even when the document already kept its old text.
            let _ = self.undo_typo_correction();
            return Err(error);
        }
        let config = IMEState::app_config_snapshot()?;
        self.sync_candidate_window_after_text_update(
            &mut ipc,
            &candidates,
            0,
            config.app_config(),
            &CompositionState::Composing,
        )
    }

    pub(super) fn undo_typo_correction(&self) -> Result<()> {
        self.cancel_typo_assist();
        let Some(applied) = self.borrow()?.borrow_composition()?.typo.applied.clone() else {
            return Ok(());
        };
        let mut ipc = IMEState::ipc_service()?.context("No IPC service")?;
        let mut candidates = match ipc.replace_from_snapshot(&applied.ledger) {
            Ok(candidates) => candidates,
            Err(error) => {
                tracing::debug!(?error, "Correction undo unavailable; retain undo snapshot");
                return Ok(());
            }
        };
        let baseline = format!("{}{}", applied.original.preview, applied.original.suffix);
        select_surface(&mut candidates, &baseline);
        let mut restored = applied.original.clone();
        restored.state = CompositionState::Composing;
        restored.preview = baseline.clone();
        restored.suffix.clear();
        restored.candidates = candidates.clone();
        restored.selection_index = 0;
        restored.corresponding_count = candidates.corresponding_count[0];
        *self.borrow()?.borrow_mut_composition()? = restored;
        IMEState::set_ipc_service(ipc.clone())?;
        self.set_text(&baseline, "")?;
        let config = IMEState::app_config_snapshot()?;
        self.sync_candidate_window_after_text_update(
            &mut ipc,
            &candidates,
            0,
            config.app_config(),
            &CompositionState::Composing,
        )
    }

    pub(super) fn typo_acceptances_for_commit(&self, displayed: &str) -> Vec<(String, String)> {
        self.borrow()
            .ok()
            .and_then(|service| {
                service.borrow_composition().ok().map(|state| {
                    let mut accepted: Vec<_> = state
                        .typo
                        .accepted
                        .iter()
                        .filter(|(_, surface)| displayed.contains(surface))
                        .cloned()
                        .collect();
                    // Space/arrow selection is also an explicit acceptance. Learn
                    // only after the selected whole-composition surface commits.
                    if state.fixed_prefix.is_empty()
                        && state.clause_snapshots.is_empty()
                        && state.future_clause_snapshots.is_empty()
                    {
                        if let Some(selected) =
                            Self::select_candidate(&state.candidates, state.selection_index)
                        {
                            if selected.candidate_id == shared::ai_candidate::TYPO_CORRECTION
                                && selected.text == displayed
                            {
                                let pair = (state.candidates.hiragana.clone(), selected.text);
                                if !accepted.contains(&pair) {
                                    accepted.push(pair);
                                }
                            }
                        }
                    }
                    accepted
                })
            })
            .unwrap_or_default()
    }

    pub(super) fn finish_typo_undo_for_actions(&self, actions: &[ClientAction]) -> Result<()> {
        if actions.is_empty() {
            return Ok(());
        }
        let service = self.borrow()?;
        let mut state = service.borrow_mut_composition()?;
        state.typo.applied = None;
        state.typo.primary_assist = None;
        state.typo.prediction_continuation.clear();
        // Retain the hold through the first following edit; the ordinary stability
        // window must build up again before automatic prefix commit is possible.
        if state.typo.hold_auto_commit {
            state.live_conversion_snapshots.clear();
            state.live_conversion_history.clear();
        }
        if !actions
            .iter()
            .any(|action| matches!(action, ClientAction::ShowCandidateWindow))
        {
            state.typo.rejected_surface = None;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn response(id: u64, text: &str, relevance: u32) -> shared::proto::GetAssistCandidatesResponse {
        shared::proto::GetAssistCandidatesResponse {
            suggestions: vec![shared::proto::Suggestion {
                text: text.into(),
                candidate_id: id,
                corresponding_count: 4,
                subtext: String::new(),
            }],
            relevance,
            continuation: "いたします。".into(),
            ..Default::default()
        }
    }
    #[test]
    fn first_prediction_waits_briefly_for_correction_but_never_blocks_auto_or_waits_unbounded() {
        assert!(!first_assist_ready(
            true,
            false,
            false,
            Duration::from_millis(50)
        ));
        assert!(first_assist_ready(
            true,
            true,
            false,
            Duration::from_millis(130)
        ));
        assert!(first_assist_ready(
            true,
            false,
            false,
            Duration::from_millis(200)
        ));
        assert!(first_assist_ready(
            false,
            true,
            true,
            Duration::from_millis(1)
        ));
        assert!(!first_assist_ready(
            false,
            true,
            false,
            Duration::from_millis(130)
        ));
    }

    #[test]
    fn bounded_keyboard_proposal_precedes_continuation_without_automatic_apply() {
        let mut state = Composition {
            candidates: Candidates {
                texts: vec!["資料を送りmっす".into()],
                sub_texts: vec![String::new()],
                hiragana: "しりょうをおくりmっす".into(),
                candidate_ids: vec![0],
                corresponding_count: vec![16],
            },
            ..Default::default()
        };
        let automatic = merge_results(
            &mut state,
            vec![
                (
                    1,
                    response(shared::ai_candidate::TYPO_CORRECTION, "資料を送ります", 80),
                ),
                (
                    2,
                    response(shared::ai_candidate::PREDICTIVE, "資料を送りmっす。", 75),
                ),
            ],
        );
        assert_eq!(automatic, None);
        assert_eq!(
            state.typo.primary_assist,
            Some(shared::ai_candidate::TYPO_CORRECTION)
        );
        assert_eq!(state.candidates.texts[0], "資料を送りmっす");
    }

    #[test]
    fn weak_correction_cannot_hide_prediction_or_change_displayed_tab_target() {
        let mut state = Composition {
            candidates: Candidates {
                texts: vec!["よろしくお願い".into()],
                sub_texts: vec![String::new()],
                hiragana: "よろしくおねがい".into(),
                candidate_ids: vec![0],
                corresponding_count: vec![4],
            },
            ..Default::default()
        };
        merge_results(
            &mut state,
            vec![
                (
                    1,
                    response(
                        shared::ai_candidate::TYPO_CORRECTION,
                        "宜しくお願いします",
                        30,
                    ),
                ),
                (
                    2,
                    response(
                        shared::ai_candidate::PREDICTIVE,
                        "よろしくお願いいたします。",
                        75,
                    ),
                ),
            ],
        );
        assert_eq!(
            state.typo.primary_assist,
            Some(shared::ai_candidate::PREDICTIVE)
        );
        assert_eq!(state.candidates.texts[0], "よろしくお願い");
        assert!(state
            .candidates
            .candidate_ids
            .contains(&shared::ai_candidate::TYPO_CORRECTION));
        merge_results(
            &mut state,
            vec![(
                1,
                response(
                    shared::ai_candidate::TYPO_CORRECTION,
                    "宜しくお願いします",
                    90,
                ),
            )],
        );
        assert_eq!(
            state.typo.primary_assist,
            Some(shared::ai_candidate::PREDICTIVE)
        );
        assert_eq!(
            choose_primary(&[
                (70, shared::ai_candidate::TYPO_CORRECTION),
                (45, shared::ai_candidate::PREDICTIVE)
            ]),
            Some(shared::ai_candidate::TYPO_CORRECTION)
        );
    }
    #[test]
    fn late_result_cannot_overtake_edit_retype_selection_or_clause_navigation() {
        let mut state = Composition {
            raw_input: "okseri".into(),
            state: CompositionState::Composing,
            candidates: Candidates {
                hiragana: "おkせり".into(),
                texts: vec!["おkせり".into()],
                ..Default::default()
            },
            ..Default::default()
        };
        let snapshot = state.candidates.clone();
        assert!(matches_snapshot(&state, "okseri", &snapshot, 1, 1));
        state.raw_input.pop();
        assert!(!matches_snapshot(&state, "okseri", &snapshot, 1, 1));
        state.raw_input.push('i');
        assert!(
            !matches_snapshot(&state, "okseri", &snapshot, 1, 2),
            "same text after retyping is a different request"
        );
        for phase in [
            CompositionState::Previewing,
            CompositionState::Selecting,
            CompositionState::None,
        ] {
            state.state = phase;
            assert!(!matches_snapshot(&state, "okseri", &snapshot, 1, 1));
        }
        state.state = CompositionState::Composing;
        state.fixed_prefix = "既存の文節".into();
        assert!(!matches_snapshot(&state, "okseri", &snapshot, 1, 1));
        state.fixed_prefix.clear();
        state.selection_index = 1;
        assert!(!matches_snapshot(&state, "okseri", &snapshot, 1, 1));
    }
    #[test]
    fn correction_tab_applies_without_commit_and_escape_restores() {
        let mut state = Composition {
            raw_input: "okseri".into(),
            state: CompositionState::Composing,
            candidates: Candidates {
                texts: vec!["おkせり".into(), "おかえり".into()],
                sub_texts: vec![String::new(); 2],
                hiragana: "おkせり".into(),
                corresponding_count: vec![6; 2],
                candidate_ids: vec![1, shared::ai_candidate::TYPO_CORRECTION],
            },
            ..Default::default()
        };
        let plan = CompositionReducer::plan_actions_for_user_action(
            &state,
            &UserAction::Tab,
            &InputMode::Kana,
            false,
            &AppConfig::default(),
            false,
        )
        .unwrap();
        assert_eq!(
            plan,
            (
                CompositionState::Composing,
                vec![ClientAction::ApplyTypoCorrection("おかえり".into())]
            )
        );
        let ipc = IPCService::recovery_for_test(false);
        state.typo.applied = Some(Rc::new(AppliedCorrection {
            original: state.clone(),
            ledger: ipc.input_ledger_snapshot(),
            source: "おkせり".into(),
            surface: "おかえり".into(),
        }));
        let plan = CompositionReducer::plan_actions_for_user_action(
            &state,
            &UserAction::Escape,
            &InputMode::Kana,
            false,
            &AppConfig::default(),
            false,
        )
        .unwrap();
        assert_eq!(
            plan,
            (
                CompositionState::Composing,
                vec![ClientAction::UndoTypoCorrection]
            )
        );
        merge_results(
            &mut state,
            vec![(
                2,
                response(shared::ai_candidate::PREDICTIVE, "おかえりなさい。", 75),
            )],
        );
        let plan = CompositionReducer::plan_actions_for_user_action(
            &state,
            &UserAction::Tab,
            &InputMode::Kana,
            false,
            &AppConfig::default(),
            false,
        )
        .unwrap();
        assert_eq!(
            plan,
            (
                CompositionState::None,
                vec![
                    ClientAction::CommitWithText("おかえりなさい。".into()),
                    ClientAction::EndComposition
                ]
            )
        );
        let plan = CompositionReducer::plan_actions_for_user_action(
            &state,
            &UserAction::Escape,
            &InputMode::Kana,
            false,
            &AppConfig::default(),
            false,
        )
        .unwrap();
        assert_eq!(plan.1, vec![ClientAction::UndoTypoCorrection]);
    }
    #[test]
    fn rejected_correction_stays_hidden_and_labels_never_enter_document() {
        let mut candidates = Candidates::default();
        let suggestion = shared::proto::Suggestion {
            text: "おかえり".into(),
            subtext: "補正".into(),
            corresponding_count: 6,
            candidate_id: shared::ai_candidate::TYPO_CORRECTION,
        };
        append_suggestions(&mut candidates, vec![suggestion.clone()], Some("おかえり"));
        assert!(candidates.texts.is_empty());
        append_suggestions(&mut candidates, vec![suggestion], None);
        assert_eq!(candidates.sub_texts, vec![""]);
    }

    #[test]
    fn correction_is_second_and_keeps_normal_candidate_order_and_identity() {
        let mut candidates = Candidates {
            texts: vec!["原候補".into(), "通常の別候補".into()],
            sub_texts: vec![String::new(); 2],
            candidate_ids: vec![10, 20],
            corresponding_count: vec![6; 2],
            hiragana: "おkせり".into(),
        };
        append_suggestions(
            &mut candidates,
            vec![shared::proto::Suggestion {
                text: "おかえり".into(),
                candidate_id: shared::ai_candidate::TYPO_CORRECTION,
                corresponding_count: 6,
                subtext: "補正".into(),
            }],
            None,
        );
        assert_eq!(candidates.texts, vec!["原候補", "おかえり", "通常の別候補"]);
        assert_eq!(
            candidates.candidate_ids,
            vec![10, shared::ai_candidate::TYPO_CORRECTION, 20]
        );
        assert_eq!(candidates.corresponding_count, vec![6; 3]);
        let factory = TextServiceFactory::default();
        *factory.borrow().unwrap().borrow_mut_composition().unwrap() = Composition {
            candidates,
            selection_index: 1,
            state: CompositionState::Selecting,
            ..Default::default()
        };
        assert_eq!(
            factory.typo_acceptances_for_commit("おかえり"),
            vec![("おkせり".into(), "おかえり".into())]
        );
        assert!(factory
            .typo_acceptances_for_commit("通常の別候補")
            .is_empty());
    }
}
