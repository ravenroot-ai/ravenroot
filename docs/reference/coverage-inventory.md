# Operational documentation coverage inventory

This inventory was reviewed against the `dev` development snapshot at commit
`f58cd7c7d98cd370c89199829d5436c6a7e8eb8b`. It describes contracts present in that snapshot. The
released `0.1.0-alpha.1` notes remain the authority for that release; a development entry below must
not be read as a claim that an older release contains it.

The default server composition registers the 11 core node types below. Optional first-party source
contains 20 buildable bundle modules and 48 node descriptors. The snapshot's CI publication
selection is empty, so its official image assembly path stages no optional bundles. That is evidence
about this snapshot's configured path, not a retrospective inventory of already-published artifacts.
An operator can build optional bundles explicitly and include them in an operator-built image.

## Core catalog

`StandardBehaviorFactories.all()` is the maintained composition source; descriptor constructors are
the field source. The [descriptor contract](node-contracts.md) supplies every catalog field and a
complete parser-checked graph per node; the [core node reference](core-nodes.md) supplies payloads,
behavior-only limits, outcomes, effects, and execution prerequisites. `GET /v1/node-types` is the evidence for
the effective running deployment.

| Node | Contract and procedure | Validation evidence |
|---|---|---|
| `log` | [Core nodes](core-nodes.md#log) | Descriptor and core runtime tests |
| `delay` | [Core nodes](core-nodes.md#delay) | Descriptor, scheduler, cancellation, and deadline tests |
| `human-task` | [Durable human tasks](human-tasks.md) | Descriptor, store, authorization, re-entry, and timer tests |
| `template` | [Core nodes](core-nodes.md#template) | Descriptor and template behavior tests |
| `json-parse` | [Core nodes](core-nodes.md#json-parse) | Descriptor, canonical payload, and budget tests |
| `cel-transform` | [Core nodes](core-nodes.md#cel-transform) | Descriptor and closed CEL evaluator tests |
| `cel-decision` | [Core nodes](core-nodes.md#cel-decision) | Descriptor, Boolean result, and routing tests |
| `json-path` | [Core nodes](core-nodes.md#json-path) | Descriptor and RFC 9535 conformance/budget tests |
| `http-request` | [Core nodes](core-nodes.md#http-request) | Descriptor, credential, egress, cancellation, and response-bound tests |
| `program` | [Core nodes](core-nodes.md#program) | Descriptor, artifact lifecycle, sandbox, approval, and limit tests |
| `boundary-guard` | [Core nodes](core-nodes.md#boundary-guard) | Descriptor, policy evaluation, audit, and refusal tests |

The earlier broad catalog page also named `llm-prompt` and `agent` among shipped behavior families.
They are optional AI-bundle nodes in this baseline and are now inventoried below.

## First-party optional bundles

The extension-pack POM and every production `NodePackage` define membership. Module READMEs are the
canonical authoring source; generated site pages are checked byte-for-byte against those sources and
must name every package and node in the publication manifest. The
[bundle node examples](bundle-node-examples.md) link a complete admission-ready GraphML file and
expected execution result for every descriptor without duplicating the field contracts. The
[descriptor contract](node-contracts.md) publishes every property type, requirement, default,
allowed value, condition, descriptor limit, and declared outcome from compiled code.

| Build ID | Manifest package ID | Node types | Published contract |
|---|---|---|---|
| `ai` | `ai.ravenroot.extensions.ai` | `llm-prompt`, `agent` | [AI](bundles/ai.md) |
| `amqp091` | `ai.ravenroot.extensions.amqp091` | `amqp.publish`, `amqp.consume` | [AMQP 0-9-1](bundles/amqp091.md) |
| `discord` | `ai.ravenroot.extensions.discord` | `discord.interactions`, `discord.send` | [Discord](bundles/discord.md) |
| `filesystem` | `ai.ravenroot.extensions.filesystem` | `filesystem.read`, `filesystem.write` | [Filesystem](bundles/filesystem.md) |
| `git-workspace` | `ai.ravenroot.extensions.gitworkspace` | `git-workspace` | [Git workspace](bundles/git-workspace.md) |
| `github` | `ai.ravenroot.extensions.github` | `github-events-source`, `project-transition`, `github-app-review`, `github-workflow-watch`, `release-prepare` | [GitHub automation](bundles/github.md) |
| `jdbc` | `ai.ravenroot.extensions.jdbc` | `jdbc.query`, `jdbc.insert` | [JDBC](bundles/jdbc.md) |
| `kafka` | `ai.ravenroot.extensions.kafka` | `kafka.produce`, `kafka.consume` | [Kafka](bundles/kafka.md) |
| `mail` | `ai.ravenroot.extensions.mail` | `mail.send`, `mail.imap.query`, `mail.imap.consume`, `mail.imap.move`, `mail.imap.delete` | [Mail](bundles/mail.md) |
| `matrix` | `ai.ravenroot.extensions.matrix` | `matrix.send`, `matrix.sync` | [Matrix](bundles/matrix.md) |
| `mattermost` | `ai.ravenroot.extensions.mattermost` | `mattermost.send`, `mattermost.outgoing-webhook` | [Mattermost](bundles/mattermost.md) |
| `object-storage` | `ai.ravenroot.extensions.storage` | `object.get`, `object.put`, `object.list`, `object.delete` | [Object storage](bundles/object-storage.md) |
| `ocr` | `ai.ravenroot.extensions.ocr` | `ocr.extract` | [OCR](bundles/ocr.md) |
| `openapi-client` | `ai.ravenroot.extensions.openapi.client` | `openapi.call` | [OpenAPI client](bundles/openapi-client.md) |
| `openapi-server` | `ai.ravenroot.extensions.openapi.server` | `openapi.receive`, `openapi.request-reply` | [OpenAPI server](bundles/openapi-server.md) |
| `slack` | `ai.ravenroot.extensions.slack` | `slack.events`, `slack.commands`, `slack.post-message` | [Slack](bundles/slack.md) |
| `spel` | `ai.ravenroot.extensions.spel` | `spel.transform`, `spel.decision` | [Restricted SpEL](bundles/spel.md) |
| `teams` | `ai.ravenroot.extensions.teams` | `teams.send`, `teams.outgoing-webhook` | [Microsoft Teams](bundles/teams.md) |
| `telegram` | `ai.ravenroot.extensions.telegram` | `telegram.send`, `telegram.answer.callback`, `telegram.edit.message`, `telegram.delete.message` | [Telegram](bundles/telegram.md) |
| `websocket` | `ai.ravenroot.extensions.websocket` | `websocket.send`, `websocket.receive` | [WebSocket](bundles/websocket.md) |

All 20 are source-supported packages, optional at runtime, and absent from the default core catalog.
The deployable-bundle path requires explicit installation plus manifest-ID activation; an embedding
application can instead include extension jars and explicitly name their package classes through the
separate classpath mechanism. The [bundle lifecycle](../operator-guide/plugin-bundles.md) is the
procedure and `publish_bundle_reference.py --check`, `publish_node_contract_reference.py --check`,
the compiled `PublishedNodeContractTest`, extension-pack tests, bundle validator
tests, plugin script tests, and running `node-types` output are the validation chain. AI is excluded
from every batch; JDBC joins a batch only with verified driver/digest pairs.

## Scripts and application CLI

| Surface | Maintained contract | Manual | Validation |
|---|---|---|---|
| `plugin.sh` | Script `usage()` and shared Java bundle validator | [Command-line tools](command-line-tools.md#pluginsh), [bundle lifecycle](../operator-guide/plugin-bundles.md) | `test_plugin.sh`; operational-doc drift check |
| `service.sh` | Script `usage()`, Compose, Dockerfile, Helm chart | [Command-line tools](command-line-tools.md#servicesh), [deployment](../operator-guide/deployment-startup.md) | `test_service.sh`; Compose/OCI/Helm CI; drift check |
| `dev.sh` | Script `usage()` and runtime selectors | [Command-line tools](command-line-tools.md#devsh), [build guide](../developer-guide/build-test.md) | `test_dev.sh`; setup/check CI; drift check |
| distribution server helper | `ravenroot/scripts/server.sh` `usage()` and process/health implementation | [Command-line tools](command-line-tools.md#distribution-build-and-local-runner) | Help execution and operational-doc drift check |
| local foreground runner | `ravenroot/scripts/run-local.sh` fixed delegation | [Command-line tools](command-line-tools.md#distribution-build-and-local-runner) | Operational-doc inventory check |
| `ravenroot` CLI | `RavenrootCli`, `RavenrootCliMain`, command argument parsers | [Command-line tools](command-line-tools.md#application-cli), [HTTP/CLI reference](api-cli.md) | CLI unit/transport tests; drift check |
| release build | `ravenroot/scripts/build-release.sh` | [Command-line tools](command-line-tools.md#distribution-build-and-local-runner), [build and test](../developer-guide/build-test.md), [releasing](../governance/releasing.md) | Distribution/release CI; operational-doc inventory check |

The drift check extracts script help tokens, application CLI verbs/options, and assistant variables
from their maintained sources. Procedures still require tests because token presence alone does not
prove an operational effect.

## Operator configuration

| Group | Authoritative parser/composition | Reference and procedure | Change boundary |
|---|---|---|---|
| Engine, unknown behavior, graph limits | Core environment parsers | [Configuration](configuration.md) | Restart/recreate |
| Identity, origins, hosts, token/OIDC | Server security configuration | [Configuration](configuration.md), [identity](../operator-guide/identity-browser.md) | Restart/recreate |
| HTTP/connector egress and credentials | Server composition and package profile parsers | [Credentials and egress](../operator-guide/credentials-egress.md), bundle pages | Restart/recreate for environment/profile grants; credentials use governed API persistence |
| Plugin directory and allowlist | Dockerfile, Compose, `PluginBundleLoader` | [Bundle lifecycle](../operator-guide/plugin-bundles.md) | Rebuild image for bytes; restart/recreate for allowlist |
| Authoring assistant | `AssistantConfiguration`, `AssistantComposition`, consent store | [Authoring assistant](../operator-guide/authoring-assistant.md) | Restart/recreate; consent persists, OAuth token does not |
| Execution, inventory, audit, embed, backup | Server/store configuration parsers | [Configuration](configuration.md), [persistence](../operator-guide/persistence-lifecycle.md), [embed](embed-extension-contracts.md), [backup](backup-recovery.md) | Reference page states persistence and restart boundary |
| Bundle-specific profiles | Each extension's strict environment parser | [Bundle index](bundles/) | Restart/recreate; graph cannot widen profile authority |

Environment aliases and dynamic profile families are grouped by their parser rather than expanded
into invented concrete tenant/profile names. For example, the exact JDBC family is documented as
`RAVENROOT_JDBC_PROFILE_<TENANT_UTF8_HEX>_<PROFILE_UTF8_HEX>` with its complete JSON schema in the
JDBC page. This is an applicability decision, not an omission.

The generated [production environment-variable inventory](environment-variables.md) maps every
literal Java setting and dynamic-family prefix to these semantic pages. Its drift check refuses an
unclassified new name and prevents silent inventory omissions.

## Public application and transport surfaces

| Surface | Reference | Procedure and validation |
|---|---|---|
| Status, readiness, configuration, node catalog | [HTTP API and CLI](api-cli.md) | [Deployment/startup](../operator-guide/deployment-startup.md); server route and transport tests |
| Graph inspect, validation, execution, control, results | [HTTP API and CLI](api-cli.md), [GraphML](graphml.md), [execution events](execution-events.md) | [First execution](../get-started/first-execution.md); HTTP/CLI parity and execution tests |
| Durable process inventory and manifests | [HTTP API and CLI](api-cli.md), [process inventory](../architecture/process-inventory.md) | Persistence/restart and route tests |
| Credentials, model providers, program artifacts | [HTTP API and CLI](api-cli.md) | [Credentials/egress](../operator-guide/credentials-egress.md), [AI/program integration](../integrator-guide/ai-programs.md); ownership and lifecycle tests |
| Authoring assistant | [HTTP API and CLI](api-cli.md) | [Assistant configuration](../operator-guide/authoring-assistant.md); route/composition tests |
| Embedded viewer | [Embed contracts](embed-extension-contracts.md) | [Embed operations](../operator-guide/embed-operations.md); gate, session, and projection tests |
| SSE live/recent events | [Execution events](execution-events.md) | [Application integration](../integrator-guide/application-http.md); cursor and revalidation tests |

## Explicit exclusions

| Surface | Applicability decision |
|---|---|
| Test fixtures and `ravenroot-node-starter` example | Not supported first-party runtime packages. They test or teach extension construction and are not in the extension pack. |
| `scripts/bisect-*`, `scripts/measure-*`, `scripts/verify-*`, and `scripts/tests/*` | Developer and CI probes rather than supported operator command surfaces. Their invoking workflow or test is the maintained contract; the build guide and bundle references link the probes needed for a documented procedure. |
| Engine, persistence, observability, programming, and sandbox adapters | Host composition components rather than node bundles. Their applicable contracts live in architecture, operator, integrator, and developer pages. |
| `ravenroot-extensions-all` | Embedded Maven dependency pack only; not a bundle and no runtime activation by itself. An embedding application must include the resolved jars and explicitly select classpath package names. |
| Third-party bundles | Their publishers own node/profile documentation. Ravenroot documents the manifest, validation, service-grant, egress, and activation contract that admits or refuses them. |
| Marketing website | Outside this operational manual and repository documentation scope. |
| Scenario project tracked separately | Reuse published executable GraphML when available; this inventory owns ordinary procedures and does not duplicate a separate scenario repository. |
| Future configuration work | Names/defaults not present at the pinned snapshot are absent until their machine contract lands. Development docs do not pre-announce them. |
| Final site publication | A local render or merge to `dev` is not publication. The published version and `https://docs.ravenroot.ai/` URLs can be verified only after the normal owner-authorized documentation promotion. |

Run `python3 scripts/publish_bundle_reference.py --check`,
`python3 scripts/publish_node_contract_reference.py --check`,
`python3 scripts/check_operational_docs.py`, `python3 scripts/check_public_docs.py`, the documentation
site build/check, and the referenced shell tests when changing an inventoried surface.
