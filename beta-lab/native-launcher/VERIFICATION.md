# Native Owner beta verification — 2026-09-06

**Partial verification: offline checks pass; live runtime remains unverified.**

`cargo test --offline --manifest-path beta-lab/native-launcher/Cargo.toml`
passed **18 tests, 0 failed, 0 ignored**. The final run compiled in 3.74 seconds;
the test executable completed in 1.04 seconds. `cargo fmt --check` also passed.

The final style-only offline release build completed in 11.66 seconds and produced
`target/release/CelesteOwnerBeta.exe` (6,739,456 bytes), SHA-256
`ff2a76b625179ca1c3a4b171b32ffb4f66cb708b2990e719659287abd21ca4aa`.
The 18 behavior tests above preceded this appearance-only update; no authentication,
delivery, targeting, retry or lifecycle logic changed. Final formatting check passed.

The behavior checks cover:

- The actual Python-server schema-2 signed golden fixture and canonical catalog.
- UID/device/exact-installer binding, validity windows and signed manifest mutations.
- Strict duplicate-key/numeric/base64 parsing, bounded transport metadata and reads.
- Immutable content-addressed A publication: concurrent writers, locked file reuse,
  mismatch preservation, and cleanup of only owned temporary files.
- Atomic protected-file replacement failure preserves its existing target.
- Inspection accepts only the matching canonical directory, PID, creation time,
  Minecraft version and absence of an already loaded Celeste client.
- Starting is not reported as active; failed status retains its reason.
- Relative directories, missing JDKs and empty agent files fail closed.
- Authentication error details redact request secrets before display truncation.
- The original native-verifier shared UID-7 cache path is reused with no beta suffix.
- Retry requires confirmed inactive state and the same signed A hash/release;
  active, stopped, failed and restart-required states do not admit a new lifetime.
- Start transmits the exact seven required fields including signed expected release
  and catalog hash. An internal-bootstrap-only change yields a different verified
  catalog hash even when the release ID and A digest are unchanged; Retry rejects it.

An independent source review additionally prompted fixes for signed-digest trust
handoff, carrying the selected timestamp through the Java Attach CLI, and applying
the helper deadline while stdin is written concurrently. No full native-to-Java
helper integration test has been run by these Rust tests.

No test used a real license or credential store, collected hardware, contacted a
live service, enrolled/reset a license, installed into a game, launched Minecraft,
attached a payload, changed mods/libraries/launcher files, or published artifacts.
Temporary-file tests wrote only their own uniquely reserved fixture directories.

Initial native-window QA found system-theme contrast problems. The final update
pins dark mode and explicitly styles light text, dark input/combo/button states,
readable disabled controls and borderless cards. Actual-window reinspection
passed. The new `Celeste Owner Beta QA` profile is selected with its clean game
process, and the UID-7 beta catalog is deployed as Fly v65. The protected Java
bootstrap inspected that process successfully and found no startup Celeste.
Live authentication, signed native downloads, attach-ready transition, gameplay
and Stop cleanup remain pending at the user's authentication click. The separate
original Minecraft process still contains production Celeste and is not a valid
second-runtime target. See `../RELEASE-STATUS.md` for the exact release evidence.

Source provenance and the runtime contract are in [README.md](README.md).
