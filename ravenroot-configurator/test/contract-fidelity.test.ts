import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { createHash } from "node:crypto";
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

  test("publishes versioned resolver limits and refuses representative boundary mismatches", () => {
    expect(CONTRACTS.every((contract) => contract.schemaVersion === 1)).toBe(true);
    const llm = templateSelection("ai.llm-profile") as { values: { document: Record<string, unknown> } };
    llm.values.document.maxConcurrency = 0;
    expect(() => serializeSelection(llm)).toThrow("must be at least 1");
    const insecure = templateSelection("ai.llm-profile") as { values: { document: Record<string, unknown> } };
    insecure.values.document.endpoint = "ftp://models.example.test/v1";
    expect(() => serializeSelection(insecure)).toThrow("must use http or https");
    const fragment = templateSelection("ai.llm-profile") as { values: { document: Record<string, unknown> } };
    fragment.values.document.endpoint = "https://models.example.test/v1#fragment";
    expect(() => serializeSelection(fragment)).toThrow("must not include a fragment");
    const partialCredential = templateSelection("ai.llm-profile") as { values: { document: Record<string, unknown> } };
    delete partialCredential.values.document.credentialReference;
    expect(() => serializeSelection(partialCredential)).toThrow("must be supplied together");
    const identity = templateSelection("ai.llm-profile") as { identity: { profile?: string } };
    identity.identity.profile = "x".repeat(65);
    expect(() => serializeSelection(identity)).toThrow("1 to 64 characters");
    const blankModel = templateSelection("ai.llm-profile") as { values: { document: Record<string, unknown> } };
    blankModel.values.document.model = "   ";
    expect(() => serializeSelection(blankModel)).toThrow("non-whitespace");
    for (const origin of ["https://api.example.test/v1", "https://api.example.test?tenant=a"]) {
      const openapi = templateSelection("openapi-client.profile") as { values: { document: Record<string, unknown> } };
      openapi.values.document.origin = origin;
      expect(() => serializeSelection(openapi)).toThrow("only an authority");
    }
  });

  test("models every governed runner catalog entry and protocol boundary", async () => {
    const runner = templateSelection("core.runner") as { values: { runnerDocument: Record<string, unknown> } };
    expect(() => serializeSelection(runner)).not.toThrow();
    const tenant = Object.values(runner.values.runnerDocument.tenants as Record<string, Record<string, unknown>>)[0]!;
    expect((tenant.definitions as unknown[])[0]).toBeTypeOf("object");
    expect((tenant.runners as unknown[])[0]).toBeTypeOf("object");
    expect((tenant.workspaceProfiles as unknown[])[0]).toBeTypeOf("object");
    runner.values.runnerDocument.protocolVersion = 2;
    expect(() => serializeSelection(runner)).toThrow("at most 1");

    const documented = JSON.parse(await readFile(resolve("..", "docs/examples/governed-runner/control-plane.json"), "utf8"));
    const documentedSelection = templateSelection("core.runner") as { values: { runnerDocument: Record<string, unknown> } };
    documentedSelection.values.runnerDocument = documented;
    expect(() => serializeSelection(documentedSelection)).not.toThrow();
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

  test("matches OpenAPI origin and header safety semantics", () => {
    const accepted = templateSelection("openapi-client.profile") as { values: { document: Record<string, unknown> } };
    accepted.values.document.origin = "https://API.Example.Test:443/";
    accepted.values.document.fixedHeaders = { "X-Mixed-Case": ["one", "two", "three", "four", "five", "six", "seven", "eight"] };
    accepted.values.document.inputHeaders = ["X-Request-Id"];
    accepted.values.document.responseHeaders = ["ETag"];
    expect(() => serializeSelection(accepted)).not.toThrow();
    const explicitNulls = structuredClone(accepted);
    explicitNulls.values.document.fixedHeaders = null;
    explicitNulls.values.document.credentialBindingId = null;
    explicitNulls.values.document.credentialReference = null;
    expect(() => serializeSelection(explicitNulls)).not.toThrow();

    for (const origin of ["HTTPS://api.example.test", "https://[::1]", "https://invalid_host", "https://-invalid.example.test"]) {
      const invalid = structuredClone(accepted); invalid.values.document.origin = origin;
      expect(() => serializeSelection(invalid), origin).toThrow();
    }
    for (const values of [[], Array.from({ length: 9 }, (_, index) => `value-${index}`)]) {
      const invalid = structuredClone(accepted); invalid.values.document.fixedHeaders = { "x-safe": values };
      expect(() => serializeSelection(invalid), JSON.stringify(values)).toThrow();
    }
    for (const value of ["   ", "line-one\rline-two", "line-one\nline-two"]) {
      const invalid = structuredClone(accepted); invalid.values.document.fixedHeaders = { "x-safe": [value] };
      expect(() => serializeSelection(invalid), JSON.stringify(value)).toThrow();
    }
    const forbidden = ["authorization", "cookie", "host", "content-length", "connection", "transfer-encoding", "upgrade", "proxy-authorization", "sec-fetch-site", "AuThOrIzAtIoN", "SeC-Fetch-Site"];
    for (const name of [...forbidden, "x".repeat(65), "bad header"]) {
      for (const field of ["fixedHeaders", "inputHeaders", "responseHeaders"] as const) {
        const invalid = structuredClone(accepted);
        invalid.values.document[field] = field === "fixedHeaders" ? { [name]: ["value"] } : [name];
        expect(() => serializeSelection(invalid), `${field}:${name}`).toThrow();
      }
    }
    const duplicate = structuredClone(accepted);
    duplicate.values.document.fixedHeaders = { "X-Trace": ["one"], "x-trace": ["two"] };
    expect(() => serializeSelection(duplicate)).toThrow("case-insensitive duplicate");
  });

  test("matches the OpenAPI decoded specification size boundary", () => {
    const selection = templateSelection("openapi-client.profile") as { values: { document: Record<string, unknown> } };
    const source = Buffer.from(String(selection.values.document.specBase64), "base64");
    const withSize = (size: number): void => {
      const specification = Buffer.concat([source, Buffer.alloc(size - source.length, 0x20)]);
      selection.values.document.specBase64 = specification.toString("base64");
      selection.values.document.specSha256 = createHash("sha256").update(specification).digest("hex");
      expect(String(selection.values.document.specBase64)).toHaveLength(2_796_204);
    };
    withSize(2_097_152);
    expect(() => serializeSelection(selection)).not.toThrow();
    withSize(2_097_153);
    expect(() => serializeSelection(selection)).toThrow("must decode to at most 2097152 bytes");
  });

  test("counts governed runner instructions in UTF-8 bytes", () => {
    const runner = templateSelection("core.runner") as { values: { runnerDocument: { tenants: Record<string, { definitions: { instructions: string; skills: string[]; skillInstructions: Record<string, string> }[] }> } } };
    (runner.values.runnerDocument as unknown as { protocolVersion: number }).protocolVersion = 1;
    const definition = Object.values(runner.values.runnerDocument.tenants)[0]!.definitions[0]!;
    definition.skills = ["review-skill"];
    definition.instructions = "é".repeat(32_768);
    definition.skillInstructions = { "review-skill": "é".repeat(32_768) };
    expect(() => serializeSelection(runner)).not.toThrow();
    definition.instructions += "é";
    expect(() => serializeSelection(runner)).toThrow("UTF-8 bytes");
    definition.instructions = "bounded";
    definition.skillInstructions["review-skill"] += "é";
    expect(() => serializeSelection(runner)).toThrow("UTF-8 bytes");
  });
});
