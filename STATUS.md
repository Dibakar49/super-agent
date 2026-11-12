# Android port status

Audited 2026-11-12. Existing checkout: `Dibakar49/super-agent`; the requested
`Dibakar49/superaiagent` URL returned HTTP 404. Continue with the supplied checkout.

## Baseline

- Original OpenBot reference: `0649b1ab3162c4664cc1bd19cd2ed715c6a45d68`
  (2026-11-08, package version 0.3.4); 3,453 tracked original files.
- Native project: `android/`, 102 Kotlin files / 8,264 lines, two Gradle modules.
  It was entirely untracked when this phase started; preserve it before changes.
- `assembleDebug` passed on 2026-11-12 with JDK 21 / Gradle 8.13 / SDK 36.
  APK: `artifacts/openbot-android-debug.apk` (ARM64, API 26+, target 35).
- Previous complete verification: 124 tests (44 core, 80 Android), lint zero
  errors, APK signature and 16 KB ZIP/ELF alignment passed; advisory lint warnings remain.

## Implemented

Native Compose home/sidebar, Bots, conversations, Markdown/code copying, skills,
scoped personal/observed memory, routines/WorkManager, goals and run history, MCP
connections/grants, original approval policy/rules, durable decisions/questions,
file/image attachments, notifications/deep links, API providers and optional
MediaPipe text inference. Voice dictation includes permission, bounded recording,
transcription, cancellation/retry and separate encrypted credentials.

## Missing or partial

- Streaming text, actual subagent/delegation execution, queued foreground turns.
- OAuth/catalogue accounts, goal event adapters, check-ins/tasks, learning and
  workspace/team/sharing/admin flows; live two-way voice calls.
- On-device model tool execution; clipboard/drag image import.
- Tablet/landscape polish, complete UI parity, clear-data settings and reusable CI.
- Architecture currently uses a large AndroidViewModel, SQLiteOpenHelper and
  direct HTTPS; Hilt/Room/DataStore/OkHttp migration is not implemented.
- No ktlint/detekt gate yet. Computer use remains deliberately excluded.

## Known risks / unverified behavior

- Fixed goal/routine pause during automatic MCP initialization: persisted run
  scope is checked transactionally immediately before execution; regression tests
  verify that pausing either scope prevents the external call.
- Global ViewModel busy state limits navigation/edits while generating.
- No physical-device, live provider/MCP, real microphone/codec, imported-model
  performance, or idle/reboot timing validation. Test fixtures are not live calls.
- Git push succeeds. GitHub API is blocked by the environment network policy
  (proxy CONNECT 403), so PR creation through `gh` is currently unavailable.
- Upstream delta: 128 commits / 1,035 changed paths to `68fe3277`, recorded in
  `docs/upstream/`. Latest stable release page: v1.0.2; main package: 1.0.1.
- Added and tested native sidebar rename, hourly schedule descriptions / typed
  timezone refusal, and updated unrun outcomes with legacy transcript compatibility.
- REA static analysis and open-source assistant/license comparison are recorded in
  `FEATURES.md`; selected-file static coverage does not establish full parity.

See `android/MIGRATION.md` and `android/VALIDATION.md` for mapping and evidence.
