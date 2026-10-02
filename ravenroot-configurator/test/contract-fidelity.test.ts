import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { describe, expect, test } from "vitest";
import { CONTRACTS, serializeSelection, templateSelection } from "../src/registry.js";

const RUNTIME_SOURCES: Readonly<Record<string, string>> = {
  "bundle.service-grant": "ravenroot-server/src/main/java/ai/ravenroot/server/plugin/EnvironmentNodePackageServiceGrants.java",
  "ai.llm-profile": "ravenroot-extensions/ravenroot-ai/src/main/java/ai/ravenroot/extensions/ai/EnvironmentLlmProfileResolver.java",
  "ai.mcp-profile": "ravenroot-extensions/ravenroot-ai/src/main/java/ai/ravenroot/extensions/ai/EnvironmentMcpProfileResolver.java",
  "amqp.profile": "ravenroot-extensions/ravenroot-amqp091/src/main/java/ai/ravenroot/extensions/amqp091/EnvironmentAmqpProfileResolver.java",
  "amqp.consumer": "ravenroot-extensions/ravenroot-amqp091/src/main/java/ai/ravenroot/extensions/amqp091/EnvironmentAmqpConsumerPolicyResolver.java",
  "filesystem.profile": "ravenroot-extensions/ravenroot-filesystem/src/main/java/ai/ravenroot/extensions/filesystem/EnvironmentFilesystemProfileResolver.java",
  "git-workspace.profile": "ravenroot-extensions/ravenroot-git-workspace/src/main/java/ai/ravenroot/extensions/gitworkspace/EnvironmentGitWorkspaceProfileResolver.java",
  "jdbc.profile": "ravenroot-extensions/ravenroot-jdbc/src/main/java/ai/ravenroot/extensions/jdbc/EnvironmentJdbcProfileResolver.java",
  "kafka.producer-profile": "ravenroot-extensions/ravenroot-kafka/src/main/java/ai/ravenroot/extensions/kafka/EnvironmentKafkaProfileResolver.java",
  "kafka.consumer-profile": "ravenroot-extensions/ravenroot-kafka/src/main/java/ai/ravenroot/extensions/kafka/EnvironmentKafkaConsumerProfileResolver.java",
  "mail.smtp-profile": "ravenroot-extensions/ravenroot-mail/src/main/java/ai/ravenroot/extensions/mail/EnvironmentMailProfileResolver.java",
  "mail.imap-profile": "ravenroot-extensions/ravenroot-mail/src/main/java/ai/ravenroot/extensions/mail/imap/EnvironmentImapProfileResolver.java",
  "mail.imap-consumer": "ravenroot-extensions/ravenroot-mail/src/main/java/ai/ravenroot/extensions/mail/imap/EnvironmentImapConsumerPolicyResolver.java",
  "mail.imap-mutation": "ravenroot-extensions/ravenroot-mail/src/main/java/ai/ravenroot/extensions/mail/imap/EnvironmentImapMutationPolicyResolver.java",
  "object-storage.profile": "ravenroot-extensions/ravenroot-object-storage/src/main/java/ai/ravenroot/extensions/storage/EnvironmentStorageProfileResolver.java",
  "ocr.profile": "ravenroot-extensions/ravenroot-ocr/src/main/java/ai/ravenroot/extensions/ocr/EnvironmentOcrProfileResolver.java",
  "openapi-client.profile": "ravenroot-extensions/ravenroot-openapi-client/src/main/java/ai/ravenroot/extensions/openapi/client/EnvironmentOpenApiClientProfileResolver.java",
  "openapi-server.config": "ravenroot-extensions/ravenroot-openapi-server/src/main/java/ai/ravenroot/extensions/openapi/server/EnvironmentOpenApiServerConfigurationResolver.java",
  "telegram.profile": "ravenroot-extensions/ravenroot-telegram/src/main/java/ai/ravenroot/extensions/telegram/EnvironmentTelegramProfileResolver.java",
  "websocket.profile": "ravenroot-extensions/ravenroot-websocket/src/main/java/ai/ravenroot/extensions/websocket/EnvironmentWebSocketProfileResolver.java",
  "discord.config": "ravenroot-extensions/ravenroot-discord/src/main/java/ai/ravenroot/extensions/discord/DiscordConfiguration.java",
  "github.config": "ravenroot-extensions/ravenroot-github/src/main/java/ai/ravenroot/extensions/github/GithubConfiguration.java",
  "matrix.config": "ravenroot-extensions/ravenroot-matrix/src/main/java/ai/ravenroot/extensions/matrix/MatrixConfiguration.java",
  "mattermost.config": "ravenroot-extensions/ravenroot-mattermost/src/main/java/ai/ravenroot/extensions/mattermost/MattermostConfiguration.java",
  "slack.config": "ravenroot-extensions/ravenroot-slack/src/main/java/ai/ravenroot/extensions/slack/SlackConfiguration.java",
  "teams.config": "ravenroot-extensions/ravenroot-teams/src/main/java/ai/ravenroot/extensions/teams/TeamsConfiguration.java",
  "core.http": "ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootServerMain.java",
  "core.program": "ravenroot-server/src/main/java/ai/ravenroot/server/RavenrootServerMain.java",
  "core.human-task": "ravenroot-server/src/main/java/ai/ravenroot/server/humantaskinteraction/HumanTaskInteractionConfiguration.java",
  "core.publication-policies": "ravenroot-server/src/main/java/ai/ravenroot/server/PublicationPolicyConfiguration.java",
  "core.runner": "ravenroot-server/src/main/java/ai/ravenroot/server/RunnerPlaneConfiguration.java"
};

describe("runtime contract fidelity", () => {
  test("pins every contract to a current Java resolver or loader source", async () => {
    expect(Object.keys(RUNTIME_SOURCES).sort()).toEqual(CONTRACTS.map((contract) => contract.runtimeVerifier).sort());
    for (const [id, path] of Object.entries(RUNTIME_SOURCES)) {
      const source = await readFile(resolve("..", "ravenroot", path), "utf8");
      const contract = CONTRACTS.find((candidate) => candidate.id === id)!;
      if (contract.environment !== "*") expect(source).toContain(contract.environment.replace(/_$/, ""));
    }
  });

  test.each(CONTRACTS.map((contract) => [contract.id] as const))("%s has a strict positive round trip and a negative vector", (id) => {
    const contract = CONTRACTS.find((candidate) => candidate.id === id)!;
    const selection = templateSelection(id);
    const serialized = serializeSelection(selection);
    if (!contract.fields.some((field) => field.name.endsWith("Document"))) expect(Object.keys(serialized).length).toBeGreaterThan(0);
    if (contract.encoding === "base64-json") {
      const encoded = Object.values(serialized)[0]!;
      expect(JSON.parse(Buffer.from(encoded, "base64").toString("utf8"))).toEqual(selection.values.document);
      const invalid = structuredClone(selection) as { values: Record<string, unknown> };
      invalid.values = { document: { ...(invalid.values.document as Record<string, unknown>), unsupportedByRuntime: true } };
      expect(() => serializeSelection(invalid)).toThrow("is not supported");
    } else {
      const invalid = structuredClone(selection) as { values: Record<string, unknown> };
      invalid.values.unsupportedByRuntime = true;
      expect(() => serializeSelection(invalid)).toThrow("unsupported values");
      if (contract.encoding === "delimited") expect(Object.values(serialized)[0]!.split(";")).toHaveLength(contract.fields.length);
    }
  });

  test("matches the OpenAPI runtime resolver's exact field sets", async () => {
    const client = await readFile(resolve("..", "ravenroot", RUNTIME_SOURCES["openapi-client.profile"]!), "utf8");
    for (const field of Object.keys((templateSelection("openapi-client.profile").values.document as Record<string, unknown>))) {
      expect(client).toContain(`"${field}"`);
    }
    const server = await readFile(resolve("..", "ravenroot", RUNTIME_SOURCES["openapi-server.config"]!), "utf8");
    const document = templateSelection("openapi-server.config").values.document as Record<string, Record<string, unknown>>;
    for (const field of Object.keys(document)) expect(server).toContain(`"${field}"`);
    for (const field of Object.keys(document.projection!)) expect(server).toContain(`"${field}"`);
    for (const field of Object.keys(Object.values(document.profiles!)[0] as Record<string, unknown>)) expect(server).toContain(`"${field}"`);
  });
});
