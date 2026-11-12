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

- Goal/routine pause during an automatically admitted MCP call needs a persisted
  execution-scope recheck; current automatic calls have no goal/routine reference.
- Global ViewModel busy state limits navigation/edits while generating.
- No physical-device, live provider/MCP, real microphone/codec, imported-model
  performance, or idle/reboot timing validation. Test fixtures are not live calls.
- GitHub CLI authentication reports failure; verify network versus authorization
  before claiming push/PR access. Upstream comparison and research are in progress.

See `android/MIGRATION.md` and `android/VALIDATION.md` for mapping and evidence.
