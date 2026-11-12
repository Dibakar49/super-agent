# Native Android validation

This is a work-in-progress native port. These checks establish the named behavior,
not full feature parity or a physical phone installation.

```sh
python3 /workspace/android-tools/build.py :core:test :app:testDebugUnitTest :app:assembleDebug
```

Latest complete run: **124 tests passed** (44 core, 80 Android), including 30
Activity journey cases across API 34 and 35. Debug APK assembly and Android
lint passed with zero errors. Lint retains advisory style/dependency/platform
warnings; it is not warning-free.

## Verified behavior

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
compile SDK 36, target 35. All new project files live under `android/`; original
tracked TypeScript/Python/Rust files remain unchanged. Toolchains and caches stay
outside version control.

## Reproducible references

After installing original frozen Bun dependencies, run from the repository root:

```sh
bun android/scripts/generate-parity-fixtures.ts
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
`/workspace/android-tools`. Native layouts use the original font, Tabler paths,
logo and theme tokens. Every route, complete pixel equality, hardware rendering
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

The tested debug APK is 29,373,220 bytes. `apksigner verify` and
`zipalign -c -P 16 4` passed; every native ELF load segment has at least 16 KB
alignment. SHA-256:
`06b058756286269d809f0a40980221445e1a08133a1149a5484fd096e451fd8d`.
This is a debug APK, not a store release. Recheck after any further build changes.
