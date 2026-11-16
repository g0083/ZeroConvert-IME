//! Learning-mode capabilities shared by the Windows client, Android and the
//! browser build.
//!
//! The Swift server owns the learning stores; this module only decides what the
//! current mode permits, so every host enables the same operations and sends the
//! same requests. The mode arrives as the settings string rather than as a
//! struct, because the settings type lives in the Windows crate while the Web
//! build reads the same value from JSON.

/// What a learning mode allows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LearningCapability {
    /// Committing a candidate writes new learning.
    pub record: bool,
    /// Conversion may read existing learning.
    pub read: bool,
    /// One candidate's learning can be deleted.
    pub forget: bool,
    /// The whole learning store can be erased.
    pub clear: bool,
}

/// Normalizes a settings value to one of `enabled`, `read_only`, `disabled`.
///
/// Windows trims, lower-cases and maps hyphens to underscores before matching
/// (`normalizedLearningMode` in `azookey_server.swift`), and accepts the legacy
/// aliases below. The browser receives the same strings from the staged settings.
pub fn normalize_mode(mode: &str) -> &'static str {
    let normalized = mode.trim().to_ascii_lowercase().replace('-', "_");
    match normalized.as_str() {
        "read_only" | "readonly" | "no_new_learning" => "read_only",
        "disabled" => "disabled",
        _ => "enabled",
    }
}

/// Windows keeps these distinctions: `read_only` reads existing memory but
/// records nothing, and only `disabled` stops reading and forgetting. Clearing
/// stays available in every mode so a user can remove stored history.
pub fn capability(mode: &str) -> LearningCapability {
    match normalize_mode(mode) {
        "read_only" => LearningCapability {
            record: false,
            read: true,
            forget: true,
            clear: true,
        },
        "disabled" => LearningCapability {
            record: false,
            read: false,
            forget: false,
            clear: true,
        },
        _ => LearningCapability {
            record: true,
            read: true,
            forget: true,
            clear: true,
        },
    }
}

/// A commit records learning only when the mode allows writing and the engine
/// marked the candidate as a learning target.
pub fn should_record_commit(mode: &str, is_learning_target: bool) -> bool {
    is_learning_target && capability(mode).record
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enabled_allows_every_learning_operation() {
        let allowed = capability("enabled");
        assert!(allowed.record && allowed.read && allowed.forget && allowed.clear);
        assert!(should_record_commit("enabled", true));
    }

    #[test]
    fn read_only_keeps_reading_and_forgetting_but_records_nothing() {
        let allowed = capability("read_only");
        assert!(!allowed.record);
        assert!(allowed.read);
        assert!(allowed.forget);
        assert!(allowed.clear);
        assert!(!should_record_commit("read_only", true));
        // The legacy aliases Windows accepts map to the same mode.
        for alias in ["readonly", "no_new_learning"] {
            assert_eq!(capability(alias), capability("read_only"));
        }
    }

    #[test]
    fn disabled_stops_reading_and_forgetting_but_can_still_clear() {
        let allowed = capability("disabled");
        assert!(!allowed.record && !allowed.read && !allowed.forget);
        assert!(allowed.clear);
        assert!(!should_record_commit("disabled", true));
        assert_eq!(normalize_mode("  DISABLED "), "disabled");
    }

    #[test]
    fn an_unknown_mode_falls_back_to_enabled_like_the_settings_default() {
        assert_eq!(capability("unexpected"), capability("enabled"));
        assert_eq!(normalize_mode(""), "enabled");
        // Windows normalizes case, surrounding spaces and hyphens first.
        assert_eq!(normalize_mode(" Read-Only "), "read_only");
        assert_eq!(normalize_mode("NO_NEW_LEARNING"), "read_only");
    }

    #[test]
    fn a_non_learning_candidate_is_never_recorded() {
        for mode in ["enabled", "read_only", "disabled"] {
            assert!(!should_record_commit(mode, false));
        }
    }
}
