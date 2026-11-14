//! Assistance decisions shared by TSF and InputConnection hosts.
//! Hosts retain editor snapshots and execute a plan before installing its new state.
use serde::{Deserialize, Serialize};

pub const TYPO_CORRECTION: u64 = 999901;
pub const PREDICTIVE: u64 = 999902;
pub const TRANSLATION: u64 = 999904;
pub const FIRST_PRESENTATION_WAIT_MS: u64 = 200;

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

/// Keep the displayed target stable while late results are added to the list.
pub fn choose_primary(choices: &[(u32, u64)], displayed: Option<u64>) -> Option<u64> {
    displayed.or_else(|| {
        choices
            .iter()
            .max_by_key(|(score, id)| (*score, *id == PREDICTIVE))
            .map(|(_, id)| *id)
    })
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
    pub rejected_surface: Option<String>,
    pub hold_auto_commit: bool,
    undo: Option<CorrectionUndo>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
struct CorrectionUndo {
    surface: String,
    accepted: Vec<Acceptance>,
    protected: Vec<String>,
    hold_auto_commit: bool,
}

impl CorrectionState {
    pub fn is_applied(&self) -> bool {
        self.undo.is_some()
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
        if reading.is_empty()
            || reading.chars().count() > 512
            || surface.trim().is_empty()
            || surface.chars().count() > 512
            || self.is_applied()
            || (!explicit && pending_romaji)
        {
            return false;
        }
        self.undo = Some(CorrectionUndo {
            surface: surface.into(),
            accepted: self.accepted.clone(),
            protected: self.protected.clone(),
            hold_auto_commit: self.hold_auto_commit,
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
        self.rejected_surface = None;
        true
    }

    pub fn revert(&mut self) -> bool {
        let Some(undo) = self.undo.take() else {
            return false;
        };
        self.accepted = undo.accepted;
        self.protected = undo.protected;
        self.hold_auto_commit = undo.hold_auto_commit;
        self.rejected_surface = Some(undo.surface);
        true
    }

    pub fn edited(&mut self) {
        if !self.is_applied() {
            self.hold_auto_commit = false;
        }
        self.undo = None;
        self.rejected_surface = None;
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
        "revert" => json!(state.revert()),
        "edited" => {
            state.edited();
            Value::Null
        }
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
    fn late_correction_keeps_displayed_prediction_and_ties_prefer_prediction() {
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE), (80, TYPO_CORRECTION)], None),
            Some(TYPO_CORRECTION)
        );
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE), (80, TYPO_CORRECTION)], Some(PREDICTIVE)),
            Some(PREDICTIVE)
        );
        assert_eq!(
            choose_primary(&[(60, PREDICTIVE), (60, TYPO_CORRECTION)], None),
            Some(PREDICTIVE)
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
}
