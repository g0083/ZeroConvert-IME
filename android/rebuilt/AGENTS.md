# Rebuilt Android host

Follow `../AGENTS.md` and `../../engine/AGENTS.md`. This application compiles the
existing keyboard/settings/service sources with `BuildConfig.WINDOWS_ENGINE=true`.
The application ID remains `com.zeroconvert.ime` so a compatible signed update can
retain preferences and history. Do not install or remove the old application to
work around signature/version problems.

Conversion policy belongs to the Windows/shared engine. Add Android editor and
native ABI adaptations in `app/.../engine` or `engine/native`; do not implement a
second Kotlin/C++ candidate ranking or correction pipeline here.

Native binaries must pass `scripts/check-shared-engine.mjs` before packaging.
Tests for Android UI/state stay in the existing app suite; shared policy tests run
with `cargo test --manifest-path engine/rust/Cargo.toml --lib`.
