# Shared conversion engine

Follow the root guide. `engine/rust/src/lib.rs` compiles Windows source modules
directly. `scripts/prepare-shared-engine.mjs` stages the same Windows Swift sources
and pinned dependency for Android. `engine/build/` is generated and ignored.

- Edit conversion policy at its Windows source, not in the generated staging tree.
- Put platform I/O and ABI adaptations here. Do not add another conversion implementation.
- Preserve candidate identities, input styles, Unicode units, privacy and request generations.
- Run shared Rust tests, Windows checks for changed Windows layers, existing Android
  regressions and the rebuilt Android APK/lint checks. Native behavior needs a device.
- Use `Build-Android.bat`; it checks source/binary fingerprints and builds both ABIs.
  It does not install, change the selected IME, sign a release, commit or push.
- APKs are split by ABI; read output-metadata.json for current filenames.
  `-UniversalApk` additionally builds a universal APK. Keep upstream ARM CPU
  feature detection and the baseline backend; never force new instructions.
  The dlopen CPU backend manifest is part of native dependency verification.
