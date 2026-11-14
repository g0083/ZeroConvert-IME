//! The Windows assistance implementation is compiled directly, never translated.
// The shared assistance sources address policy through the Windows crate name.
// Resolve that path to this crate so one policy file serves both targets.
extern crate self as shared;

#[path = "../../../windows/crates/server/src/ai_assist/mod.rs"]
pub mod ai_assist;

pub mod bridge;

#[path = "../../../windows/crates/shared/src/conversion_policy.rs"]
pub mod conversion_policy;

#[path = "../../../windows/crates/shared/src/assist_policy.rs"]
pub mod assist_policy;
