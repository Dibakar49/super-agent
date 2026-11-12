# Native Android migration

Source baseline: OpenBot `0649b1ab` (0.3.4). Comparison target: main
`68fe32774a83689a862d5821a6260e72e5f8fdb9`, audited 2026-11-12.
**This is an incremental port, not complete upstream parity.** The original TypeScript
checkout stays at the baseline; updating these reference sources is not an Android implementation.

The [128-commit ledger](docs/upstream/changes-2026-11-12.md) lists all changes in the
range. [changed-files.csv](docs/upstream/changed-files.csv) lists every changed path.
The [earlier inventory](android/MIGRATION.md) covers all 3,453 original source paths.
`done` below applies to the named behavior, not every feature in an upstream directory.

| Upstream TS module / behavior | Android Kotlin file(s), relative to `android/` | Status |
|---|---|---|
| `app/src/components/app-sidebar/channel.tsx`, chosen title / rename dialog | `app/.../ui/AppSidebar.kt`, `app/.../data/LocalStore.kt`, `core/.../ConversationRoster.kt` | done: rename, clear, persistence; local owner only |
| `server/src/routines/schedule.ts` | `core/.../Schedule.kt` | done: hourly labels, timezone error code; retained DST/floor fixtures |
| `shared/approval-outcomes.ts` | `core/.../ApprovalOutcomes.kt` | done: new non-computer `notRun` contract; readable legacy saved outcomes |
| `shared/standing-rule.ts`, approval service/policy | `core/.../ApprovalPolicy.kt`, `app/.../data/ApprovalRuleStore.kt`, `app/.../inference/ActionPolicy.kt` | partial: baseline policy only; conversation answers / open default / admin closed rules todo |
| `server/src/goals/*`, `server/src/routines/*`, unified routines editor | `core/.../Goals.kt`, `app/.../data/GoalStore.kt`, `app/.../ui/{Goals,Routines}Screen.kt` | partial: local goals/runs/schedules; latest ends_when_met / goal_linked_by_run / editor todo |
| `server/src/agents/*`, tool result persistence / stream recovery | `app/.../inference/{AgentRuntime,ProviderTurnEngine}.kt`, `app/.../data/LocalStore.kt` | partial: durable local continuations; latest run-budget/prompt semantics need review |
| Provider response streaming / transcript working state | `core/.../ProviderStream.kt`, `app/.../inference/ProviderModel.kt`, `app/.../ui/ConversationScreen.kt` | done: local SSE and partial UI; provider-side resumption todo |
| `server/src/agents/subagent.ts` | `core/.../Subagent.kt` | partial: identifiers/results only; real child execution todo |
| Dictation / speech hooks in `app/src` | `core/.../Dictation.kt`, `app/.../inference/{AndroidAudioRecording,TranscriptionProvider}.kt`, `app/.../ui/DictationControls.kt` | partial: provider dictation; latest on-device fallback todo |
| Attachments and first-message attachment flow | `core/.../Attachments.kt`, `app/.../data/AttachmentStore.kt`, `app/.../inference/{TurnAttachments,AttachmentRequestBody}.kt` | partial: picker, text/images done; clipboard/desktop drop excluded or todo native equivalent |
| `app/src/components/*`, theme CSS | `app/.../ui/*`, `app/.../MainActivity.kt` | partial: native baseline screens; latest Inter/theme/navigation/cards not fully ported |
| Providers, Claude/Grok sign-in and discovery | `core/.../ModelProvider.kt`, `app/.../data/ModelSettingsStore.kt` | partial: API-compatible models / MediaPipe; OAuth/provider catalogue todo |
| Memory / skills | `core/.../{MemoryTools,SkillTools}.kt`, `app/.../ui/{Memory,Skills}Screen.kt` | partial: local memory/skills; Learning backend workflows todo |
| Team, audit, admin, boundaries/backtesting | none | todo: local architecture equivalent required |
| Desktop Docker/Podman/WSL/Helm installer/update machinery | none | not applicable to a phone-local backend; do not ship desktop runtime |
| Computer use, browser control and computer panel | none | excluded by user request |

Here `app/...` expands to `app/src/main/kotlin/ai/openbot/android` and `core/...`
to `core/src/main/kotlin/ai/openbot/core`. Schema additions use defaulted JSON fields
so existing SQLite payloads decode without destroying data. API keys are excluded
from checkpoint serialization and kept in Android Keystore encrypted preferences.

## Reproduce the delta checks

```sh
git fetch --no-tags https://github.com/CopilotKit/openbot.git main:refs/remotes/upstream/main
bun android/scripts/upstream-delta-fixtures.ts
cd android
./gradlew :core:test :app:testDebugUnitTest assembleDebug lint
```

The upstream oracle script is pinned to the full target SHA and retains the baseline
fixtures. A new SHA requires an explicit re-audit; do not silently regenerate expected
values from a changed implementation.
