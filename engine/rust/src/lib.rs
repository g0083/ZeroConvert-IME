//! The Windows assistance implementation is compiled directly, never translated.
#[path = "../../../windows/crates/server/src/ai_assist/mod.rs"]
pub mod ai_assist;

pub mod bridge;

#[path = "../../../windows/crates/shared/src/conversion_policy.rs"]
pub mod conversion_policy;
