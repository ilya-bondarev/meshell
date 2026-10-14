# Architecture

## Source layout

| Path | Concern |
| --- | --- |
| `src/services/` | Platform-independent rules; plain inputs and outputs |
| `src/com/mesystem/shell/chat/` | Conversation coordination, context and retry policy |
| `src/com/mesystem/shell/persistence/` | SQLite, migrations, backups and encrypted credentials |
| `src/com/mesystem/shell/environment/` | Shell, files, device and USB adapters |
| `src/com/mesystem/shell/modules/` | Built-in module implementations and action dispatch |
| `src/com/mesystem/shell/transport/` | HTTP, SSE and the ChatGPT/Codex protocol |
| `src/com/mesystem/shell/presentation/` | Views, drawing, formatting and locale resources |
| `src/com/mesystem/shell/*.java` | Android entry points and lifecycle wiring |
| `native/` | Authenticated broker, PTY and independent command workers |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | English and Russian UI text |
| `tools/`, `build.sh`, `device/` | Build tooling and phone startup/update scripts |

The Java directory groups retain one package. This keeps existing package-private contracts without making every adapter public merely to move files. Directory grouping is not a claim that all Android coordination code is platform-independent. Only `src/services/` has that enforced boundary.

## Owners

- Quota arithmetic: `services.quota.QuotaRules`, [rules](QUOTA_RULES.md).
- Chat admission and identity: `ChatEngine`; retry and continuation: `RetryPolicy`; context construction: `ContextBudget` and `CompactContext`, [rules](CHAT_RULES.md).
- Module definitions and dispatch: `ModuleHub`; storage: `ModuleStore`, [rules](MODULE_RULES.md).
- Device measurements: `TelemetryModule`, [rules](DEVICE_RULES.md).
- File mutations, secret masking and updater: [environment rules](ENVIRONMENT_RULES.md).
- Model catalogue and the reported Codex client version: `OpenAiClient`. Settings only triggers the update.

Views call owner contracts instead of reading another module's database. Store migrations remain with their store. Presentation can format or lay out values; quota thresholds and measurement conversions are not view rules.

## Gates

`tools/RepositoryCheck.java` checks resource parity, placeholders, unused strings and source-class references, known secret signatures, forbidden artifacts, platform imports in `services/`, SQL outside persistence and regressions of the extracted rendering rules. These are targeted checks, not a proof that every method is live or every possible secret is detected.

`tests/handshake_test.c` and `tests/file_write_test.sh` guard the broker handshake and the file-write lock. Builds run the current-source suite before signing. GitHub's Verify job adds history scanning and Android integration execution. Do not replace these gates with prose-only promises.
