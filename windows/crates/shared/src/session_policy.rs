//! Conversion-session state transitions shared by the Windows client and the
//! browser build.
//!
//! `conversion_policy.rs` owns per-character input policy and the cursor,
//! commit and deletion decisions. This module owns the candidate-selection
//! transitions the TSF client and the Web Worker must agree on, so one
//! keystroke resolves to the same candidate row on both targets.
//!
//! Evidence for the encoded Windows behaviour:
//!
//! - `windows/crates/client/src/engine/composition/clause_state.rs`
//!   (`apply_set_selection`) maps `SetSelectionType::Up` / `Down` to
//!   `selection ± 1` and `Number(n)` to an absolute index.
//! - `windows/crates/client/src/engine/composition.rs` (`select_candidate`)
//!   clamps the desired index into `0..candidates.len()`; the list never wraps.
//! - `candidate_preview_actions` opens the list from `Composing` with
//!   `SetSelection(Down)`, and the stored selection starts at `0`, so the first
//!   `Space` picks the row after the composing position.
//!
//! The browser Worker reaches this through the `zerocon_web_live_policy` wasm
//! (`zerocon_session_selection`); the TSF client calls it directly from
//! `apply_set_selection`. Edit this file at its Windows source; the engine
//! compiles it through `#[path]`.

/// Candidate movement requested by a key or a clicked row.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CandidateSelectionMove {
    /// `Space`, `Tab`, `Reconvert` and `Down` in an active conversion.
    Next,
    /// `Up` in an active conversion.
    Previous,
    /// A number key or a directly clicked candidate row.
    Absolute(i32),
}

/// Resolves the row a candidate operation selects.
///
/// `current` is the platform's stored selection index. Both targets keep it at
/// `0` while composing. Returns `None` when the list is empty; otherwise the
/// result is clamped into `0..candidate_count`.
pub fn resolve_candidate_index(
    current: i32,
    candidate_count: usize,
    movement: CandidateSelectionMove,
) -> Option<i32> {
    if candidate_count == 0 {
        return None;
    }
    let last = candidate_count.saturating_sub(1).min(i32::MAX as usize) as i32;
    Some(match movement {
        CandidateSelectionMove::Next => current.saturating_add(1).clamp(0, last),
        CandidateSelectionMove::Previous => current.saturating_sub(1).clamp(0, last),
        CandidateSelectionMove::Absolute(index) => index.clamp(0, last),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn next_steps_down_and_clamps_at_the_last_row() {
        assert_eq!(
            resolve_candidate_index(0, 3, CandidateSelectionMove::Next),
            Some(1)
        );
        assert_eq!(
            resolve_candidate_index(1, 3, CandidateSelectionMove::Next),
            Some(2)
        );
        assert_eq!(
            resolve_candidate_index(2, 3, CandidateSelectionMove::Next),
            Some(2)
        );
    }

    #[test]
    fn previous_steps_up_and_clamps_at_zero() {
        assert_eq!(
            resolve_candidate_index(2, 3, CandidateSelectionMove::Previous),
            Some(1)
        );
        assert_eq!(
            resolve_candidate_index(1, 3, CandidateSelectionMove::Previous),
            Some(0)
        );
        assert_eq!(
            resolve_candidate_index(0, 3, CandidateSelectionMove::Previous),
            Some(0)
        );
    }

    #[test]
    fn absolute_rows_clamp_like_select_candidate() {
        assert_eq!(
            resolve_candidate_index(0, 3, CandidateSelectionMove::Absolute(9)),
            Some(2)
        );
        assert_eq!(
            resolve_candidate_index(0, 3, CandidateSelectionMove::Absolute(-1)),
            Some(0)
        );
        assert_eq!(
            resolve_candidate_index(0, 3, CandidateSelectionMove::Absolute(1)),
            Some(1)
        );
    }

    #[test]
    fn an_empty_candidate_list_selects_nothing() {
        for movement in [
            CandidateSelectionMove::Next,
            CandidateSelectionMove::Previous,
            CandidateSelectionMove::Absolute(0),
        ] {
            assert_eq!(resolve_candidate_index(0, 0, movement), None);
        }
    }

    #[test]
    fn a_stale_selection_beyond_the_list_recovers_to_a_valid_row() {
        assert_eq!(
            resolve_candidate_index(9, 3, CandidateSelectionMove::Next),
            Some(2)
        );
        assert_eq!(
            resolve_candidate_index(9, 3, CandidateSelectionMove::Previous),
            Some(2)
        );
    }
}
