# OpenBot native Android

Native Kotlin/Jetpack Compose work in progress. The requested target is a local
Android application, including its backend and agents, with computer use excluded.
Full OpenBot feature and visual parity is still in progress.

The native UI now follows the original web Home, sidebar, Bot roster/profile,
Capabilities/Skills/Memory, transcript and General settings. It uses the original
Atkinson Hyperlegible Next font, Tabler icon paths, logo and light/dark CSS color
tokens. Phone layouts have been rendered alongside browser captures of the real
web app; this does not establish complete visual parity.

The local backend includes app-private SQLite, Bot and conversation management,
private `/skill` execution guidance, personal/observed memory with Bot scope and
forget tombstones, provider tool-call rounds, transactional tool-result records,
and routines created/changed through conversations. Routines have on/off controls,
Run now, persistent history, a 20-enabled cap, a 15-minute minimum interval and a
ten-failure pause rule. WorkManager executes scheduled and manual runs on the phone.
Android idle/battery/network conditions may defer background work; it is not an
exact alarm service. Provider calls go directly from the phone over HTTPS. There is
no remote OpenBot server dependency and no WebView.

## Open and build

Open this directory in Android Studio with a JDK 21 toolchain and Android SDK 36.
Install Android SDK Build Tools 35.0.0. The app targets Android 35, supports Android
8.0/API 26 and newer, and packages ARM64 native inference libraries. Actual model
compatibility and RAM requirements depend on the selected model and phone.

Create your own untracked `local.properties` with `sdk.dir` if Android Studio does
not do so. Do not commit machine paths or signing keys.

```sh
cd android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

The wrapper uses Gradle 8.13 and pins its official distribution SHA-256. Build
output is `app/build/outputs/apk/debug/app-debug.apk`. The debug signing key is for
development; this is not a Play Store release.

In the prepared cloud workspace, the verified JDK, SDK, Gradle cache and proxy/CA
configuration are outside the checkout under `/workspace/android-tools`. Run:

```sh
python3 /workspace/android-tools/build.py
```

The cloud helper uses the inherited proxy and a local Java trust store; TLS and
artifact-checksum verification remain enabled. These machine-specific paths are
not required on a normal Android Studio machine.

## Run locally

Install the debug APK on an ARM64 Android phone. The application starts without an
account or server. Add a Bot or use the initial OpenBot, save memory, and open a
conversation. All of that data remains in the application's private SQLite store.
Backup is disabled; uninstalling the app removes its data and imported model.

For provider API replies, open **Model settings**, select **Model provider API**,
and save an HTTPS API base URL, model name, API style and provider key. The default
base URL is OpenAI's `/v1` API. Keys are encrypted at rest with an Android Keystore
AES-GCM key; they never enter the UI's public state or request logs. Backups are
disabled. Changing the API URL clears the saved key unless a new key is supplied.
Requests do not forward credentials across redirects. Provider mode sends the
conversation prompt and enabled memory to the chosen provider. Live provider
access and Keystore behavior on physical hardware remain unverified.

For offline AI replies, use **Model settings → On-device model → Import model** and select an actual
MediaPipe-compatible LLM `.task` file you have permission to use. A `.gguf` file
cannot be substituted. No model is included and no model has been tested on a
physical phone here. Import validates native loading before replacing an existing
model; a SHA-256 digest identifies the imported file, but is not a publisher
signature. Native loading/inference can exceed the RAM available on some devices.
An absent model produces an explicit error and keeps the user's unsent draft.

Provider mode runs local routine, goal and memory tools and records their results in the
transcript. Stop cancels foreground turns; HTTP connections are disconnected on
cancellation. On-device mode currently generates text replies and does not yet
execute tools. Prompts retain complete recent messages within a declared context
budget; memory context quoting follows the original TypeScript helper.

**Connections** supports custom MCP Streamable HTTP endpoints, tool discovery,
encrypted bearer credentials and per-Bot/per-tool grants. Granted tools execute
locally through the provider tool loop. The original personal approval policy,
mandatory safety stops, global and per-Bot rules, optional model auto-review,
Allow once, Always allow here, Deny and I did it myself are implemented. Waiting
decisions and protocol continuations survive process restart. Started calls with
unknown results require an explicit result check and are never blindly repeated.
OAuth/catalogue account setup remains pending.

**Goals** supports creation, editing, pause/resume, completion, run history and
queued WorkManager execution. Goal tools bind progress to the actual run and
preserve recurring goals linked to routines. Event subscriptions are not offered
until real local adapters exist.

**Questions and approvals** also supports `ask_person`: durable free-text or
multipart questions with optional choices, answered in the conversation or inbox.
Answers resume the waiting turn once. Later steps from a paused tool batch are
reported as not run, so the model replans using the person's decision.

The composer accepts up to eight PNG/JPEG/GIF/WebP images or UTF-8 plain text,
Markdown, CSV and JSON files. Images are capped at 8 MiB, text files at 1 MiB,
and one model turn at 32 MiB of file content. Text is extracted with the original
120,000-character boundary and truncation note; large text tiles say “may be cut”.
Files are copied into app-private storage and bind atomically to the message that
sends them. Image bytes stream as base64 to the chosen provider, while SQLite and
waiting-turn protocol history retain references. The native document picker,
previews, Remove controls, attachment-only sends and resumed provider turns are
implemented. On-device mode accepts text files within its smaller prompt budget;
it does not accept images. Clipboard/drag image import remains pending.

**Settings → Notifications → Bot updates** enables Android notifications for
waiting questions/approvals and completed background replies. Android 13+ asks for
notification permission when you enable it. Notifications link to the matching
local inbox/conversation, hide content on the lock screen and clear resolved or
deleted items. Dismissing a waiting notification does not repeatedly alert you.
This is local Android delivery; it does not register an Expo push token.

**Model settings → Dictation settings** enables voice input with an OpenAI-compatible
transcription URL, model and optional key. Voice credentials are encrypted separately
from chat-model credentials. The microphone button asks for permission when used,
records private mono AAC/MP4 audio for at most two minutes/10 MiB, and offers
Cancel, Stop and transcribe, Transcribe and send, and Retry after a transient
transcription error. Cancelling or leaving the screen releases the microphone,
aborts an in-flight upload and deletes audio; generation fences discard late results.
Successful transcription appends to the current draft and removes its recording.
Native microphone capture and live transcription still need physical-device/service
validation. This implements dictation; live two-way voice calls remain pending.

## Structure

- `core/src/main/kotlin/ai/openbot/core`: original API contracts, task sorting,
  notification-link validation, routine framing, subagent result/identity helpers,
  approval outcome text, and the new local prompt builder.
- `app/src/main/kotlin/ai/openbot/android/data`: transactional SQLite persistence.
- `app/src/main/kotlin/ai/openbot/android/inference`: provider API calls and local model import/inference.
- `app/src/main/kotlin/ai/openbot/android/ui`: native phone screens.
- `app/src/main/kotlin/ai/openbot/android/OpenBotViewModel.kt`: lifecycle and
  asynchronous actions; only one write/generation at a time.
- `core/src/test`: contract tests and fixtures generated from unchanged TypeScript.
- `app/src/test`: actual Android SQLite tests and a Compose Activity journey using
  Robolectric. These are not a physical-device inference test.

See [MIGRATION.md](MIGRATION.md) for the source inventory and explicit pending work.
Needs you/tasks, OAuth/catalogue accounts, event subscriptions, live two-way voice calls,
clipboard/drag image import, learning, additional agent frameworks and full visual parity remain
incomplete. Routines still need physical-device/background timing validation. The original
TypeScript, Python and Rust implementations have not been modified.

## Reference fixtures

After installing the original repository's frozen Bun dependencies, regenerate
selected fixtures directly from the original TypeScript with:

```sh
bun android/scripts/generate-parity-fixtures.ts
```

Run this from the repository root. Temporary reference modules are removed after
the run. The fixtures cover 101 routine cases, scoped memory context, goal/routine
links, 69 connected-action safety cases, 960 approval decisions and wildcard rules.
Multipart question schemas and answer strings also come from the original source.
They validate the named behavior, rather than establishing whole-app parity.
