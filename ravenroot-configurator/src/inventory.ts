export type CoverageKind = "operator-configured" | "author-only" | "runtime-seam-unavailable";

export interface NodeCoverage {
  readonly nodeId: string;
  readonly bundle: string;
  readonly kind: CoverageKind;
  readonly contracts: readonly string[];
  readonly rationale: string;
}

const authorOnly = (nodeId: string): NodeCoverage => ({
  nodeId,
  bundle: "core",
  kind: "author-only",
  contracts: [],
  rationale: "The shipped behavior uses author properties and platform defaults without a node-specific operator profile."
});

const covered = (nodeId: string, bundle: string, contracts: readonly string[]): NodeCoverage => ({
  nodeId,
  bundle,
  kind: "operator-configured",
  contracts,
  rationale: "The shipped behavior resolves operator-owned profile, credential, grant, policy, external-file, or backend configuration."
});

export const NODE_COVERAGE: readonly NodeCoverage[] = [
  covered("agent", "AI", ["ai.llm-profile", "ai.mcp-profile", "core.runner"]),
  covered("amqp.consume", "AMQP 0-9-1", ["amqp.profile", "amqp.consumer"]),
  covered("amqp.publish", "AMQP 0-9-1", ["amqp.profile"]),
  authorOnly("bigint-op"),
  covered("boundary-guard", "core", ["core.publication-policies"]),
  authorOnly("cel-decision"), authorOnly("cel-transform"), authorOnly("crontab"), authorOnly("delay"),
  covered("discord.interactions", "Discord", ["discord.config"]),
  covered("discord.send", "Discord", ["discord.config"]),
  covered("filesystem.read", "Filesystem", ["filesystem.profile"]),
  covered("filesystem.write", "Filesystem", ["filesystem.profile"]),
  covered("git-workspace", "Git workspace", ["git-workspace.profile"]),
  covered("github-app-review", "GitHub automation", ["github.config"]),
  covered("github-events-source", "GitHub automation", ["github.config"]),
  covered("github-workflow-watch", "GitHub automation", ["github.config"]),
  covered("http-request", "core", ["core.http"]),
  covered("human-task", "core", ["core.human-task"]),
  covered("jdbc.insert", "JDBC", ["jdbc.profile"]),
  covered("jdbc.query", "JDBC", ["jdbc.profile"]),
  authorOnly("json-parse"), authorOnly("json-path"),
  covered("kafka.consume", "Kafka", ["kafka.consumer-profile"]),
  covered("kafka.produce", "Kafka", ["kafka.producer-profile"]),
  covered("llm-prompt", "AI", ["ai.llm-profile"]),
  authorOnly("log"),
  covered("mail.imap.consume", "Mail", ["mail.imap-profile", "mail.imap-consumer"]),
  covered("mail.imap.delete", "Mail", ["mail.imap-profile", "mail.imap-mutation"]),
  covered("mail.imap.move", "Mail", ["mail.imap-profile", "mail.imap-mutation"]),
  covered("mail.imap.query", "Mail", ["mail.imap-profile"]),
  covered("mail.send", "Mail", ["mail.smtp-profile"]),
  covered("matrix.send", "Matrix", ["matrix.config"]),
  covered("matrix.sync", "Matrix", ["matrix.config"]),
  covered("mattermost.outgoing-webhook", "Mattermost", ["mattermost.config"]),
  covered("mattermost.send", "Mattermost", ["mattermost.config"]),
  covered("object.delete", "Object storage", ["object-storage.profile"]),
  covered("object.get", "Object storage", ["object-storage.profile"]),
  covered("object.list", "Object storage", ["object-storage.profile"]),
  covered("object.put", "Object storage", ["object-storage.profile"]),
  covered("ocr.extract", "OCR", ["ocr.profile"]),
  covered("openapi.call", "OpenAPI client", ["openapi-client.profile"]),
  covered("openapi.receive", "OpenAPI server", ["openapi-server.config"]),
  covered("openapi.request-reply", "OpenAPI server", ["openapi-server.config"]),
  covered("program", "core", ["core.program"]),
  covered("project-transition", "GitHub automation", ["github.config"]),
  covered("release-prepare", "GitHub automation", ["github.config"]),
  covered("slack.commands", "Slack", ["slack.config"]),
  covered("slack.events", "Slack", ["slack.config"]),
  covered("slack.post-message", "Slack", ["slack.config"]),
  { ...authorOnly("spel.decision"), bundle: "Restricted SpEL", rationale: "The bundle declares no profile, credential, service grant, egress, or external-file contract." },
  { ...authorOnly("spel.transform"), bundle: "Restricted SpEL", rationale: "The bundle declares no profile, credential, service grant, egress, or external-file contract." },
  covered("teams.outgoing-webhook", "Microsoft Teams", ["teams.config"]),
  covered("teams.send", "Microsoft Teams", ["teams.config"]),
  covered("telegram.answer.callback", "Telegram", ["telegram.profile"]),
  covered("telegram.delete.message", "Telegram", ["telegram.profile"]),
  covered("telegram.edit.message", "Telegram", ["telegram.profile"]),
  covered("telegram.send", "Telegram", ["telegram.profile"]),
  authorOnly("template"), authorOnly("timer"),
  covered("websocket.receive", "WebSocket", ["websocket.profile"]),
  covered("websocket.send", "WebSocket", ["websocket.profile"])
] as const;

export const GOVERNED_NODE_COVERAGE: readonly NodeCoverage[] = [
  covered("agent", "AI", ["ai.llm-profile", "ai.mcp-profile", "core.runner"]),
  covered("workspace", "Governed runner", ["core.runner"])
] as const;

export interface BundleCoverage {
  readonly label: string;
  readonly manifestId: string;
  readonly contracts: readonly string[];
}

export const BUNDLE_COVERAGE: readonly BundleCoverage[] = [
  { label: "AI", manifestId: "ai.ravenroot.extensions.ai", contracts: ["ai.llm-profile", "ai.mcp-profile"] },
  { label: "AMQP 0-9-1", manifestId: "ai.ravenroot.extensions.amqp091", contracts: ["amqp.profile", "amqp.consumer"] },
  { label: "Discord", manifestId: "ai.ravenroot.extensions.discord", contracts: ["discord.config"] },
  { label: "Filesystem", manifestId: "ai.ravenroot.extensions.filesystem", contracts: ["filesystem.profile"] },
  { label: "Git workspace", manifestId: "ai.ravenroot.extensions.gitworkspace", contracts: ["git-workspace.profile"] },
  { label: "GitHub automation", manifestId: "ai.ravenroot.extensions.github", contracts: ["github.config"] },
  { label: "JDBC", manifestId: "ai.ravenroot.extensions.jdbc", contracts: ["jdbc.profile"] },
  { label: "Kafka", manifestId: "ai.ravenroot.extensions.kafka", contracts: ["kafka.producer-profile", "kafka.consumer-profile"] },
  { label: "Mail", manifestId: "ai.ravenroot.extensions.mail", contracts: ["mail.smtp-profile", "mail.imap-profile", "mail.imap-consumer", "mail.imap-mutation"] },
  { label: "Matrix", manifestId: "ai.ravenroot.extensions.matrix", contracts: ["matrix.config"] },
  { label: "Mattermost", manifestId: "ai.ravenroot.extensions.mattermost", contracts: ["mattermost.config"] },
  { label: "Object storage", manifestId: "ai.ravenroot.extensions.storage", contracts: ["object-storage.profile"] },
  { label: "OCR", manifestId: "ai.ravenroot.extensions.ocr", contracts: ["ocr.profile"] },
  { label: "OpenAPI client", manifestId: "ai.ravenroot.extensions.openapi.client", contracts: ["openapi-client.profile"] },
  { label: "OpenAPI server", manifestId: "ai.ravenroot.extensions.openapi.server", contracts: ["openapi-server.config"] },
  { label: "Slack", manifestId: "ai.ravenroot.extensions.slack", contracts: ["slack.config"] },
  { label: "Restricted SpEL", manifestId: "ai.ravenroot.extensions.spel", contracts: [] },
  { label: "Microsoft Teams", manifestId: "ai.ravenroot.extensions.teams", contracts: ["teams.config"] },
  { label: "Telegram", manifestId: "ai.ravenroot.extensions.telegram", contracts: ["telegram.profile"] },
  { label: "WebSocket", manifestId: "ai.ravenroot.extensions.websocket", contracts: ["websocket.profile"] }
] as const;

export const AUXILIARY_ENVIRONMENT_FAMILIES = [
  "RAVENROOT_AMQP091_CREDENTIAL_", "RAVENROOT_KAFKA_CREDENTIAL_", "RAVENROOT_MAIL_CREDENTIAL_",
  "RAVENROOT_TELEGRAM_CREDENTIAL_", "RAVENROOT_CREDENTIAL_", "RAVENROOT_NODE_PACKAGE_SERVICES_",
  "RAVENROOT_ENABLED_PLUGINS", "RAVENROOT_PLUGINS_INSTALL_DIR", "RAVENROOT_PUBLICATION_POLICY_CONFIG"
] as const;
