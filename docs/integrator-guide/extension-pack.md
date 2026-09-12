# First-party extension dependency pack

Use `ravenroot-extensions-all` when an embedding application intentionally wants every production
first-party Ravenroot node package on its Maven classpath. The artifact is a dependency-bearing JAR:
one ordinary dependency resolves the complete reviewed set at one Ravenroot version.

## Add the dependency

```xml
<properties>
  <ravenroot.version>0.1.0-alpha.1</ravenroot.version>
</properties>

<dependencies>
  <dependency>
    <groupId>ai.ravenroot</groupId>
    <artifactId>ravenroot-extensions-all</artifactId>
    <version>${ravenroot.version}</version>
  </dependency>
</dependencies>
```

From the consumer project, inspect the resolved Ravenroot artifacts reproducibly with the same
declared version:

```sh
mvn -B --no-transfer-progress dependency:tree \
  -Dincludes=ai.ravenroot:* \
  -Dravenroot.version=0.1.0-alpha.1
```

The pack contains no replacement versions, ranges, snapshots, classifiers, or optional edges. Every
first-party extension dependency uses the pack's exact project version.

## What the pack includes

The current reviewed membership is:

| Artifact | Explicit `NodePackage` implementation |
|---|---|
| `ravenroot-ai` | `ai.ravenroot.extensions.ai.AiNodePackage` |
| `ravenroot-amqp091` | `ai.ravenroot.extensions.amqp091.AmqpNodePackage` |
| `ravenroot-discord` | `ai.ravenroot.extensions.discord.DiscordNodePackage` |
| `ravenroot-filesystem` | `ai.ravenroot.extensions.filesystem.FilesystemNodePackage` |
| `ravenroot-git-workspace` | `ai.ravenroot.extensions.gitworkspace.GitWorkspaceNodePackage` |
| `ravenroot-github` | `ai.ravenroot.extensions.github.GithubNodePackage` |
| `ravenroot-slack` | `ai.ravenroot.extensions.slack.SlackNodePackage` |
| `ravenroot-jdbc` | `ai.ravenroot.extensions.jdbc.JdbcNodePackage` |
| `ravenroot-kafka` | `ai.ravenroot.extensions.kafka.KafkaNodePackage` |
| `ravenroot-mail` | `ai.ravenroot.extensions.mail.MailNodePackage` |
| `ravenroot-object-storage` | `ai.ravenroot.extensions.storage.StorageNodePackage` |
| `ravenroot-ocr` | `ai.ravenroot.extensions.ocr.OcrNodePackage` |
| `ravenroot-openapi-client` | `ai.ravenroot.extensions.openapi.client.OpenApiClientNodePackage` |
| `ravenroot-openapi-server` | `ai.ravenroot.extensions.openapi.server.OpenApiServerNodePackage` |
| `ravenroot-spel` | `ai.ravenroot.extensions.spel.SpelNodePackage` |
| `ravenroot-telegram` | `ai.ravenroot.extensions.telegram.TelegramNodePackage` |
| `ravenroot-websocket` | `ai.ravenroot.extensions.websocket.WebSocketNodePackage` |

The source of truth is the production source tree beneath `ravenroot-extensions`: every direct Maven
module containing a main-source class that implements `NodePackage` belongs in the pack. Testkits,
examples, engine and persistence adapters, the server and distribution, and third-party plugin
bundles do not. When adding or removing a first-party production node-package module, update the
`ravenroot-extensions` reactor module list and the pack's explicit dependency list together. The
extension-pack contract check derives membership from source and fails on an omission, an extra
artifact, or a drifting version.

## Activate packages explicitly

Classpath presence grants no authority. An embedding application's composition root still chooses
each package and the operator services it receives. For example, an application that has already
created its registry and package-scoped service grants can activate mail explicitly:

```java
BehaviorRegistry registry = BehaviorRegistry.standard(environment);
NodePackages.register(
    registry,
    new MailNodePackage(),
    operatorServiceRegistry);
```

The server and CLI use the same trust decision through an operator-owned class allowlist. For
example, placing the pack on their classpath still does nothing until the deployment names a class:

```sh
export RAVENROOT_NODE_PACKAGES=ai.ravenroot.extensions.mail.MailNodePackage
```

Configure the corresponding package service grants separately. Registration fails closed when a
package lacks required capability, credential, egress, ingress, or deployment authority. Graph
content cannot name a class to load, and the pack publishes no `ServiceLoader` provider.

## Pack, BOM, and SBOM are different

- `ravenroot-extensions-all` is an installation convenience with ordinary Maven dependencies. It
  puts the reviewed extension JARs on a consumer classpath but activates none of them.
- A Maven BOM is dependency-management metadata. Importing a BOM can align versions, but it does not
  install the managed artifacts. The extension pack is not a BOM.
- The aggregate CycloneDX SBOM is release evidence: it records components and dependency
  relationships for inventory and audit. An SBOM is not a Maven dependency declaration and does not
  change a runtime classpath.

The pack remains outside Ravenroot's default binary distribution and OCI image. Choosing the broad
classpath convenience for an embedded application does not change the narrower standalone-product
boundary.

## Related contracts

- [Nodes, plugins, and runtime adapters](extensions-adapters.md)
- [Credentials, connectors, and egress](../operator-guide/credentials-egress.md)
- [Releasing Ravenroot](../governance/releasing.md)
