# First-party bundle reference

These pages publish the canonical extension-module READMEs inside the guide. Edit the module README,
then run `python3 scripts/publish_bundle_reference.py`; do not edit a generated page. The `--check`
mode compares every publication view with its source, discovers first-party `NodePackage` modules,
and verifies that the maintained package/node inventory remains represented.

Every package is optional. Build and installation do not enable it, and the default core catalog does
not include its nodes. Follow the [bundle lifecycle](../../operator-guide/plugin-bundles.md), then
require the exact node IDs in the running catalog.

The [first-party bundle node examples](../bundle-node-examples.md) provide a runnable GraphML fragment
and expected Run result for every node listed below.

| Bundle | Nodes |
|---|---|
| [AI](ai.md) | `llm-prompt`, `agent` |
| [AMQP 0-9-1](amqp091.md) | `amqp.publish`, `amqp.consume` |
| [Discord](discord.md) | `discord.interactions`, `discord.send` |
| [Filesystem](filesystem.md) | `filesystem.read`, `filesystem.write` |
| [Git workspace](git-workspace.md) | `git-workspace` |
| [GitHub automation](github.md) | `github-events-source`, `project-transition`, `github-app-review`, `github-workflow-watch`, `release-prepare` |
| [JDBC](jdbc.md) | `jdbc.query`, `jdbc.insert` |
| [Kafka](kafka.md) | `kafka.produce`, `kafka.consume` |
| [Mail](mail.md) | `mail.send`, `mail.imap.query`, `mail.imap.consume`, `mail.imap.move`, `mail.imap.delete` |
| [Matrix](matrix.md) | `matrix.send`, `matrix.sync` |
| [Mattermost](mattermost.md) | `mattermost.send`, `mattermost.outgoing-webhook` |
| [Object storage](object-storage.md) | `object.get`, `object.put`, `object.list`, `object.delete` |
| [OCR](ocr.md) | `ocr.extract` |
| [OpenAPI client](openapi-client.md) | `openapi.call` |
| [OpenAPI server](openapi-server.md) | `openapi.receive`, `openapi.request-reply` |
| [Slack](slack.md) | `slack.events`, `slack.commands`, `slack.post-message` |
| [Restricted SpEL](spel.md) | `spel.transform`, `spel.decision` |
| [Microsoft Teams](teams.md) | `teams.send`, `teams.outgoing-webhook` |
| [Telegram](telegram.md) | `telegram.send`, `telegram.answer.callback`, `telegram.edit.message`, `telegram.delete.message` |
| [WebSocket](websocket.md) | `websocket.send`, `websocket.receive` |

The [coverage inventory](../coverage-inventory.md) maps these contracts to their source and
validation evidence. Shared manifest, service-grant, egress, activation, and compatibility rules are
in [Embed and extension contracts](../embed-extension-contracts.md).
