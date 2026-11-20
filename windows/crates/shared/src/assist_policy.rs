//! Assistance decisions shared by TSF and InputConnection hosts.
//! Hosts retain editor snapshots and execute a plan before installing its new state.
use serde::{Deserialize, Serialize};

pub const TYPO_CORRECTION: u64 = 999901;
pub const PREDICTIVE: u64 = 999902;
pub const TRANSLATION: u64 = 999904;
pub const FIRST_PRESENTATION_WAIT_MS: u64 = 200;

/// Strength preset controlling automatic typo-correction thresholds (Plan C / B / A).
#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AutoTypoCorrectionStrength {
    Off,
    Conservative,
    #[default]
    Standard,
    Aggressive,
}

impl AutoTypoCorrectionStrength {
    pub fn is_enabled(self) -> bool {
        self != Self::Off
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Conservative => "conservative",
            Self::Standard => "standard",
            Self::Aggressive => "aggressive",
        }
    }

    pub fn from_str_lossy(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().replace('-', "_").as_str() {
            "off" | "disabled" | "none" | "false" => Self::Off,
            "conservative" | "low" | "cautious" | "plan_c" => Self::Conservative,
            "aggressive" | "high" | "strong" | "plan_a" => Self::Aggressive,
            _ => Self::Standard,
        }
    }

    pub const fn to_u8(self) -> u8 {
        match self {
            Self::Off => 0,
            Self::Conservative => 1,
            Self::Standard => 2,
            Self::Aggressive => 3,
        }
    }

    pub const fn from_u8(value: u8) -> Self {
        match value {
            0 => Self::Off,
            1 => Self::Conservative,
            3 => Self::Aggressive,
            _ => Self::Standard,
        }
    }
}

#[derive(Clone, Copy, Debug, Serialize, PartialEq, Eq)]
pub struct AssistRequestPlan {
    pub correction: bool,
    pub prediction: bool,
}

/// Normal conversion is the first correction stage. Run the model fallback
/// only for an uncorrected snapshot, independently of sentence prediction.
pub fn request_plan(
    correction_enabled: bool,
    prediction_enabled: bool,
    applied: bool,
    observed: &str,
    corrected: &str,
) -> AssistRequestPlan {
    AssistRequestPlan {
        correction: correction_enabled
            && !applied
            && assist_reading(observed, corrected, true).is_some(),
        prediction: prediction_enabled,
    }
}

/// A corrected normal candidate owns the reading used for prediction. Another
/// correction pass must not replace it using the old observed reading.
pub fn assist_reading<'a>(
    observed: &'a str,
    corrected: &'a str,
    correction: bool,
) -> Option<&'a str> {
    if corrected.is_empty() || corrected == observed {
        Some(observed)
    } else if correction {
        None
    } else {
        Some(corrected)
    }
}

pub fn first_result_ready(
    prediction_done: bool,
    correction_done: bool,
    automatic: bool,
    elapsed_ms: u64,
) -> bool {
    automatic || (prediction_done && (correction_done || elapsed_ms >= FIRST_PRESENTATION_WAIT_MS))
}

fn assist_rank(id: u64) -> u8 {
    match id {
        TRANSLATION => 3,
        TYPO_CORRECTION => 2,
        PREDICTIVE => 1,
        _ => 0,
    }
}

/// Force correction (and translation) priority over predictive continuations
/// even when prediction scored higher or arrived first, while keeping
/// equal/lower-rank late arrivals from displacing the current target.
pub fn choose_primary(choices: &[(u32, u64)], displayed: Option<u64>) -> Option<u64> {
    let best = choices
        .iter()
        .max_by_key(|(score, id)| (assist_rank(*id), *score))
        .map(|(_, id)| *id);
    match (displayed, best) {
        (Some(current), Some(candidate)) if assist_rank(candidate) > assist_rank(current) => {
            Some(candidate)
        }
        _ => displayed.or(best),
    }
}

pub fn priority_index(ids: &[u64], displayed: Option<u64>) -> Option<(usize, u64)> {
    displayed
        .into_iter()
        .chain([TRANSLATION, TYPO_CORRECTION, PREDICTIVE])
        .find_map(|id| {
            ids.iter()
                .position(|candidate| *candidate == id)
                .map(|i| (i, id))
        })
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum AdoptionAction {
    Ignore,
    ReplaceComposition,
    CommitText,
}

/// Platform safety (editor identity, request generation, privacy) is checked
/// before calling this policy. Only corrections can be applied automatically.
pub fn adoption_action(id: u64, explicit: bool, automatic: bool, applied: bool) -> AdoptionAction {
    match id {
        TYPO_CORRECTION if !applied && (explicit || automatic) => {
            AdoptionAction::ReplaceComposition
        }
        PREDICTIVE | TRANSLATION if explicit => AdoptionAction::CommitText,
        _ => AdoptionAction::Ignore,
    }
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct Acceptance {
    pub reading: String,
    pub surface: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct CorrectionState {
    accepted: Vec<Acceptance>,
    protected: Vec<String>,
    pub rejected_reading: Option<String>,
    pub rejected_surface: Option<String>,
    pub hold_auto_commit: bool,
    undo: Option<CorrectionUndo>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct CorrectionUndo {
    #[serde(default)]
    reading: String,
    surface: String,
    accepted: Vec<Acceptance>,
    protected: Vec<String>,
    hold_auto_commit: bool,
    #[serde(default)]
    edited_after_apply: bool,
}

impl CorrectionState {
    pub fn is_applied(&self) -> bool {
        self.undo
            .as_ref()
            .is_some_and(|undo| !undo.edited_after_apply)
    }

    pub fn can_revert(&self) -> bool {
        self.undo.is_some()
    }

    fn can_install(
        &self,
        reading: &str,
        surface: &str,
        explicit: bool,
        pending_romaji: bool,
    ) -> bool {
        !reading.is_empty()
            && reading.chars().count() <= 512
            && !surface.trim().is_empty()
            && surface.chars().count() <= 512
            && !self.is_applied()
            && (explicit || !pending_romaji)
    }

    fn install_correction(
        &mut self,
        reading: &str,
        surface: &str,
        explicit: bool,
        undo_accepted: Vec<Acceptance>,
        undo_protected: Vec<String>,
        undo_hold_auto_commit: bool,
    ) {
        self.undo = Some(CorrectionUndo {
            reading: reading.into(),
            surface: surface.into(),
            accepted: undo_accepted,
            protected: undo_protected,
            hold_auto_commit: undo_hold_auto_commit,
            edited_after_apply: false,
        });
        if explicit {
            self.accepted.push(Acceptance {
                reading: reading.into(),
                surface: surface.into(),
            });
        }
        self.protected.push(surface.into());
        if self.accepted.len() > 8 {
            self.accepted.remove(0);
        }
        if self.protected.len() > 8 {
            self.protected.remove(0);
        }
        self.hold_auto_commit = true;
        self.rejected_reading = None;
        self.rejected_surface = None;
    }

    /// The caller installs this mutated copy only after the editor accepted
    /// the replacement. Failed editor transactions retain the previous state.
    pub fn apply(
        &mut self,
        reading: &str,
        surface: &str,
        explicit: bool,
        pending_romaji: bool,
    ) -> bool {
        if !self.can_install(reading, surface, explicit, pending_romaji) {
            return false;
        }
        let undo_accepted = self.accepted.clone();
        let undo_protected = self.protected.clone();
        let undo_hold = self.hold_auto_commit;
        self.install_correction(
            reading,
            surface,
            explicit,
            undo_accepted,
            undo_protected,
            undo_hold,
        );
        true
    }

    /// Replaces the currently active automatic correction with a context-refined
    /// one while retaining the pre-initial-apply baseline for undo/reversion.
    pub fn refine(
        &mut self,
        origin_reading: &str,
        surface: &str,
        explicit: bool,
        pending_romaji: bool,
    ) -> bool {
        if !self.can_install(origin_reading, surface, explicit, pending_romaji) {
            return false;
        }
        let Some(prev_undo) = self.undo.take() else {
            return self.apply(origin_reading, surface, explicit, pending_romaji);
        };
        self.accepted = prev_undo.accepted.clone();
        self.protected = prev_undo.protected.clone();
        self.install_correction(
            origin_reading,
            surface,
            explicit,
            prev_undo.accepted,
            prev_undo.protected,
            prev_undo.hold_auto_commit,
        );
        true
    }

    /// Silently reverts an automatic correction after growing context shows the
    /// unaltered input is valid, dropping undo and releasing auto-commit hold.
    pub fn auto_revert(&mut self, origin_reading: &str, reverted_surface: &str) -> bool {
        let Some(undo) = self.undo.take() else {
            return false;
        };
        self.accepted = undo.accepted;
        self.protected = undo.protected;
        self.hold_auto_commit = false;
        self.rejected_reading = (!origin_reading.is_empty()).then(|| origin_reading.into());
        self.rejected_surface = (!reverted_surface.is_empty()).then(|| reverted_surface.into());
        true
    }

    pub fn revert(&mut self) -> bool {
        let Some(undo) = self.undo.take() else {
            return false;
        };
        self.accepted = undo.accepted;
        self.protected = undo.protected;
        self.hold_auto_commit = undo.hold_auto_commit;
        self.rejected_reading = (!undo.reading.is_empty()).then_some(undo.reading);
        self.rejected_surface = Some(undo.surface);
        true
    }

    pub fn edited(&mut self) {
        if !self.is_applied() {
            self.hold_auto_commit = false;
        }
        if let Some(undo) = self.undo.as_mut() {
            undo.edited_after_apply = true;
        }
        self.rejected_reading = None;
        self.rejected_surface = None;
    }

    pub fn discard_undo(&mut self) {
        self.undo = None;
    }

    /// Suppresses exact matches and boomerang re-corrections of a differentially reverted prefix.
    pub fn is_rejected(&self, input: &str, candidate: &str) -> bool {
        let Some(rejected_surface) = self.rejected_surface.as_deref() else {
            return false;
        };
        if candidate == rejected_surface {
            return true;
        }
        if let Some(orig) = self.rejected_reading.as_deref() {
            if !orig.is_empty() && !rejected_surface.is_empty() {
                if let (Some(in_tail), Some(cand_tail)) = (
                    input.strip_prefix(orig),
                    candidate.strip_prefix(rejected_surface),
                ) {
                    if !in_tail.is_empty() && in_tail == cand_tail {
                        return true;
                    }
                }
            }
        }
        false
    }

    pub fn acceptances_for_commit(&self, displayed: &str) -> Vec<Acceptance> {
        let mut accepted = Vec::new();
        for entry in &self.accepted {
            if displayed.contains(&entry.surface) && !accepted.contains(entry) {
                accepted.push(entry.clone());
            }
        }
        accepted
    }

    pub fn fixed_prefix<'a>(&'a self, input: &str) -> &'a str {
        self.protected
            .iter()
            .filter(|surface| input.starts_with(surface.as_str()))
            .max_by_key(|surface| surface.len())
            .map(String::as_str)
            .unwrap_or_default()
    }

    pub fn committed(&mut self, displayed: &str) -> Vec<Acceptance> {
        let result = self.acceptances_for_commit(displayed);
        self.accepted.retain(|entry| !result.contains(entry));
        self.protected
            .retain(|surface| !displayed.contains(surface));
        self.undo = None;
        self.hold_auto_commit = false;
        result
    }
}

/// Pure, bounded JNI contract. No model, file access, process-global session or
/// conversion lock is involved. Session state belongs to one editor snapshot.
pub fn dispatch(value: &str) -> Result<serde_json::Value, String> {
    use serde_json::{json, Value};
    if value.len() > 131_072 {
        return Err("assist policy request exceeds limits".into());
    }
    let request: Value = serde_json::from_str(value).map_err(|error| error.to_string())?;
    let operation = request["op"].as_str().ok_or("missing policy operation")?;
    if operation == "ids" {
        return Ok(json!({
            "typoCorrection": TYPO_CORRECTION,
            "predictive": PREDICTIVE,
            "translation": TRANSLATION,
        }));
    }
    if operation == "plan" {
        return Ok(json!(request_plan(
            request["correctionEnabled"].as_bool().unwrap_or(false),
            request["predictionEnabled"].as_bool().unwrap_or(false),
            request["applied"].as_bool().unwrap_or(false),
            request["reading"].as_str().unwrap_or_default(),
            request["baselineReading"].as_str().unwrap_or_default(),
        )));
    }
    if operation == "choose" {
        let choices: Vec<(u32, u64)> = serde_json::from_value(request["choices"].clone())
            .map_err(|error| error.to_string())?;
        if choices.len() > 8 {
            return Err("too many assist choices".into());
        }
        return Ok(json!({"selected": choose_primary(&choices, request["displayed"].as_u64())}));
    }
    if operation == "ready" {
        return Ok(
            json!({"waitMs": FIRST_PRESENTATION_WAIT_MS, "ready": first_result_ready(
                request["predictionDone"].as_bool().unwrap_or(false),
                request["correctionDone"].as_bool().unwrap_or(false),
                request["automatic"].as_bool().unwrap_or(false),
                request["elapsedMs"].as_u64().unwrap_or(0),
            )}),
        );
    }
    if operation == "adopt" {
        return Ok(json!({"action": adoption_action(
            request["id"].as_u64().unwrap_or(0),
            request["explicit"].as_bool().unwrap_or(false),
            request["automatic"].as_bool().unwrap_or(false),
            request["applied"].as_bool().unwrap_or(false),
        )}));
    }
    let mut state: CorrectionState =
        serde_json::from_value(request.get("state").cloned().unwrap_or(json!({})))
            .map_err(|error| error.to_string())?;
    let reading = request["reading"].as_str().unwrap_or_default();
    let surface = request["surface"].as_str().unwrap_or_default();
    if reading.chars().count() > 4096 || surface.chars().count() > 4096 {
        return Err("assist policy text exceeds limits".into());
    }
    let result = match operation {
        "apply" => json!(state.apply(
            reading,
            surface,
            request["explicit"].as_bool().unwrap_or(false),
            request["pendingRomaji"].as_bool().unwrap_or(false)
        )),
        "refine" => json!(state.refine(
            reading,
            surface,
            request["explicit"].as_bool().unwrap_or(false),
            request["pendingRomaji"].as_bool().unwrap_or(false)
        )),
        "revert" => json!(state.revert()),
        "autoRevert" => json!(state.auto_revert(reading, surface)),
        "edited" => {
            state.edited();
            Value::Null
        }
        "discardUndo" => {
            state.discard_undo();
            Value::Null
        }
        "isRejected" => json!(state.is_rejected(reading, surface)),
        "acceptances" => json!(state.acceptances_for_commit(surface)),
        "committed" => json!(state.committed(surface)),
        "fixedPrefix" => json!(state.fixed_prefix(reading)),
        _ => return Err("unknown assist policy operation".into()),
    };
    Ok(json!({"state": state, "result": result}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn correction_and_prediction_have_independent_settings() {
        for correction in [false, true] {
            for prediction in [false, true] {
                assert_eq!(
                    request_plan(correction, prediction, false, "しますた", ""),
                    AssistRequestPlan {
                        correction,
                        prediction
                    },
                );
                // Normal conversion and an adopted correction both suppress
                // another fallback, even when sentence prediction is disabled.
                for (applied, corrected) in [(false, "しました"), (true, "")] {
                    assert_eq!(
                        request_plan(correction, prediction, applied, "しますた", corrected),
                        AssistRequestPlan {
                            correction: false,
                            prediction
                        },
                    );
                }
            }
        }
        assert!(request_plan(true, false, false, "しました", "しました").correction);
    }

    #[test]
    fn request_plan_jni_contract_runs_the_same_fallback_policy() {
        let response = dispatch(r#"{"op":"plan","correctionEnabled":true,"predictionEnabled":false,"reading":"しますた"}"#).unwrap();
        assert_eq!(
            response,
            serde_json::json!({"correction":true,"prediction":false})
        );
        let response = dispatch(r#"{"op":"plan","correctionEnabled":true,"predictionEnabled":true,"reading":"しますた","baselineReading":"しました"}"#).unwrap();
        assert_eq!(
            response,
            serde_json::json!({"correction":false,"prediction":true})
        );
    }

    #[test]
    fn assist_ids_expose_the_single_candidate_identity_source() {
        assert_eq!(
            dispatch(r#"{"op":"ids"}"#).unwrap(),
            serde_json::json!({
                "typoCorrection": TYPO_CORRECTION,
                "predictive": PREDICTIVE,
                "translation": TRANSLATION,
            })
        );
    }

    #[test]
    fn correction_always_wins_priority_and_replaces_displayed_prediction() {
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE), (80, TYPO_CORRECTION)], None),
            Some(TYPO_CORRECTION)
        );
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE), (30, TYPO_CORRECTION)], None),
            Some(TYPO_CORRECTION)
        );
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE), (30, TYPO_CORRECTION)], Some(PREDICTIVE)),
            Some(TYPO_CORRECTION)
        );
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE)], Some(TYPO_CORRECTION)),
            Some(TYPO_CORRECTION)
        );
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE), (60, TYPO_CORRECTION)], None),
            Some(TYPO_CORRECTION)
        );
        assert!(!first_result_ready(true, false, false, 199));
        assert!(first_result_ready(true, false, false, 200));
        assert!(first_result_ready(false, false, true, 0));
        assert_eq!(
            adoption_action(PREDICTIVE, false, true, false),
            AdoptionAction::Ignore
        );
        assert_eq!(
            adoption_action(TYPO_CORRECTION, true, false, true),
            AdoptionAction::Ignore
        );
    }

    #[test]
    fn apply_edit_apply_cancel_and_commit_preserve_only_explicit_acceptance() {
        let mut state = CorrectionState::default();
        assert!(state.apply("がこう", "学校", true, false));
        state.edited();
        assert!(state.hold_auto_commit);
        state.edited();
        assert!(!state.hold_auto_commit);
        assert!(state.apply("学校にいきます", "学校へ行きます", false, false));
        assert!(state.revert());
        assert_eq!(state.rejected_surface.as_deref(), Some("学校へ行きます"));
        assert_eq!(state.fixed_prefix("学校に行きます"), "学校");
        assert!(state.committed("別候補").is_empty());
        assert_eq!(
            state.committed("学校に行きます"),
            vec![Acceptance {
                reading: "がこう".into(),
                surface: "学校".into()
            }]
        );
        assert!(state.committed("学校に行きます").is_empty());
    }

    #[test]
    fn pending_romaji_and_failed_transactions_do_not_replace_input() {
        let original = CorrectionState::default();
        let mut planned = original.clone();
        assert!(!planned.apply("なn", "何", false, true));
        assert_eq!(planned, original);
        assert!(planned.apply("なn", "何", true, true));
        assert!(!original.is_applied());
        assert!(planned.revert());
        assert!(planned.acceptances_for_commit("何").is_empty());
    }

    #[test]
    fn corrected_normal_candidate_owns_prediction_without_a_second_correction() {
        assert_eq!(
            assist_reading("しますた", "しました", false),
            Some("しました")
        );
        assert_eq!(assist_reading("しますた", "しました", true), None);
        assert_eq!(assist_reading("しました", "", true), Some("しました"));
        assert_eq!(
            assist_reading("しました", "しました", false),
            Some("しました")
        );
    }

    #[test]
    fn strength_lossy_and_u8_roundtrip_and_persistent_diff_undo() {
        for (raw, expected) in [
            ("off", AutoTypoCorrectionStrength::Off),
            ("disabled", AutoTypoCorrectionStrength::Off),
            ("none", AutoTypoCorrectionStrength::Off),
            ("false", AutoTypoCorrectionStrength::Off),
            ("conservative", AutoTypoCorrectionStrength::Conservative),
            ("plan-c", AutoTypoCorrectionStrength::Conservative),
            ("low", AutoTypoCorrectionStrength::Conservative),
            ("standard", AutoTypoCorrectionStrength::Standard),
            ("aggressive", AutoTypoCorrectionStrength::Aggressive),
            ("plan_a", AutoTypoCorrectionStrength::Aggressive),
            ("strong", AutoTypoCorrectionStrength::Aggressive),
            ("unknown", AutoTypoCorrectionStrength::Standard),
        ] {
            assert_eq!(AutoTypoCorrectionStrength::from_str_lossy(raw), expected);
        }
        for preset in [
            AutoTypoCorrectionStrength::Off,
            AutoTypoCorrectionStrength::Conservative,
            AutoTypoCorrectionStrength::Standard,
            AutoTypoCorrectionStrength::Aggressive,
        ] {
            assert_eq!(AutoTypoCorrectionStrength::from_u8(preset.to_u8()), preset);
            assert_eq!(
                preset.is_enabled(),
                preset != AutoTypoCorrectionStrength::Off
            );
        }

        let mut state = CorrectionState::default();
        assert!(state.apply("きょおは", "きょうは", false, false));
        assert!(state.is_applied());
        assert!(state.can_revert());

        // First edit keeps can_revert and hold_auto_commit, drops is_applied.
        state.edited();
        assert!(!state.is_applied());
        assert!(state.can_revert());
        assert!(state.hold_auto_commit);

        // Second edit drops hold_auto_commit, still allows explicit differential revert.
        state.edited();
        assert!(!state.hold_auto_commit);
        assert!(state.can_revert());

        // Revert records both reading and surface to block boomerang re-corrections.
        assert!(state.revert());
        assert!(!state.can_revert());
        assert!(state.is_rejected("きょおは", "きょうは"));
        assert!(state.is_rejected("きょおはいいてんき", "きょうはいいてんき"));
        assert!(!state.is_rejected("きょおはいいてんき", "きょうは良いてんき"));

        // A second apply after edited replaces undo while keeping accepted/protected.
        assert!(state.apply("がこう", "学校", true, false));
        state.edited();
        assert!(state.apply("いぃ", "いい", true, false));
        assert_eq!(state.acceptances_for_commit("学校がいい").len(), 2);
        state.discard_undo();
        assert!(!state.can_revert());
        assert_eq!(state.acceptances_for_commit("学校がいい").len(), 2);
    }

    #[test]
    fn refine_preserves_pre_initial_baseline_and_auto_revert_clears_undo_and_blocks_boomerang() {
        let mut state = CorrectionState::default();
        assert!(state.apply("きょおは", "きょうは", false, false));
        state.edited();
        assert!(state.refine("きょおはし", "きょおはし？", false, false));
        assert!(state.is_applied());
        assert!(state.can_revert());
        assert_eq!(state.fixed_prefix("きょおはし？"), "きょおはし？");

        state.edited();
        assert!(!state.is_applied());
        // Second refinement replaces superseded protected prefix and preserves true origin acceptance on explicit.
        assert!(state.refine("しゅてんします", "しゅってんします", true, false));
        assert_eq!(state.fixed_prefix("しゅってんします"), "しゅってんします");
        assert!(state.acceptances_for_commit("今日出展します").is_empty());
        assert_eq!(
            state.acceptances_for_commit("しゅってんします"),
            vec![Acceptance {
                reading: "しゅてんします".into(),
                surface: "しゅってんします".into(),
            }]
        );
        // After revert, neither intermediate auto-applied acceptance nor protected entry leaks.
        assert!(state.revert());
        assert!(!state.can_revert());
        assert!(state.acceptances_for_commit("しゅってんします").is_empty());
        assert_eq!(state.fixed_prefix("きょうは"), "");

        // auto_revert clears can_revert and hold_auto_commit, blocks boomerang, and clears on next edit.
        assert!(state.apply("しゅてん", "しゅうてん", false, false));
        state.edited();
        assert!(state.auto_revert("しゅてんどうじ", "しゅうてんどうじ"));
        assert!(!state.can_revert());
        assert!(!state.hold_auto_commit);
        assert!(state.is_rejected("しゅてんどうじ", "しゅうてんどうじ"));
        state.edited();
        assert!(!state.is_rejected("しゅてんどうじ", "しゅうてんどうじ"));

        // Verify JNI dispatch exposes refine and autoRevert.
        let applied = dispatch(
            &serde_json::json!({
                "op": "apply",
                "state": {},
                "reading": "しゅてん",
                "surface": "しゅうてん",
                "explicit": false,
                "pendingRomaji": false,
            })
            .to_string(),
        )
        .unwrap();
        let edited =
            dispatch(&serde_json::json!({"op": "edited", "state": applied["state"]}).to_string())
                .unwrap();
        let refined = dispatch(
            &serde_json::json!({
                "op": "refine",
                "state": edited["state"],
                "reading": "しゅてんし",
                "surface": "しゅってんし",
                "explicit": false,
                "pendingRomaji": false,
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(refined["result"], true);
        let reverted = dispatch(
            &serde_json::json!({
                "op": "autoRevert",
                "state": refined["state"],
                "reading": "しゅてんどうじ",
                "surface": "しゅうてんどうじ",
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(reverted["result"], true);
        assert!(reverted["state"]["undo"].is_null());
    }
}
