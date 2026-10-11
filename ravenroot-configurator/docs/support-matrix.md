# Shipped-node support matrix

`src/inventory.ts` is the executable inventory. `pnpm check:coverage` compares it with the current public descriptor TSVs and the 21-bundle index, so omissions and stale rows fail the build.

Every optional package can additionally select `bundle.service-grant`, keyed by its exact manifest ID. This emits the strict `RAVENROOT_NODE_PACKAGE_SERVICES_<PACKAGE_UTF8_HEX>` managed-service authority required for declared HTTP, WebSocket, credential, tool, approval, and Agent capabilities.

## Operator-configured nodes

| Family | Shipped node IDs | Configurator contract |
|---|---|---|
| AI | `agent`, `llm-prompt` | `ai.llm-profile`, `ai.mcp-profile`, `core.runner` |
| AMQP 0-9-1 | `amqp.consume`, `amqp.publish` | `amqp.profile`, `amqp.consumer` |
| Publication guard | `boundary-guard` | `core.publication-policies` |
| Discord | `discord.interactions`, `discord.send` | `discord.config` |
| Filesystem | `filesystem.read`, `filesystem.write` | `filesystem.profile` |
| Git workspace | `git-workspace` | `git-workspace.profile` |
| GitHub | `github-app-review`, `github-events-source`, `github-workflow-watch`, `project-transition`, `release-prepare` | `github.config` |
| Built-in HTTP | `http-request` | `core.http` |
| Human Task | `human-task` | `core.human-task` |
| JDBC | `jdbc.insert`, `jdbc.query` | `jdbc.profile` |
| Kafka | `kafka.consume`, `kafka.produce` | `kafka.consumer-profile`, `kafka.producer-profile` |
| Mail | `mail.imap.consume`, `mail.imap.delete`, `mail.imap.move`, `mail.imap.query`, `mail.send` | `mail.imap-consumer`, `mail.imap-mutation`, `mail.imap-profile`, `mail.smtp-profile` |
| Matrix | `matrix.send`, `matrix.sync` | `matrix.config` |
| Mattermost | `mattermost.outgoing-webhook`, `mattermost.send` | `mattermost.config` |
| Object storage | `object.delete`, `object.get`, `object.list`, `object.put` | `object-storage.profile` |
| OCR | `ocr.extract` | `ocr.profile` |
| OpenAPI | `openapi.call`, `openapi.receive`, `openapi.request-reply` | `openapi-client.profile`, `openapi-server.config` |
| Program | `program` | `core.program` |
| Slack | `slack.commands`, `slack.events`, `slack.post-message` | `slack.config` |
| System One | `system-one-evaluate` | `system-one.profile` |
| Microsoft Teams | `teams.outgoing-webhook`, `teams.send` | `teams.config` |
| Telegram | `telegram.answer.callback`, `telegram.delete.message`, `telegram.edit.message`, `telegram.send` | `telegram.profile` |
| WebSocket | `websocket.receive`, `websocket.send` | `websocket.profile` |
| Governed catalog | `agent`, `workspace` | `core.runner` |

## Author-owned nodes

These shipped behaviors use graph properties and platform defaults without their own operator profile: `bigint-op`, `cel-decision`, `cel-transform`, `delay`, `json-parse`, `json-path`, `log`, `spel.decision`, `spel.transform`, `template`.

The source inventory records each one separately, including the Restricted SpEL bundle classification. A behavior cannot be moved into this group without an explicit coverage row and a passing exact inventory comparison.

## System One profiles

The guided `system-one.profile` contract emits canonical padded Base64 JSON under
`RAVENROOT_SYSTEM_ONE_PROFILE_<PROFILE_UTF8_HEX>`. Endpoint and pinned model are required;
other fields may be omitted (or null) to retain the Java resolver defaults. The sample displays
those limits explicitly: 60,000 ms, 1 MiB request and response, concurrency 4, 256 KiB state,
32 questions, 8 KiB instructions, 255 Choice options, 10 Score levels and all three primitive types.
State and instruction limits cannot exceed the effective request limit, including omitted defaults.
Credentials are optional paired selectors, not secret values. The guided profile uses HTTPS for
credentials; trusted-network plaintext exceptions remain a separate operator policy.

`probabilityTolerance` is a finite decimal from 0 through 0.05. Its runtime default remains 0.001.
Use 0.02 only for the [pinned Kev serialization contract](../../docs/reference/bundles/system-one.md);
Rizzo Flow profiles use at most 26 Choice options. The Configurator does not select a provider,
install models, activate a bundle, or widen managed HTTP grants automatically.
