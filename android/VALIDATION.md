# Native Android validation

This is a work-in-progress native port. These checks establish the named behavior,
not full feature parity or a physical phone installation.

```sh
python3 /workspace/android-tools/build.py :core:test :app:testDebugUnitTest :app:assembleDebug
```

Latest cloud APK gate (2026-11-12): `assembleDebug lint ktlintCheck` passed
for **version 0.2.0, target API 36** after a clean rebuild. Lint has zero errors
and **52 advisory warnings** (37 KTX style suggestions, 9 newer-dependency notices,
4 Modifier conventions, ARM64-only ChromeOS support and monochrome launcher icon).
It is not warning-free. ktlint 1.7.1 reports no violations.

The last complete cloud **test** run before cleaning passed **150 tests** (53 core,
97 Android). Subsequent targeted runs passed the tablet/landscape journeys and
local reset/rollback/confirmation tests. After the API-36 clean rebuild, the core
53 tests and five real local HTTPS streaming tests pass, but 16 Android test-class
initializations fail while downloading the Robolectric runtime: Maven Central
redirects its JAR to `gcp.mirrors.maven.org`, which returns proxy CONNECT 403 in
this environment. Those are not counted as passing or skipped application tests.
The environment draft adds that hostname; the full final cloud suite must be retried
when network settings apply. An earlier API-level lint error was fixed with an
explicit Android-12 guard and the latest APK/lint gate passes.

Hosted CI: the corrected [full native gate passed](https://github.com/Dibakar49/super-agent/actions/runs/43161881644)
for source commit `da77427e`. The run's overall status is **Success**; the unit/Compose,
lint, ktlint and APK step passed, and both APK/report artifact uploads passed.
This independently validates the final Kotlin sources at target API 36 even though
this cloud instance's clean Robolectric download remains blocked. The committed APK
was built/signed in the cloud workspace; CI builds its own debug-signed APK.

The first [CI run](https://github.com/Dibakar49/super-agent/actions/runs/43161497257)
failed in SDK setup before tests. The corrected workflow uses the documented
preinstalled SDK on Ubuntu 24.04 and pinned Node 24 actions. Neither that earlier
failure nor a successful upload alone is counted as application test evidence.

## Verified behavior

- Responses/Chat Completions SSE framing, fragmented parallel tool-call assembly,
  Unicode socket chunk boundaries, JSON fallback, credential-safe redirects and
  prompt socket cancellation. Truncated or malformed streams cannot execute tools.
  Runtime/recovery tests preserve partial text; Compose shows it before completion.
- Strict Android 12+ on-device speech fallback, offline intent, recognizer disposal,
  late-result fencing and unsupported-service guidance. Actual language packs/audio
  hardware remain untested.
- Chosen conversation titles survive new messages and reopening; clearing restores
  the generated summary. Latest hourly cron/outcome contracts use a pinned upstream
  TypeScript oracle while old persisted outcome strings stay readable.
- Automatic actions persist goal/routine scope, with initialization-pause regression
  tests proving no MCP call occurs after a scope is paused.
- Confirmed local-data reset cancels work, waits for the agent gate, atomically resets
  records and removes saved keys/files while retaining imported models. Injected
  reset failure preserves records, files and credentials.

- Original TypeScript fixtures cover routines (101 cron/timezone/DST cases),
  skill commands, scoped memory context, goal/routine links, multipart question
  answers, subagent identities/results and approval outcome strings. Attachment parity adds 208 MIME/sniffing cases
  and Unicode filename/text extraction boundaries. Chromium
  fixtures check original light/dark OKLCH tokens.
- Dictation core tests cover original format/draft append parity, cancellation of
  late recorder/transcription results, retained-audio retry/send intent, cleanup of
  invalid audio and the two-minute stop. Android framework shadows verify mono AAC
  settings, hardware-limit callbacks and recorder release. Scripted HTTPS verifies
  multipart lengths/fields, destination/key isolation, no redirects and safe errors.
  Native UI journeys verify settings and microphone denial without losing a draft.
- Approval parity includes 69 connected-action safety cases, 960 policy decisions
  and nine wildcard cases. Rules, source defaults, safety stops and reviewer
  failure behavior match these references. Computer dispatch is excluded.
- Android SQLite tests cover non-destructive upgrades, transaction rollback,
  scoped/forgotten memory, routine claims/fatigue/history, per-Bot/tool grants,
  goals/runs, approval rules, pending decisions and process-death uncertainty.
- Scripted provider/MCP integration executes real saved local tools and goals,
  validates original provider continuations, reopens pending approvals/questions,
  consumes decisions once, rechecks live grants/rules/settings, and prevents old
  batched steps from running after a human decision. These are transport fixtures,
  not live model/service calls. Attachment integration binds files before model
  dispatch, reopens waiting turns with references, refuses missing current files
  before accepting a turn, and verifies exact streamed JSON/base64 lengths.
- Android notification tests verify opt-in/permission, private lock-screen content,
  matching inbox/conversation intents, dismissal deduplication and resolved/deleted
  item cleanup. Notification-link Activity journeys filter the matching question
  and reject unrelated URI schemes.
- Native Compose Activity journeys run on Android API 34 and 35. They cover Home,
  sidebar, Bot profiles, memory, skills, model settings, routines, connections,
  approval rules, durable approval cards, goals, multipart questions, independent
  conversation drafts, navigation state, full-root modal dismissal/accessibility,
  pinning, confirmed deletion and actual document-picker result delivery with
  private staged files, removal and preservation across navigation/send refusal.

Build output is `app/build/outputs/apk/debug/app-debug.apk`, ARM64, API 26+,
compile SDK 36, target 36. The project lives under `android/`; audit documents,
screenshots, CI and the committed APK also live at repository level. Original
tracked TypeScript/Python/Rust files remain unchanged. Toolchains and caches stay
outside version control.

## Reproducible references

After installing original frozen Bun dependencies, run from the repository root:

```sh
bun android/scripts/generate-parity-fixtures.ts
bun android/scripts/upstream-delta-fixtures.ts
```

The script reads original source, regenerates the named comparison fixtures and
routine/goal/question schemas and policy regex data, then removes temporary modules.
Other existing shared-helper and Chromium fixtures record their reference results.
No TypeScript is loaded by the Android application.

## Visual evidence

Native Canvas screenshots are under `app/build/test-home/screenshots/`, including
Home, memory/dialogs, skills, routines, connections, approvals/rules, goals,
questions, attachments, dictation settings/permission denial and confirmed conversation deletion on API 34 and 35.

Reference captures use the actual original Vite app with API fixtures, not a
production backend. Cloud browser capture scripts/images remain under
`/workspace/android-tools`. Native layouts use upstream Inter Variable (5.3.0), Tabler paths,
logo and the updated pinned theme tokens. Tablet and landscape captures are also
tracked in `docs/screenshots/`; phone journey captures were made before the 0.2.0
version-label bump. Every route, complete pixel equality, hardware rendering
and a full accessibility audit remain unverified.

## Runtime boundaries and remaining conversion

No physical Android device, microphone capture/codec output, compatible MediaPipe `.task` model or live provider/
MCP credentials have been used. Hardware Keystore, native model RAM/inference,
real service calls and Android idle/reboot background timing remain unverified.
Robolectric native rendering does not substitute for device validation.

On-device inference currently produces text replies; tool execution uses provider
API mode. OAuth/catalogue accounts, goal event adapters, Needs you/tasks/check-ins,
clipboard/drag attachment import, live two-way voice calls, learning, additional agent frameworks, team/sharing/admin and
remaining original UI routes are still incomplete. Computer use is excluded.

The compiled and statically verified debug APK is 27,951,847 bytes. `apksigner verify` and
`zipalign -c -P 16 4` passed; every native ELF load segment has at least 16 KB
alignment. SHA-256:
`3f1a525fd0510bcd9e9fb7f0c570ead932a3af26b5e3832f7f9563b2e4ad75da`.
This is a debug APK, not a store release. Recheck after any further build changes.
