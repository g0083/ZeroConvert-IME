//! Reversible correction and cancellable snapshot assistance on the TSF thread.
use super::super::ipc_service::{AssistOutcome, InputLedgerSnapshot};
use super::*;
use std::{
    cell::{Cell, RefCell},
    sync::mpsc,
};
use windows::Win32::{
    Foundation::HWND,
    UI::WindowsAndMessaging::{KillTimer, SetTimer},
};

/// Why translation mode shows an error. `InvalidSource` and `Truncated` are
/// not produced yet: an invalid source never sends a request, and a
/// limit-stopped response is indistinguishable from a failure without a
/// protocol extension.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum TranslationError {
    /// The server was ready but no result arrived within the RPC timeout.
    Timeout,
    /// The server never became ready within the preparation wait.
    Unavailable,
}

#[derive(Clone, Debug, Default)]
pub(super) struct TypoState {
    pub applied: Option<Rc<AppliedCorrection>>,
    pub correction: shared::assist_policy::CorrectionState,
    pub primary_assist: Option<u64>,
    pub translation_error: Option<TranslationError>,
    pub prediction_continuation: String,
}

#[derive(Clone, Debug)]
pub(super) struct AppliedCorrection {
    original: Composition,
    ledger: InputLedgerSnapshot,
    pub explicit: bool,
    pub refined: bool,
    pub source: String,
    pub source_raw_input: String,
    pub surface: String,
    pub converter_input: String,
    pub applied_hiragana: String,
    pub edited_after_apply: bool,
}

impl AppliedCorrection {
    /// Returns the pre-correction displayed surface for focused diff UI,
    /// falling back to the original baseline candidate or raw reading.
    pub(super) fn baseline_surface(&self) -> String {
        if self.refined {
            return self.source.clone();
        }
        let preview = format!("{}{}", self.original.preview, self.original.suffix);
        if !preview.is_empty() {
            return preview;
        }
        baseline_surface_from_candidates(&self.original.candidates)
            .unwrap_or_else(|| self.source.clone())
    }
}

pub(super) fn build_auto_refinement_inputs(
    applied: &AppliedCorrection,
    current_raw_input: &str,
    current_hiragana: &str,
) -> Option<(String, String, String)> {
    if applied.explicit
        || !applied.edited_after_apply
        || applied.applied_hiragana.is_empty()
        || !is_reading_only_correction(&applied.converter_input)
    {
        return None;
    }
    let trailing_hira = current_hiragana
        .strip_prefix(&applied.applied_hiragana)
        .filter(|t| !t.is_empty())?;
    let origin_reading = format!("{}{}", applied.source, trailing_hira);
    let is_raw_romaji =
        |s: &str| !s.is_empty() && s.bytes().all(|c| c.is_ascii_lowercase() || c == b'-');
    let trailing_raw = current_raw_input.strip_prefix(&applied.converter_input);
    let origin_raw_input = match trailing_raw {
        Some(trailing) if is_raw_romaji(&applied.source_raw_input) && is_raw_romaji(trailing) => {
            let single_trailing_n = applied.source_raw_input.ends_with('n')
                && !applied.source_raw_input.ends_with("nn");
            let needs_n_sep = single_trailing_n
                && trailing
                    .bytes()
                    .next()
                    .is_some_and(|b| matches!(b, b'a' | b'i' | b'u' | b'e' | b'o' | b'y' | b'n'));
            if needs_n_sep {
                format!("{}n{}", applied.source_raw_input, trailing)
            } else {
                format!("{}{}", applied.source_raw_input, trailing)
            }
        }
        _ => String::new(),
    };
    Some((
        origin_reading,
        origin_raw_input,
        applied.applied_hiragana.clone(),
    ))
}

/// Extracts the non-AI baseline conversion surface from `candidates`, if present.
pub(super) fn baseline_surface_from_candidates(candidates: &Candidates) -> Option<String> {
    let idx = (0..candidates.texts.len()).find(|&i| {
        !matches!(
            candidates.candidate_ids.get(i).copied(),
            Some(
                shared::ai_candidate::TYPO_CORRECTION
                    | shared::ai_candidate::PREDICTIVE
                    | shared::ai_candidate::TRANSLATION
            )
        )
    })?;
    let text = candidates.texts.get(idx).map(String::as_str).unwrap_or("");
    let sub_text = candidates
        .sub_texts
        .get(idx)
        .map(String::as_str)
        .unwrap_or("");
    let surface = format!("{text}{sub_text}");
    (!surface.is_empty()).then_some(surface)
}

struct Pending {
    translation: bool,
    generation: u64,
    timer: usize,
    started: Instant,
    receiver: Option<mpsc::Receiver<AssistOutcome>>,
    task: Option<tokio::task::JoinHandle<()>>,
    correction_task: Option<tokio::task::JoinHandle<()>>,
    correction_receiver: Option<mpsc::Receiver<AssistOutcome>>,
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
    translation_error: Option<TranslationError>,
    model: (String, String),
    features: (
        bool,
        shared::assist_policy::AutoTypoCorrectionStrength,
        bool,
    ),
}

impl Drop for Pending {
    fn drop(&mut self) {
        if let Some(task) = &self.task {
            task.abort();
        }
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

fn receive(receiver: &mpsc::Receiver<AssistOutcome>) -> Option<AssistOutcome> {
    match receiver.try_recv() {
        Ok(outcome) => Some(outcome),
        Err(mpsc::TryRecvError::Disconnected) => Some(AssistOutcome::Done(None)),
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
    if pending.started.elapsed()
        >= Duration::from_millis(if pending.translation { 42_000 } else { 1600 })
    {
        return;
    }
    let mut results = Vec::new();
    if !pending.prediction_done {
        if let Some(outcome) = pending.receiver.as_ref().and_then(receive) {
            pending.prediction_done = true;
            match outcome {
                AssistOutcome::Done(result) => {
                    pending.prediction_result = if pending.translation {
                        Some(result.unwrap_or_default())
                    } else {
                        result
                    };
                }
                AssistOutcome::TranslationUnavailable => {
                    pending.translation_error = Some(TranslationError::Unavailable);
                    pending.prediction_result = pending.translation.then(Default::default);
                }
            }
        }
    }
    if !pending.correction_done {
        if let Some(outcome) = pending.correction_receiver.as_ref().and_then(receive) {
            pending.correction_done = true;
            pending.correction_result = match outcome {
                AssistOutcome::Done(result) => result,
                AssistOutcome::TranslationUnavailable => None,
            };
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
            .is_some_and(|r| r.auto_apply || r.revert_to_origin || !r.suggestions.is_empty()),
        pending.started.elapsed(),
    ) {
        if let Some(result) = pending.prediction_result.take() {
            results.push((if pending.translation { 3 } else { 2 }, result));
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
    shared::assist_policy::first_result_ready(
        prediction_done,
        correction_done,
        automatic,
        elapsed.as_millis() as u64,
    )
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum MergeOutcome {
    None,
    AutoApply(String),
    AutoRevert,
}

fn merge_results(
    state: &mut Composition,
    responses: Vec<(u32, shared::proto::GetAssistCandidatesResponse)>,
) -> MergeOutcome {
    let correction = state.typo.correction.clone();
    let mut choices = Vec::new();
    let mut auto_apply = false;
    let mut revert_to_origin = false;
    for (kind, response) in responses {
        let id = if kind == 3 {
            shared::ai_candidate::TRANSLATION
        } else if kind == 1 {
            shared::ai_candidate::TYPO_CORRECTION
        } else {
            shared::ai_candidate::PREDICTIVE
        };
        if kind == 1 && response.revert_to_origin {
            revert_to_origin = true;
            continue;
        }
        auto_apply |= kind == 1 && response.auto_apply;
        if kind == 2 {
            state.typo.prediction_continuation = response.continuation;
        }
        append_suggestions(&mut state.candidates, response.suggestions, &correction);
        if state.candidates.candidate_ids.contains(&id) {
            choices.push((response.relevance, id));
        }
    }
    // Prefer correction/translation over prediction even if prediction appeared first.
    state.typo.primary_assist =
        shared::assist_policy::choose_primary(&choices, state.typo.primary_assist);
    if revert_to_origin {
        return MergeOutcome::AutoRevert;
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
        .map_or(MergeOutcome::None, MergeOutcome::AutoApply)
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

pub(super) fn is_reading_only_correction(surface: &str) -> bool {
    !surface.is_empty()
        && surface.chars().all(|c| {
            ('\u{3041}'..='\u{3096}').contains(&c)
                || matches!(
                    c,
                    '\u{309D}'
                        | '\u{309E}'
                        | '\u{30FC}'
                        | '、'
                        | '。'
                        | '！'
                        | '？'
                        | '!'
                        | '?'
                        | '・'
                )
        })
}

fn append_suggestions(
    candidates: &mut Candidates,
    suggestions: Vec<shared::proto::Suggestion>,
    correction: &shared::assist_policy::CorrectionState,
) {
    for suggestion in suggestions.into_iter().take(8) {
        if suggestion.text.is_empty()
            || suggestion.text.chars().count() > 512
            || candidates.texts.first() == Some(&suggestion.text)
            || (candidates.texts.contains(&suggestion.text)
                && suggestion.candidate_id != shared::ai_candidate::TYPO_CORRECTION)
            || !shared::ai_candidate::is_ai_assist_candidate(suggestion.candidate_id)
            || (suggestion.candidate_id == shared::ai_candidate::TYPO_CORRECTION
                && correction.is_rejected(&candidates.hiragana, &suggestion.text))
        {
            continue;
        }
        let at = if suggestion.candidate_id == shared::ai_candidate::TYPO_CORRECTION {
            usize::from(!candidates.texts.is_empty())
        } else {
            candidates.texts.len()
        };
        if !suggestion.prediction_reading.is_empty() {
            candidates
                .prediction_readings
                .insert(suggestion.text.clone(), suggestion.prediction_reading);
        }
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

fn translation_surface(candidates: &Candidates) -> Option<&str> {
    candidates
        .candidate_ids
        .iter()
        .position(|id| *id == shared::ai_candidate::TRANSLATION)
        .and_then(|i| candidates.texts.get(i))
        .map(String::as_str)
}

/// Absence of a translation surface is a timeout unless the background task
/// reported that the server never became ready within the preparation wait.
fn translation_error_from_outcome(
    unavailable: bool,
    has_surface: bool,
) -> Option<TranslationError> {
    if has_surface {
        return None;
    }
    if unavailable {
        Some(TranslationError::Unavailable)
    } else {
        Some(TranslationError::Timeout)
    }
}

impl TranslationError {
    pub(super) fn ui_key(self) -> &'static str {
        match self {
            TranslationError::Timeout => "timeout",
            TranslationError::Unavailable => "unavailable",
        }
    }
}

impl TextServiceFactory {
    pub(crate) fn translation_feature_enabled(&self) -> Result<bool> {
        Ok(IMEState::app_config_snapshot()?
            .app_config()
            .general
            .experimental_translation)
    }

    pub(crate) fn input_config_snapshot(&self) -> Result<crate::engine::state::AppConfigSnapshot> {
        Ok(IMEState::app_config_snapshot()?
            .for_translation_mode(self.borrow()?.translation_session.is_some()))
    }

    pub(crate) fn stop_translation_mode(&self) {
        if !self
            .borrow()
            .is_ok_and(|service| service.translation_session.is_some())
        {
            return;
        }
        self.cancel_typo_assist();
        if let Ok(mut service) = self.borrow_mut() {
            service.translation_session = None;
        }
        if let Ok(Some(mut ipc)) = IMEState::ipc_service() {
            let _ = ipc.update_candidate_window(Some(false), None, None, None, None);
        }
    }

    pub(crate) fn toggle_translation_mode(&self, context: Option<&ITfContext>) -> Result<()> {
        if let Some(context) = context {
            self.borrow_mut()?.context = Some(context.clone());
        }
        if self.borrow()?.translation_session.is_some() {
            if self.borrow()?.borrow_composition()?.state != CompositionState::None {
                self.handle_action(
                    &[ClientAction::RemoveText, ClientAction::EndComposition],
                    CompositionState::None,
                )?;
            }
            self.stop_translation_mode();
            return Ok(());
        }
        if self.current_context_has_sensitive_input_scope() {
            return Ok(());
        }
        self.cancel_typo_assist();
        if IMEState::input_mode()? == InputMode::Latin {
            self.handle_action(
                &[ClientAction::SetIMEMode(InputMode::Kana)],
                CompositionState::None,
            )?;
        }
        self.borrow_mut()?.translation_session =
            Some(shared::translation_session::TranslationSessionGuard::start()?);
        let mut ipc = IMEState::ipc_service()?.context("No IPC service")?;
        let (candidates, cursor_position) = {
            let service = self.borrow()?;
            let comp = service.borrow_composition()?;
            (comp.candidates.clone(), comp.cursor_position)
        };
        self.sync_candidate_window_update(
            &mut ipc,
            &candidates,
            0,
            Some(true),
            true,
            Some(""),
            Some(false),
            None,
            cursor_position,
        )?;
        self.begin_typo_assist()
    }

    pub(crate) fn cancel_typo_assist(&self) {
        GENERATION.with(|generation| generation.set(generation.get().wrapping_add(1)));
        let pending = PENDING.with(|pending| pending.borrow_mut().take());
        drop(pending);
    }

    pub(super) fn begin_typo_assist(&self) -> Result<()> {
        self.begin_typo_assist_inner(false)
    }

    fn begin_typo_assist_inner(&self, suppress_correction: bool) -> Result<()> {
        let config = self.input_config_snapshot()?;
        let config = config.app_config();
        let translation = config.general.experimental_translation;
        if (!translation
            && !config.general.experimental_typo_correction
            && !config.general.experimental_ai_assist)
            || IMEState::input_mode()? == InputMode::Latin
            || IMEState::keyboard_disabled().unwrap_or(true)
        {
            return Ok(());
        }
        let (owner, context, composition, raw_input, candidates, corrected, applied) = {
            let service = self.borrow()?;
            let state = service.borrow_composition()?;
            if !eligible(&state)
                || (translation
                    && state
                        .candidates
                        .hiragana
                        .chars()
                        .last()
                        .is_some_and(|c| c.is_ascii_alphabetic()))
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
                suppress_correction || state.typo.correction.is_applied(),
                state.typo.applied.clone(),
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
        let baseline_reading = candidates
            .texts
            .first()
            .and_then(|surface| candidates.prediction_readings.get(surface))
            .cloned()
            .unwrap_or_default();
        let plan = shared::assist_policy::request_plan(
            config.general.experimental_typo_correction && !translation,
            config.general.experimental_ai_assist && !translation,
            corrected,
            &candidates.hiragana,
            &baseline_reading,
        );
        if !translation && !plan.correction && !plan.prediction {
            return Ok(());
        }
        let ipc = IMEState::ipc_service()?.context("No IPC service")?;
        let timer = unsafe { SetTimer(None, 0, 25, Some(poll)) };
        anyhow::ensure!(timer != 0, "Cannot schedule assistance completion");
        let refinement_inputs = applied
            .as_deref()
            .and_then(|a| build_auto_refinement_inputs(a, &raw_input, &candidates.hiragana));
        let request = shared::proto::GetAssistCandidatesRequest {
            baseline_reading,
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
            kind: if translation { 3 } else { 2 },
            origin_reading: String::new(),
            origin_raw_input: String::new(),
            applied_prefix: String::new(),
        };
        let (task, receiver) = if translation || plan.prediction {
            let (sender, receiver) = mpsc::channel();
            (
                Some(ipc.assist_candidates_background(request.clone(), sender)),
                Some(receiver),
            )
        } else {
            (None, None)
        };
        let (correction_task, correction_receiver) = if plan.correction {
            let (sender, receiver) = mpsc::channel();
            let mut correction = request;
            correction.kind = 1;
            if let Some((origin_reading, origin_raw_input, applied_prefix)) = refinement_inputs {
                correction.origin_reading = origin_reading;
                correction.origin_raw_input = origin_raw_input;
                correction.applied_prefix = applied_prefix;
            }
            (
                Some(ipc.assist_candidates_background(correction, sender)),
                Some(receiver),
            )
        } else {
            (None, None)
        };
        PENDING.with(|pending| {
            *pending.borrow_mut() = Some(Pending {
                translation,
                generation: GENERATION.with(Cell::get),
                timer,
                started: Instant::now(),
                receiver,
                task,
                correction_task,
                correction_receiver,
                correction_result: None,
                prediction_result: None,
                prediction_done: !translation && !plan.prediction,
                correction_done: !plan.correction,
                owner,
                context,
                composition,
                raw_input,
                candidates,
                context_key: ipc.assist_snapshot_key(),
                translation_error: None,
                model: (
                    config.general.experimental_ai_engine.clone(),
                    config.zenzai.backend.clone(),
                ),
                features: (
                    config.general.experimental_typo_correction,
                    config.general.auto_typo_correction,
                    config.general.experimental_ai_assist,
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
        let config = self.input_config_snapshot()?;
        let config = config.app_config();
        let mut ipc = IMEState::ipc_service()?.context("No IPC service")?;
        if pending.translation != config.general.experimental_translation
            || pending.features
                != (
                    config.general.experimental_typo_correction,
                    config.general.auto_typo_correction,
                    config.general.experimental_ai_assist,
                )
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
        let (candidates, outcome) = {
            let service = self.borrow()?;
            let mut state = service.borrow_mut_composition()?;
            let outcome = merge_results(&mut state, responses);
            (state.candidates.clone(), outcome)
        };
        if !config.general.jev_conversion && config.general.auto_typo_correction.is_enabled() {
            match outcome {
                MergeOutcome::AutoRevert => {
                    self.auto_revert_typo_correction()?;
                    return self.begin_typo_assist_inner(true);
                }
                MergeOutcome::AutoApply(surface) => {
                    self.apply_typo_correction(&surface, false)?;
                    return self.begin_typo_assist();
                }
                MergeOutcome::None => {}
            }
        }
        if pending.translation {
            let error = translation_error_from_outcome(
                pending.translation_error.is_some(),
                translation_surface(&candidates).is_some(),
            );
            self.borrow()?
                .borrow_mut_composition()?
                .typo
                .translation_error = error;
            if let Some(text) = translation_surface(&candidates) {
                self.set_text(text, "")?;
                let service = self.borrow()?;
                let mut state = service.borrow_mut_composition()?;
                state.preview = text.to_owned();
                state.suffix.clear();
            }
        }
        let cursor_position = self.borrow()?.borrow_composition()?.cursor_position;
        self.sync_candidate_window_after_text_update(
            &mut ipc,
            &candidates,
            0,
            config,
            &CompositionState::Composing,
            cursor_position,
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
            .experimental_typo_correction
            || IMEState::keyboard_disabled().unwrap_or(true)
        {
            return Ok(());
        }
        let mut original = self.borrow()?.borrow_composition()?.clone();
        if !editable_composition(&original)
            || shared::assist_policy::adoption_action(
                shared::ai_candidate::TYPO_CORRECTION,
                explicit,
                true,
                original.typo.correction.is_applied(),
            ) != shared::assist_policy::AdoptionAction::ReplaceComposition
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
        let repaired_reading = original
            .candidates
            .prediction_readings
            .get(surface)
            .filter(|r| is_reading_only_correction(r))
            .cloned()
            .or_else(|| is_reading_only_correction(surface).then(|| surface.to_owned()));
        let converter_input = repaired_reading.as_deref().unwrap_or(surface).to_owned();
        let refinement_origin = original.typo.applied.as_deref().and_then(|prev| {
            let (origin_reading, origin_raw_input, applied_prefix) = build_auto_refinement_inputs(
                prev,
                &original.raw_input,
                &original.candidates.hiragana,
            )?;
            (!converter_input.starts_with(&applied_prefix))
                .then_some((origin_reading, origin_raw_input))
        });
        let pending_romaji = shared::conversion_policy::raw_input_has_pending_romaji_tail(
            &original.raw_input,
            &source,
        );
        let mut correction = original.typo.correction.clone();
        let (applied_source, applied_source_raw, is_refined) =
            if let Some((origin_reading, origin_raw_input)) = refinement_origin {
                if !correction.refine(&origin_reading, surface, explicit, pending_romaji) {
                    return Ok(());
                }
                (origin_reading, origin_raw_input, true)
            } else {
                if !correction.apply(&source, surface, explicit, pending_romaji) {
                    return Ok(());
                }
                (source, original.raw_input.clone(), false)
            };
        let mut candidates = match ipc.replace_with_direct_surface(&converter_input) {
            Ok(candidates) => candidates,
            Err(error) => {
                // The old ledger and displayed composition remain authoritative.
                // Shared IPC recovery reconstructs them before the next ordinary edit.
                tracing::debug!(?error, "Correction replacement unavailable; retain input");
                return Ok(());
            }
        };
        let (preview_text, suffix_text, selected_count) =
            if repaired_reading.is_some() && !candidates.texts.is_empty() {
                if let Some(selected) = Self::select_candidate(&candidates, 0) {
                    (
                        selected.text,
                        selected.sub_text,
                        selected.corresponding_count,
                    )
                } else {
                    select_surface(&mut candidates, surface);
                    (
                        surface.into(),
                        String::new(),
                        candidates.corresponding_count[0],
                    )
                }
            } else {
                select_surface(&mut candidates, surface);
                (
                    surface.into(),
                    String::new(),
                    candidates.corresponding_count[0],
                )
            };
        let applied_display = format!("{preview_text}{suffix_text}");
        let applied_hiragana = candidates.hiragana.clone();
        // Persist canonical converter input before any reentrant TSF edit.
        original.typo = TypoState {
            correction: original.typo.correction.clone(),
            ..Default::default()
        };
        let applied = Rc::new(AppliedCorrection {
            original,
            ledger,
            explicit,
            refined: is_refined,
            source: applied_source,
            source_raw_input: applied_source_raw,
            surface: applied_display,
            converter_input: converter_input.clone(),
            applied_hiragana,
            edited_after_apply: false,
        });
        let cursor_position = ipc.latest_composition_sync().cursor;
        {
            let service = self.borrow()?;
            let mut state = service.borrow_mut_composition()?;
            state.state = CompositionState::Composing;
            state.preview = preview_text.clone();
            state.suffix = suffix_text.clone();
            state.raw_input = converter_input;
            state.raw_hiragana = candidates.hiragana.clone();
            state.corresponding_count = selected_count;
            state.cursor_position = cursor_position;
            state.selection_index = 0;
            state.candidates = candidates.clone();
            state.live_conversion_history.clear();
            state.live_conversion_snapshots.clear();
            state.typo = TypoState {
                applied: Some(applied),
                correction,
                ..Default::default()
            };
        }
        IMEState::set_ipc_service(ipc.clone())?;
        if let Err(error) = self.set_text(&preview_text, &suffix_text) {
            // Timer completions can race a TSF write lock. Restore the converter
            // and client snapshot even when the document already kept its old text.
            let _ = self.undo_typo_correction();
            return Err(error);
        }
        let config = self.input_config_snapshot()?;
        self.sync_candidate_window_after_text_update(
            &mut ipc,
            &candidates,
            0,
            config.app_config(),
            &CompositionState::Composing,
            cursor_position,
        )
    }

    fn apply_reverted_prefix_to_composition(
        &self,
        ipc: &mut crate::engine::ipc_service::IPCService,
        applied: &AppliedCorrection,
        correction: shared::assist_policy::CorrectionState,
        trailing: &str,
    ) -> Result<()> {
        let candidates = match ipc.replace_with_reverted_prefix(&applied.source, trailing) {
            Ok(candidates) => candidates,
            Err(error) => {
                tracing::debug!(
                    ?error,
                    "Differential correction revert unavailable; retain snapshot"
                );
                return Ok(());
            }
        };
        let (preview_text, suffix_text, selected_count, selected_index) =
            if let Some(selected) = Self::select_candidate(&candidates, 0) {
                (
                    selected.text,
                    selected.sub_text,
                    selected.corresponding_count,
                    selected.index,
                )
            } else {
                (format!("{}{trailing}", applied.source), String::new(), 0, 0)
            };
        let sync = ipc.latest_composition_sync();
        let next_raw_input = sync
            .raw_input
            .unwrap_or_else(|| format!("{}{trailing}", applied.source));
        let cursor_position = sync.cursor;
        {
            let service = self.borrow()?;
            let mut state = service.borrow_mut_composition()?;
            state.state = CompositionState::Composing;
            state.preview = preview_text.clone();
            state.suffix = suffix_text.clone();
            state.raw_input = next_raw_input;
            state.raw_hiragana = candidates.hiragana.clone();
            state.corresponding_count = selected_count;
            state.cursor_position = cursor_position;
            state.selection_index = selected_index;
            state.candidates = candidates.clone();
            state.live_conversion_snapshots.clear();
            state.live_conversion_history.clear();
            state.typo = TypoState {
                applied: None,
                correction,
                ..Default::default()
            };
        }
        IMEState::set_ipc_service(ipc.clone())?;
        self.set_text(&preview_text, &suffix_text)?;
        let config = self.input_config_snapshot()?;
        self.sync_candidate_window_after_text_update(
            ipc,
            &candidates,
            selected_index,
            config.app_config(),
            &CompositionState::Composing,
            cursor_position,
        )
    }

    pub(super) fn auto_revert_typo_correction(&self) -> Result<()> {
        self.cancel_typo_assist();
        let (Some(applied), mut correction, current_raw_input, current_raw_hiragana) = ({
            let service = self.borrow()?;
            let state = service.borrow_composition()?;
            (
                state.typo.applied.clone(),
                state.typo.correction.clone(),
                state.raw_input.clone(),
                state.raw_hiragana.clone(),
            )
        }) else {
            return Ok(());
        };
        if applied.explicit || !applied.edited_after_apply {
            return Ok(());
        }
        let Some(trailing_raw) = current_raw_input
            .strip_prefix(&applied.converter_input)
            .filter(|t| !t.is_empty())
            .map(str::to_owned)
        else {
            return Ok(());
        };
        let Some(trailing_hira) = current_raw_hiragana
            .strip_prefix(&applied.applied_hiragana)
            .filter(|t| !t.is_empty())
        else {
            return Ok(());
        };
        let origin_reading = format!("{}{}", applied.source, trailing_hira);
        if !correction.auto_revert(&origin_reading, &current_raw_hiragana) {
            return Ok(());
        }
        let mut ipc = IMEState::ipc_service()?.context("No IPC service")?;
        self.apply_reverted_prefix_to_composition(&mut ipc, &applied, correction, &trailing_raw)
    }

    pub(super) fn undo_typo_correction(&self) -> Result<()> {
        self.cancel_typo_assist();
        let (Some(applied), mut correction, current_raw_input, current_raw_hiragana) = ({
            let service = self.borrow()?;
            let state = service.borrow_composition()?;
            (
                state.typo.applied.clone(),
                state.typo.correction.clone(),
                state.raw_input.clone(),
                state.raw_hiragana.clone(),
            )
        }) else {
            return Ok(());
        };
        let trailing_raw = if applied.edited_after_apply {
            let tail = current_raw_input
                .strip_prefix(&applied.converter_input)
                .or_else(|| current_raw_hiragana.strip_prefix(&applied.applied_hiragana));
            match tail {
                Some(t) => Some(t.to_string()),
                None => {
                    let service = self.borrow()?;
                    let mut state = service.borrow_mut_composition()?;
                    state.typo.applied = None;
                    state.typo.correction.discard_undo();
                    return Ok(());
                }
            }
        } else {
            None
        };
        if !correction.revert() {
            return Ok(());
        }
        let mut ipc = IMEState::ipc_service()?.context("No IPC service")?;
        if applied.refined || trailing_raw.as_deref().is_some_and(|t| !t.is_empty()) {
            let trailing = trailing_raw.as_deref().unwrap_or("");
            return self
                .apply_reverted_prefix_to_composition(&mut ipc, &applied, correction, trailing);
        }
        let mut candidates = match ipc.replace_from_snapshot(&applied.ledger) {
            Ok(candidates) => candidates,
            Err(error) => {
                tracing::debug!(?error, "Correction undo unavailable; retain undo snapshot");
                return Ok(());
            }
        };
        let baseline = format!("{}{}", applied.original.preview, applied.original.suffix);
        select_surface(&mut candidates, &baseline);
        let cursor_position = ipc
            .latest_composition_sync()
            .cursor
            .or(applied.original.cursor_position);
        let mut restored = applied.original.clone();
        restored.typo.correction = correction;
        restored.state = CompositionState::Composing;
        restored.preview = baseline.clone();
        restored.suffix.clear();
        restored.candidates = candidates.clone();
        restored.selection_index = 0;
        restored.corresponding_count = candidates.corresponding_count[0];
        restored.cursor_position = cursor_position;
        *self.borrow()?.borrow_mut_composition()? = restored;
        IMEState::set_ipc_service(ipc.clone())?;
        self.set_text(&baseline, "")?;
        let config = self.input_config_snapshot()?;
        self.sync_candidate_window_after_text_update(
            &mut ipc,
            &candidates,
            0,
            config.app_config(),
            &CompositionState::Composing,
            cursor_position,
        )
    }

    pub(super) fn typo_acceptances_for_commit(&self, displayed: &str) -> Vec<(String, String)> {
        self.borrow()
            .ok()
            .and_then(|service| {
                service.borrow_composition().ok().map(|state| {
                    let mut accepted = Vec::new();
                    for entry in state
                        .typo
                        .correction
                        .acceptances_for_commit(displayed)
                        .into_iter()
                        .chain(
                            state
                                .typo
                                .correction
                                .acceptances_for_commit(&state.raw_hiragana),
                        )
                    {
                        let pair = (entry.reading, entry.surface);
                        if !accepted.contains(&pair) {
                            accepted.push(pair);
                        }
                    }
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
        let only_candidate_nav = actions.iter().all(|action| {
            matches!(
                action,
                ClientAction::ShowCandidateWindow | ClientAction::SetSelection(_)
            )
        });
        if only_candidate_nav {
            return Ok(());
        }
        let clears_undo = actions.iter().any(|action| {
            matches!(
                action,
                ClientAction::EndComposition
                    | ClientAction::CommitWithText(_)
                    | ClientAction::CommitTextDirect(_)
                    | ClientAction::RestoreReconversionOriginal
                    | ClientAction::MoveCursor(_)
                    | ClientAction::DeleteForwardText
                    | ClientAction::ShrinkText(_)
                    | ClientAction::ShrinkTextRaw(_)
                    | ClientAction::ShrinkTextDirect(_)
                    | ClientAction::EnsureClauseNavigationReady { .. }
                    | ClientAction::MoveClause(_)
                    | ClientAction::AdjustBoundary(_)
                    | ClientAction::StartJevConversion
                    | ClientAction::SetTextWithType(_)
                    | ClientAction::SetTemporaryLatin(_)
                    | ClientAction::SetIMEMode(_)
            )
        });
        let service = self.borrow()?;
        let mut state = service.borrow_mut_composition()?;
        state.typo.correction.edited();
        if clears_undo {
            state.typo.correction.discard_undo();
            state.typo.applied = None;
        } else if let Some(applied) = state.typo.applied.as_mut() {
            Rc::make_mut(applied).edited_after_apply = true;
        }
        state.typo.primary_assist = None;
        state.typo.prediction_continuation.clear();
        // Retain the hold through the first following edit; the ordinary stability
        // window must build up again before automatic prefix commit is possible.
        if state.typo.correction.hold_auto_commit {
            state.live_conversion_snapshots.clear();
            state.live_conversion_history.clear();
        }
        Ok(())
    }

    pub(super) fn reconcile_typo_applied(
        &self,
        transition: &CompositionState,
        raw_input: &str,
        raw_hiragana: &str,
        suffix: &str,
        fixed_prefix: &str,
        clause_snapshots: &[ClauseSnapshot],
    ) -> Result<()> {
        let service = self.borrow()?;
        let mut state = service.borrow_mut_composition()?;
        let keep = state.typo.applied.as_deref().is_some_and(|applied| {
            should_keep_applied_correction(
                applied,
                transition,
                raw_input,
                raw_hiragana,
                suffix,
                fixed_prefix,
                clause_snapshots,
            )
        });
        if !keep && state.typo.applied.is_some() {
            state.typo.applied = None;
            state.typo.correction.discard_undo();
        }
        Ok(())
    }
}

pub(super) fn should_keep_applied_correction(
    applied: &AppliedCorrection,
    transition: &CompositionState,
    raw_input: &str,
    raw_hiragana: &str,
    suffix: &str,
    fixed_prefix: &str,
    clause_snapshots: &[ClauseSnapshot],
) -> bool {
    matches!(
        transition,
        CompositionState::Composing | CompositionState::Previewing
    ) && suffix.is_empty()
        && fixed_prefix.is_empty()
        && clause_snapshots.is_empty()
        && !applied.applied_hiragana.is_empty()
        && raw_hiragana.starts_with(&applied.applied_hiragana)
        && (applied.converter_input.is_empty() || raw_input.starts_with(&applied.converter_input))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn translation_tab_commits_english_and_keeps_japanese_source_until_acceptance() {
        let mut config = AppConfig::default();
        config.general.experimental_translation = true;
        config.general.punctuation_commit = true;
        let mut state = Composition {
            state: CompositionState::Composing,
            raw_input: "kaigihaashitadesu".into(),
            preview: "会議は明日です".into(),
            candidates: Candidates {
                prediction_readings: Default::default(),
                texts: vec!["会議は明日です".into()],
                sub_texts: vec![String::new()],
                hiragana: "かいぎはあしたです".into(),
                corresponding_count: vec![16],
                candidate_ids: vec![1],
                top_token_margins: Default::default(),
            },
            ..Default::default()
        };
        let snapshot = state.candidates.clone();
        let mut result = response(
            shared::ai_candidate::TRANSLATION,
            "The meeting is tomorrow.",
            100,
        );
        result.auto_apply = true; // Never treat a translation as a typo replacement.
        result.suggestions[0].corresponding_count = 16;
        assert_eq!(
            merge_results(&mut state, vec![(3, result)]),
            MergeOutcome::None
        );
        assert_eq!(state.candidates.texts[0], "会議は明日です");
        assert_eq!(state.raw_input, "kaigihaashitadesu");
        assert_eq!(
            state.typo.primary_assist,
            Some(shared::ai_candidate::TRANSLATION)
        );
        assert_eq!(state.candidates.corresponding_count[1], 16);
        let plan = CompositionReducer::plan_actions_for_user_action(
            &state,
            &UserAction::Tab,
            &InputMode::Kana,
            false,
            &config,
            false,
        )
        .unwrap();
        assert_eq!(
            plan,
            (
                CompositionState::None,
                vec![
                    ClientAction::CommitWithText("The meeting is tomorrow.".into()),
                    ClientAction::EndComposition,
                ]
            )
        );
        assert_eq!(
            translation_surface(&state.candidates),
            Some("The meeting is tomorrow.")
        );
        let enter = CompositionReducer::plan_actions_for_user_action(
            &state,
            &UserAction::Enter,
            &InputMode::Kana,
            false,
            &config,
            false,
        )
        .unwrap();
        assert_eq!(enter, plan);
        let mut waiting = state.clone();
        waiting.candidates = snapshot.clone();
        waiting.typo.primary_assist = None;
        for action in [UserAction::Enter, UserAction::Tab] {
            assert_eq!(
                CompositionReducer::plan_actions_for_user_action(
                    &waiting,
                    &action,
                    &InputMode::Kana,
                    false,
                    &config,
                    false
                ),
                Some((CompositionState::Composing, Vec::new()))
            );
        }
        // A translation requested before a delete/retype must not attach afterward.
        state.candidates = snapshot.clone();
        state.raw_input.pop();
        assert!(!matches_snapshot(
            &state,
            "kaigihaashitadesu",
            &snapshot,
            1,
            1
        ));
        state.raw_input.push('u');
        assert!(!matches_snapshot(
            &state,
            "kaigihaashitadesu",
            &snapshot,
            1,
            2
        ));
        for phase in [
            CompositionState::None,
            CompositionState::Previewing,
            CompositionState::Selecting,
        ] {
            state.state = phase;
            assert!(!matches_snapshot(
                &state,
                "kaigihaashitadesu",
                &snapshot,
                1,
                1
            ));
        }
    }
    fn response(id: u64, text: &str, relevance: u32) -> shared::proto::GetAssistCandidatesResponse {
        shared::proto::GetAssistCandidatesResponse {
            suggestions: vec![shared::proto::Suggestion {
                prediction_reading: Default::default(),
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
                prediction_readings: Default::default(),
                texts: vec!["資料を送りmっす".into()],
                sub_texts: vec![String::new()],
                hiragana: "しりょうをおくりmっす".into(),
                candidate_ids: vec![0],
                corresponding_count: vec![16],
                top_token_margins: Default::default(),
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
        assert_eq!(automatic, MergeOutcome::None);
        assert_eq!(
            state.typo.primary_assist,
            Some(shared::ai_candidate::TYPO_CORRECTION)
        );
        assert_eq!(state.candidates.texts[0], "資料を送りmっす");
    }

    #[test]
    fn correction_precedes_prediction_even_with_low_relevance_or_late_arrival() {
        let base_candidates = || Candidates {
            prediction_readings: Default::default(),
            texts: vec!["よろしくお願い".into()],
            sub_texts: vec![String::new()],
            hiragana: "よろしくおねがい".into(),
            candidate_ids: vec![0],
            corresponding_count: vec![4],
            top_token_margins: Default::default(),
        };
        let mut state = Composition {
            candidates: base_candidates(),
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
            Some(shared::ai_candidate::TYPO_CORRECTION)
        );
        assert_eq!(state.candidates.texts[0], "よろしくお願い");
        assert!(state
            .candidates
            .candidate_ids
            .contains(&shared::ai_candidate::TYPO_CORRECTION));
        assert!(state
            .candidates
            .candidate_ids
            .contains(&shared::ai_candidate::PREDICTIVE));

        // When prediction displays first, a late correction still promotes to the primary target.
        let mut late_state = Composition {
            candidates: base_candidates(),
            ..Default::default()
        };
        merge_results(
            &mut late_state,
            vec![(
                2,
                response(
                    shared::ai_candidate::PREDICTIVE,
                    "よろしくお願いいたします。",
                    75,
                ),
            )],
        );
        assert_eq!(
            late_state.typo.primary_assist,
            Some(shared::ai_candidate::PREDICTIVE)
        );
        merge_results(
            &mut late_state,
            vec![(
                1,
                response(
                    shared::ai_candidate::TYPO_CORRECTION,
                    "宜しくお願いします",
                    30,
                ),
            )],
        );
        assert_eq!(
            late_state.typo.primary_assist,
            Some(shared::ai_candidate::TYPO_CORRECTION)
        );
        // Conversely, a late prediction never displaces an already shown correction.
        merge_results(
            &mut late_state,
            vec![(
                2,
                response(
                    shared::ai_candidate::PREDICTIVE,
                    "よろしくお願いいたします。",
                    95,
                ),
            )],
        );
        assert_eq!(
            late_state.typo.primary_assist,
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
                prediction_readings: Default::default(),
                texts: vec!["おkせり".into(), "おかえり".into()],
                sub_texts: vec![String::new(); 2],
                hiragana: "おkせり".into(),
                corresponding_count: vec![6; 2],
                candidate_ids: vec![1, shared::ai_candidate::TYPO_CORRECTION],
                top_token_margins: Default::default(),
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
            explicit: true,
            refined: false,
            source: "おkせり".into(),
            source_raw_input: "okseri".into(),
            surface: "おかえり".into(),
            converter_input: "おかえり".into(),
            applied_hiragana: "おかえり".into(),
            edited_after_apply: false,
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
        let mut candidates = Candidates {
            hiragana: "おkせり".into(),
            ..Default::default()
        };
        let suggestion = shared::proto::Suggestion {
            prediction_reading: Default::default(),
            text: "おかえり".into(),
            subtext: "補正".into(),
            corresponding_count: 6,
            candidate_id: shared::ai_candidate::TYPO_CORRECTION,
        };
        let mut rejected = shared::assist_policy::CorrectionState::default();
        rejected.rejected_surface = Some("おかえり".into());
        append_suggestions(&mut candidates, vec![suggestion.clone()], &rejected);
        assert!(candidates.texts.is_empty());
        append_suggestions(
            &mut candidates,
            vec![suggestion],
            &shared::assist_policy::CorrectionState::default(),
        );
        assert_eq!(candidates.sub_texts, vec![""]);
    }

    #[test]
    fn correction_is_second_and_keeps_normal_candidate_order_and_identity() {
        let mut candidates = Candidates {
            prediction_readings: Default::default(),
            texts: vec!["原候補".into(), "通常の別候補".into()],
            sub_texts: vec![String::new(); 2],
            candidate_ids: vec![10, 20],
            corresponding_count: vec![6; 2],
            hiragana: "おkせり".into(),
            top_token_margins: Default::default(),
        };
        append_suggestions(
            &mut candidates,
            vec![shared::proto::Suggestion {
                prediction_reading: Default::default(),
                text: "おかえり".into(),
                candidate_id: shared::ai_candidate::TYPO_CORRECTION,
                corresponding_count: 6,
                subtext: "補正".into(),
            }],
            &shared::assist_policy::CorrectionState::default(),
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
    #[test]
    fn translation_absence_maps_to_timeout_unless_the_server_never_became_ready() {
        assert_eq!(
            translation_error_from_outcome(false, false),
            Some(TranslationError::Timeout)
        );
        assert_eq!(
            translation_error_from_outcome(true, false),
            Some(TranslationError::Unavailable)
        );
        for unavailable in [false, true] {
            assert_eq!(translation_error_from_outcome(unavailable, true), None);
        }
    }
    #[test]
    fn translation_errors_report_distinct_ui_keys() {
        assert_eq!(TranslationError::Timeout.ui_key(), "timeout");
        assert_eq!(TranslationError::Unavailable.ui_key(), "unavailable");
    }

    #[test]
    fn assist_reading_json_uses_baseline_surface_as_correction_source() {
        let candidates = Candidates {
            prediction_readings: Default::default(),
            texts: vec!["明日の会議のおkせり".into(), "明日の会議のおかえり".into()],
            sub_texts: vec![String::new(), String::new()],
            hiragana: "あしたのかいぎのおkせり".into(),
            corresponding_count: vec![23; 2],
            candidate_ids: vec![1, shared::ai_candidate::TYPO_CORRECTION],
            top_token_margins: Default::default(),
        };
        let proposal_json = TextServiceFactory::build_assist_reading_json(
            &candidates,
            Some("あしたのかいぎのおkせり"),
            None,
            Some(shared::ai_candidate::TYPO_CORRECTION),
            "",
            None,
        )
        .expect("proposal assist json");
        let proposal: serde_json::Value = serde_json::from_str(&proposal_json).unwrap();
        assert_eq!(proposal["reading"], "あしたのかいぎのおkせり");
        assert_eq!(proposal["assist"]["status"], "proposal");
        assert_eq!(proposal["assist"]["source"], "明日の会議のおkせり");
        assert_eq!(proposal["assist"]["replacement"], "明日の会議のおかえり");

        let ipc = IPCService::recovery_for_test(false);
        let applied = AppliedCorrection {
            original: Composition {
                preview: "明日の会議のおkせり".into(),
                suffix: "です".into(),
                candidates: candidates.clone(),
                ..Default::default()
            },
            ledger: ipc.input_ledger_snapshot(),
            explicit: true,
            refined: false,
            source: "あしたのかいぎのおkせりです".into(),
            source_raw_input: String::new(),
            surface: "明日の会議のおかえりです".into(),
            converter_input: "あしたのかいぎのおkせりです".into(),
            applied_hiragana: "あしたのかいぎのおkせりです".into(),
            edited_after_apply: false,
        };
        let applied_candidates = Candidates {
            hiragana: "あしたのかいぎのおkせりです".into(),
            ..Default::default()
        };
        let applied_json = TextServiceFactory::build_assist_reading_json(
            &applied_candidates,
            None,
            Some(&applied),
            None,
            "",
            None,
        )
        .expect("applied assist json");
        let parsed: serde_json::Value = serde_json::from_str(&applied_json).unwrap();
        assert_eq!(parsed["reading"], "あしたのかいぎのおkせりです");
        assert_eq!(parsed["assist"]["status"], "applied");
        assert_eq!(parsed["assist"]["source"], "明日の会議のおkせりです");
        assert_eq!(parsed["assist"]["replacement"], "明日の会議のおかえりです");
    }

    #[test]
    fn baseline_surface_skips_ai_candidates_and_falls_back_cleanly() {
        let ai_first = Candidates {
            prediction_readings: Default::default(),
            texts: vec!["誤り補正".into(), "通常変換".into()],
            sub_texts: vec![String::new(), "です".into()],
            hiragana: "あやまり".into(),
            corresponding_count: vec![4; 2],
            candidate_ids: vec![shared::ai_candidate::TYPO_CORRECTION, 42],
            top_token_margins: Default::default(),
        };
        assert_eq!(
            baseline_surface_from_candidates(&ai_first),
            Some("通常変換です".to_string())
        );

        let empty_preview_applied = AppliedCorrection {
            original: Composition {
                candidates: ai_first,
                ..Default::default()
            },
            ledger: IPCService::recovery_for_test(false).input_ledger_snapshot(),
            explicit: true,
            refined: false,
            source: "あやまり".into(),
            source_raw_input: String::new(),
            surface: "正しい変換".into(),
            converter_input: "あやまり".into(),
            applied_hiragana: "あやまり".into(),
            edited_after_apply: false,
        };
        assert_eq!(empty_preview_applied.baseline_surface(), "通常変換です");
    }

    #[test]
    fn reading_only_typo_correction_diffs_against_hiragana_and_enter_applies_in_preview() {
        let candidates = Candidates {
            prediction_readings: Default::default(),
            texts: vec![
                "お世話になております".into(),
                "おせわになっております".into(),
            ],
            sub_texts: vec![String::new(), String::new()],
            hiragana: "おせわになております".into(),
            corresponding_count: vec![20; 2],
            candidate_ids: vec![1, shared::ai_candidate::TYPO_CORRECTION],
            top_token_margins: Default::default(),
        };
        let proposal_json = TextServiceFactory::build_assist_reading_json(
            &candidates,
            Some("おせわになております"),
            None,
            Some(shared::ai_candidate::TYPO_CORRECTION),
            "",
            None,
        )
        .expect("reading-only proposal json");
        let proposal: serde_json::Value = serde_json::from_str(&proposal_json).unwrap();
        assert_eq!(proposal["assist"]["source"], "おせわになております");
        assert_eq!(proposal["assist"]["replacement"], "おせわになっております");

        let state = Composition {
            raw_input: "osewaninateorimasu".into(),
            state: CompositionState::Previewing,
            selection_index: 1,
            preview: "おせわになっております".into(),
            candidates,
            ..Default::default()
        };
        let plan = CompositionReducer::plan_actions_for_user_action(
            &state,
            &UserAction::Enter,
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
                vec![ClientAction::ApplyTypoCorrection(
                    "おせわになっております".into()
                )]
            )
        );
    }

    #[test]
    fn post_edit_differential_undo_keeps_escape_reconciles_prefix_allows_later_tab_and_blocks_boomerang(
    ) {
        let ipc = IPCService::recovery_for_test(false);
        let mut applied = AppliedCorrection {
            original: Composition {
                preview: "きょおは".into(),
                candidates: Candidates {
                    texts: vec!["きょおは".into()],
                    hiragana: "きょおは".into(),
                    ..Default::default()
                },
                ..Default::default()
            },
            ledger: ipc.input_ledger_snapshot(),
            explicit: false,
            refined: false,
            source: "きょおは".into(),
            source_raw_input: "kyooha".into(),
            surface: "きょうは".into(),
            converter_input: "きょうは".into(),
            applied_hiragana: "きょうは".into(),
            edited_after_apply: true,
        };

        // Reconcile keeps applied while trailing text extends the prefix, and clears on prefix/mode changes.
        assert!(should_keep_applied_correction(
            &applied,
            &CompositionState::Composing,
            "きょうはiitenki",
            "きょうはいいてんき",
            "",
            "",
            &[],
        ));
        assert!(!should_keep_applied_correction(
            &applied,
            &CompositionState::Composing,
            "きょう",
            "きょう",
            "",
            "",
            &[],
        ));
        assert!(!should_keep_applied_correction(
            &applied,
            &CompositionState::Composing,
            "きょうはiitenki",
            "きょうはいいてんき",
            "",
            "今日は",
            &[],
        ));
        assert!(!should_keep_applied_correction(
            &applied,
            &CompositionState::Composing,
            "きょうはiitenki",
            "きょうはいいてんき",
            "です",
            "",
            &[],
        ));

        // build_assist_reading_json uses live reading and exposes undoAvailable alongside later proposal.
        let candidates_with_proposal = Candidates {
            prediction_readings: Default::default(),
            texts: vec!["今日は良い天気".into(), "きょうはいいてんき！".into()],
            sub_texts: vec![String::new(); 2],
            hiragana: "きょうはいいてんき".into(),
            corresponding_count: vec![9; 2],
            candidate_ids: vec![1, shared::ai_candidate::TYPO_CORRECTION],
            top_token_margins: Default::default(),
        };
        let post_edit_json = TextServiceFactory::build_assist_reading_json(
            &candidates_with_proposal,
            Some("きょうはいいてんき"),
            Some(&applied),
            Some(shared::ai_candidate::TYPO_CORRECTION),
            "",
            None,
        )
        .expect("post-edit proposal json");
        let parsed: serde_json::Value = serde_json::from_str(&post_edit_json).unwrap();
        assert_eq!(parsed["reading"], "きょうはいいてんき");
        assert_eq!(parsed["assist"]["status"], "proposal");
        assert_eq!(parsed["assist"]["undoAvailable"], true);

        // When no newer proposal exists after editing, the applied pill keeps live reading.
        let candidates_plain = Candidates {
            texts: vec!["今日は良い天気".into()],
            hiragana: "きょうはいいてんき".into(),
            candidate_ids: vec![1],
            corresponding_count: vec![9],
            ..Default::default()
        };
        let applied_live_json = TextServiceFactory::build_assist_reading_json(
            &candidates_plain,
            Some("きょうはいいてんき"),
            Some(&applied),
            None,
            "",
            None,
        )
        .expect("post-edit applied json");
        let parsed_live: serde_json::Value = serde_json::from_str(&applied_live_json).unwrap();
        assert_eq!(parsed_live["reading"], "きょうはいいてんき");
        assert_eq!(parsed_live["assist"]["status"], "applied");

        // Tab adopts a later typo proposal when is_applied() is false after edited(), while Escape undoes.
        let mut correction = shared::assist_policy::CorrectionState::default();
        assert!(correction.apply("きょおは", "きょうは", false, false));
        correction.edited();
        assert!(!correction.is_applied());
        assert!(correction.can_revert());

        applied.edited_after_apply = true;
        let state_for_plan = Composition {
            raw_input: "きょうはiitenki".into(),
            state: CompositionState::Composing,
            candidates: candidates_with_proposal,
            typo: TypoState {
                applied: Some(Rc::new(applied)),
                correction: correction.clone(),
                primary_assist: Some(shared::ai_candidate::TYPO_CORRECTION),
                ..Default::default()
            },
            ..Default::default()
        };
        let tab_plan = CompositionReducer::plan_actions_for_user_action(
            &state_for_plan,
            &UserAction::Tab,
            &InputMode::Kana,
            false,
            &AppConfig::default(),
            false,
        )
        .unwrap();
        assert_eq!(
            tab_plan,
            (
                CompositionState::Composing,
                vec![ClientAction::ApplyTypoCorrection(
                    "きょうはいいてんき！".into()
                )]
            )
        );
        let esc_plan = CompositionReducer::plan_actions_for_user_action(
            &state_for_plan,
            &UserAction::Escape,
            &InputMode::Kana,
            false,
            &AppConfig::default(),
            false,
        )
        .unwrap();
        assert_eq!(
            esc_plan,
            (
                CompositionState::Composing,
                vec![ClientAction::UndoTypoCorrection]
            )
        );

        // After reverting, merge_results suppresses the boomerang correction while allowing predictions.
        assert!(correction.revert());
        let mut after_revert = Composition {
            candidates: Candidates {
                texts: vec!["きょおはいいてんき".into()],
                sub_texts: vec![String::new()],
                hiragana: "きょおはいいてんき".into(),
                candidate_ids: vec![1],
                corresponding_count: vec![9],
                ..Default::default()
            },
            typo: TypoState {
                correction,
                ..Default::default()
            },
            ..Default::default()
        };
        merge_results(
            &mut after_revert,
            vec![
                (
                    1,
                    response(
                        shared::ai_candidate::TYPO_CORRECTION,
                        "きょうはいいてんき",
                        100,
                    ),
                ),
                (
                    2,
                    response(
                        shared::ai_candidate::PREDICTIVE,
                        "きょおはいいてんきです",
                        75,
                    ),
                ),
            ],
        );
        assert!(!after_revert
            .candidates
            .candidate_ids
            .contains(&shared::ai_candidate::TYPO_CORRECTION));
        assert_eq!(
            after_revert.typo.primary_assist,
            Some(shared::ai_candidate::PREDICTIVE)
        );
    }

    #[test]
    fn auto_refinement_inputs_and_merge_outcome_preserve_origin_and_suppress_explicit() {
        let ipc = IPCService::recovery_for_test(false);
        let base = AppliedCorrection {
            original: Composition::default(),
            ledger: ipc.input_ledger_snapshot(),
            explicit: false,
            refined: false,
            source: "しゅてん".into(),
            source_raw_input: "shuten".into(),
            surface: "終点".into(),
            converter_input: "しゅうてん".into(),
            applied_hiragana: "しゅうてん".into(),
            edited_after_apply: true,
        };
        assert_eq!(
            build_auto_refinement_inputs(&base, "しゅうてんshimasu", "しゅうてんします"),
            Some((
                "しゅてんします".into(),
                "shutenshimasu".into(),
                "しゅうてん".into()
            ))
        );
        // Single trailing 'n' inserts an extra 'n' before vowel/y/n tails so romaji stays distinct.
        assert_eq!(
            build_auto_refinement_inputs(&base, "しゅうてんarima", "しゅうてんありま"),
            Some((
                "しゅてんありま".into(),
                "shutennarima".into(),
                "しゅうてん".into()
            ))
        );
        // Explicit corrections never auto-refine.
        let explicit = AppliedCorrection {
            explicit: true,
            ..base.clone()
        };
        assert_eq!(
            build_auto_refinement_inputs(&explicit, "しゅうてんshimasu", "しゅうてんします"),
            None
        );

        let mut revert_state = Composition {
            candidates: Candidates {
                texts: vec!["終点童子".into()],
                hiragana: "しゅうてんどうじ".into(),
                candidate_ids: vec![1],
                corresponding_count: vec![8],
                ..Default::default()
            },
            ..Default::default()
        };
        let revert_outcome = merge_results(
            &mut revert_state,
            vec![(
                1,
                shared::proto::GetAssistCandidatesResponse {
                    revert_to_origin: true,
                    relevance: 100,
                    ..Default::default()
                },
            )],
        );
        assert_eq!(revert_outcome, MergeOutcome::AutoRevert);
        assert!(first_assist_ready(
            false,
            true,
            true,
            Duration::from_millis(1)
        ));
    }
}
