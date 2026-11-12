# Agent Studio: one Kotlin feature file

Deliver **agentstudio.kt** to the existing app. The other files in this directory
are a development-only verification host, not files the host app needs to copy.

```kotlin
import ai.openbot.agentstudio.AgentStudioScreen

// Inside your existing Compose navigation destination:
AgentStudioScreen(onBack = { navController.popBackStack() })
```

The screen starts with an empty agent gallery and a Make an agent button. The +
button opens name / original procedural mascot shape / color selection, followed
by agent instructions. Chats have a back button, mascot/name and options, with
no sidebar. Options retain history/rename/pin/delete, memory, skills, routines,
goals, connections, approvals, model/voice configuration and agent controls.

Each agent has a separate database, files, encrypted preference namespace,
execution gate and background job tag. Clearing one agent does not cancel the
host's unrelated jobs or clear another agent's records. Notification taps and
read-only attachment URIs carry the owning agent's identity.

Fonts, icons, image assets and tool schemas are embedded. All 110 original Kotlin
source files, their resources and notices are also retained byte-for-byte in a
recoverable archive in the same .kt file. Runtime logic from 79 original Kotlin
files is compiled in separate scopes to avoid collisions between file-private names.
The new gallery and integration adapters are original Kotlin code; none of the
reference screenshots' mascot artwork was extracted or reused.

A single Kotlin source cannot add manifest permissions or Gradle dependencies.
The file header gives the required dependencies/plugins and minimal manifest
entries. Use the host's Application and launcher, and retain its WorkManager
initialization. No separate APK or Gradle project needs to be published.

This preserves the currently implemented port. It does not fill the earlier
missing OpenBot features (actual subagents, OAuth/catalogues, full upstream
parity, Hilt/Room/DataStore migration, or on-device tool inference).

## Verification

`verify.py` creates a disposable host from the existing Gradle configuration,
with one feature source, a minimal existing-app fixture and **no feature resources**.
It translates original non-UI regression imports and checks the new gallery,
per-agent isolation, host-data/job preservation and attachment provider.

```sh
python3 android-singlefile/verify.py /tmp/agentstudio-validation
cd /tmp/agentstudio-validation
./gradlew :app:testDebugUnitTest assembleDebug
```

The exact-source recovery test checks all archived source/resource hashes and
embedded runtime schemas. Tests use scripted providers and Android shadows;
physical-device and live model/MCP behavior remain unverified.
