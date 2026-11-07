// Inputs that reach the compiled Android artifacts, shared by the staging
// generator and the checker so the two cannot drift apart.
//
// Only files that are actually compiled belong here. A file that is not
// compiled must not invalidate both ABIs, and a compiled input that is missing
// lets a stale binary pass verification.

// Compiled into libzenz_jni.so by engine/native/CMakeLists.txt.
export const NATIVE_INPUTS = [
  'android/app/src/main/cpp/zenz_jni.cpp',
  'android/app/src/main/cpp/assist_jni.cpp',
  'android/app/src/main/cpp/assist_jni.h',
  'android/app/src/main/cpp/azookey_louds.cpp',
  'android/app/src/main/cpp/azookey_louds.h',
  'android/app/src/main/cpp/keyboard_typo_dictionary.inc',
];

// Build inputs that change the staged sources without touching the pinned
// converter archive, plus the shared policy and dependency pins.
export const SHARED_INPUTS = [
  'windows/scripts/converter_patch.py',
  'windows/crates/shared/src/conversion_policy.rs',
  'windows/server-swift/Package.swift',
  'windows/server-swift/Package.resolved',
  'engine/rust/Cargo.toml',
  'engine/rust/Cargo.lock',
];

// Present in the source tree but never compiled. Fingerprinting them would
// force a full rebuild of both ABIs for an edit that changes no artifact.
export const SKIPPED_SOURCE_NAMES = ['mod.rs.bak'];
