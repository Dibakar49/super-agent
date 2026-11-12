# Android features and research

Audited 2026-11-12. This app is native Kotlin/Compose with a local phone backend.
LLM and MCP requests use the endpoints the person configures; cloud LLM use is not
on-device inference. MediaPipe text inference is optional and needs a compatible model.
Computer control is deliberately excluded.

## Existing native capabilities

Bots/conversations, Markdown and code copy, scoped memory, skills, local goals and
scheduled routines, granted MCP tools, questions/approvals with durable continuation,
text/image attachments, notifications, API-compatible models and optional local
inference, provider-based voice dictation. See [STATUS.md](STATUS.md) for limitations.

## Added in the upstream sync

| Feature | Implementation / behavior | Inspiration / source |
|---|---|---|
| Streaming model responses | Responses and Chat Completions SSE, bounded event/answer sizes, complete tool-call assembly, partial text persistence, cancellation and JSON-provider fallback | [OpenBot stream recovery #1473](https://github.com/CopilotKit/openbot/pull/1473), [LibreChat resumable-stream UX](https://github.com/danny-avila/LibreChat#readme); original Android code, no Redis/server resumption claim |
| On-device dictation fallback | Microphone on every composer; Android 12+ on-device recognition when no provider is configured; no cloud fallback; unsupported-device/language guidance; lifecycle cancellation | [OpenBot #1432](https://github.com/CopilotKit/openbot/pull/1432) |
| Responsive native navigation and theme sync | Persistent tablet/wide-landscape sidebar, scrollable short navigation, bounded content width, updated upstream Inter font/background, readable working pulse | [upstream theme](https://github.com/CopilotKit/openbot/blob/68fe32774a83689a862d5821a6260e72e5f8fdb9/app/src/theme.css); Android adaptation |
| Sidebar conversation rename | Chosen title persists separately; clear restores generated title; search includes chosen title; native rename dialog | [OpenBot #1393](https://github.com/CopilotKit/openbot/pull/1393) |
| Hourly schedule descriptions | Every hour / Every hour at NN past, typed unknown-timezone refusal | [upstream schedule.ts](https://github.com/CopilotKit/openbot/blob/68fe32774a83689a862d5821a6260e72e5f8fdb9/server/src/routines/schedule.ts) |
| Consistent unrun outcomes | A skipped/withdrawn tool step tells the model to retry if needed or disclose the missing result; legacy transcripts retain readable explanations | [upstream outcomes](https://github.com/CopilotKit/openbot/blob/68fe32774a83689a862d5821a6260e72e5f8fdb9/shared/approval-outcomes.ts) |

Upstream source is MIT under the root LICENSE. Existing bundled icons/fonts/avatars
retain their notices in `android/licenses/`. No proprietary Grok code, artwork,
credentials or extracted assets are used.

## Open-source comparison and selection

Sources were read directly from their public GitHub READMEs and license files;
GitHub repository search was used for discovery. These are product inspirations,
not dependencies or code copied into the app.

| Project | License checked | Useful documented capabilities | Android comparison |
|---|---|---|---|
| [LibreChat](https://github.com/danny-avila/LibreChat) | [MIT](https://github.com/danny-avila/LibreChat/blob/main/LICENSE) | Multi-provider agents, MCP, subagents, skills, attachments, speech, resumable streams | Local MCP/memory/skills/files already exist. Streaming is now implemented; real subagents remain a valuable missing item; server/Redis resumption cannot be claimed on phone. |
| [ChatterUI](https://github.com/Vali-98/ChatterUI) | [AGPL-3.0](https://github.com/Vali-98/ChatterUI/blob/master/LICENSE) | On-device GGUF through llama.cpp, remote APIs, multiple chats, device TTS | This port uses MediaPipe rather than llama.cpp. Device TTS and richer local model compatibility are candidates; no ChatterUI code/assets were reused. |
| [OpenBot](https://github.com/CopilotKit/openbot) | [MIT](https://github.com/CopilotKit/openbot/blob/main/LICENSE) | Agent tools, memory, routines, goals, approvals, dictation, providers, computer use | Primary reference; full delta and gaps are in MIGRATION.md. Computer use excluded. |

These open-source assistants provide comparable chat/agent capabilities; no claim
is made that any of them reproduces Grok's proprietary implementation. Existing
features are retained rather than duplicated from another project.

## REA research and use

[REA](https://github.com/morluto/rea) is an MIT analysis toolkit with a CLI/MCP
interface and evidence-oriented static JavaScript/Electron reconstruction. Native
analysis adapters are optional; they were not needed here. The pinned npm package
`rea-agents@6.8.0` was installed outside this repository with install scripts disabled.
Its static analyzer examined **only our own OSS sidebar source**:

```sh
rea-agents analyze-javascript-application ./app \
  --source-path src/components/app-sidebar/app-sidebar.tsx \
  --detail summary --evidence_output /tmp/rea-sidebar-evidence --format json
```

[Recorded summary](docs/upstream/rea-static-summary.json). This selected-file run
reported 1 examined source and 2,775 unexamined sources, with partial semantic
coverage and unknowns. It does not certify the application, validate Kotlin, or
prove runtime behavior. No proprietary application was analyzed, no protection
bypassed, and no REA-generated output was treated as project instructions.

Streaming wire contracts were checked against the official [Responses text delta type](https://github.com/openai/openai-python/blob/main/src/openai/types/responses/response_text_delta_event.py) and [Chat Completions chunk type](https://github.com/openai/openai-python/blob/main/src/openai/types/chat/chat_completion_chunk.py). No OpenAI SDK source was copied. The application does not expose hidden reasoning; encrypted reasoning items are retained only in structured provider history.

The bundled Inter Variable font comes from `@fontsource-variable/inter@5.3.0`, matching upstream. Its WOFF2 container was losslessly converted to TTF for Android; the SIL OFL notice is retained in `android/licenses/Inter-OFL.txt`.
