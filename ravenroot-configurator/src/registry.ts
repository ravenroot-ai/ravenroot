import { base64Json, safeName, utf8Hex } from "./codec.js";
import { schemaFromTemplate, validateValue } from "./schema.js";
import type { ConfigurationContract, ConfigurationSelection, FieldSpec } from "./types.js";

const requiredString = (name: string, label = name): FieldSpec => ({ name, label, type: "string", required: true });
const optionalString = (name: string, label = name): FieldSpec => ({ name, label, type: "string", required: false });
const requiredInt = (name: string, minimum = 1, maximum?: number): FieldSpec => ({
  name, label: name, type: "integer", required: true, minimum, ...(maximum === undefined ? {} : { maximum })
});
const requiredBoolean = (name: string): FieldSpec => ({ name, label: name, type: "boolean", required: true });
const requiredCsv = (name: string): FieldSpec => ({ name, label: name, type: "csv", required: true });
const optionalCsv = (name: string): FieldSpec => ({ name, label: name, type: "csv", required: false });
const strictJson = (name: string, label: string, suggestion: Readonly<Record<string, unknown>>): FieldSpec => ({
  name, label, type: "json", required: true, suggestion, schema: schemaFromTemplate(suggestion)
});

const jsonContract = (
  id: string,
  title: string,
  family: string,
  bundleId: string,
  nodeIds: readonly string[],
  environment: string,
  identity: readonly ("tenant" | "profile")[],
  jsonTemplate: Readonly<Record<string, unknown>>,
  requiredCapabilities: readonly string[] = [],
  externalRequirements: readonly string[] = []
): ConfigurationContract => ({
  id,
  title,
  family,
  bundleId,
  nodeIds,
  environment,
  identity,
  encoding: "base64-json",
  fields: [{ name: "document", label: "Configuration document", type: "json", required: true,
    schema: schemaFromTemplate(jsonTemplate) }],
  jsonTemplate,
  schema: schemaFromTemplate(jsonTemplate),
  runtimeVerifier: id,
  requiredCapabilities,
  externalRequirements,
  restartRequired: true,
  credentialResolver: requiredCapabilities.includes("credential-resolution") || requiredCapabilities.includes("outbound-http")
    ? "shared" : "none"
});

const delimitedContract = (
  id: string,
  title: string,
  family: string,
  bundleId: string,
  nodeIds: readonly string[],
  environment: string,
  fields: readonly FieldSpec[],
  options: {
    identity?: readonly ("tenant" | "profile" | "reference")[];
    capabilities?: readonly string[];
    external?: readonly string[];
    credentials?: "none" | "shared" | "family";
  } = {}
): ConfigurationContract => ({
  id,
  title,
  family,
  bundleId,
  nodeIds,
  environment,
  identity: options.identity ?? ["tenant", "profile"],
  encoding: "delimited",
  delimiter: ";",
  fields,
  runtimeVerifier: id,
  requiredCapabilities: options.capabilities ?? [],
  externalRequirements: options.external ?? [],
  restartRequired: true,
  credentialResolver: options.credentials ?? "none"
});

const authority = {
  listenerId: "main",
  pathPrefix: "/managed/example",
  requiredScopes: ["example:callbacks"],
  maxRoutes: 8,
  maxConcurrentRequests: 32,
  maxRequestBytes: 1048576,
  maxResponseBytes: 65536,
  requestTimeoutMs: 2500
};

const projection = {
  maxRelativePathBytes: 256,
  maxQueryParameters: 1,
  maxQueryBytes: 256,
  maxHeaderCount: 3,
  maxHeaderBytes: 1024,
  maxHeaderValueBytes: 512
};

export const DELIMITED_TEMPLATES: Readonly<Record<string, Readonly<Record<string, unknown>>>> = {
  "amqp.profile": { host: "localhost", port: 5672, tls: false, vhost: "/", username: "operator", credentialRef: "broker",
    defaultExchange: "", additionalExchanges: [], defaultRoutingKey: "events", additionalRoutingKeys: [], approvedHeaders: ["trace-id"],
    approvedReplyTo: [], allowPersistent: true, maxPriority: 5, maxExpirationMs: 60000, maxConcurrency: 2,
    maxPerSecond: 10, timeoutMs: 1000, maxBodyBytes: 65536, retries: 1 },
  "amqp.consumer": { queue: "events", prefetch: 8, approvedHeaders: ["trace-id"], identityHeader: "trace-id",
    maxBodyBytes: 65536, maxHeaderBytes: 8192, retryBackoffMs: 100, maxRetryBackoffMs: 1000,
    poisonAttempts: 3, poisonPolicy: "reject", drainTimeoutMs: 1000 },
  "filesystem.profile": { canonicalAbsoluteRoot: "/", read: true, write: false, allowedRelativeGlobs: ["**"],
    maxBytes: 65536, maxConcurrency: 2, deadlineMs: 1000 },
  "kafka.producer-profile": { bootstrapServers: "localhost:9092", clientDnsLookup: "use_all_dns_ips", tls: false,
    saslMechanism: "PLAIN", username: "operator", credentialRef: "broker", clientId: "ravenroot", defaultTopic: "events",
    additionalTopics: [], approvedHeaders: ["trace-id"], allowPartition: false, maxPartition: 0, allowTimestamp: false,
    compression: "none", acks: "all", idempotence: true, retries: 1, maxInFlight: 1, allowAutoCreate: false,
    maxConcurrency: 2, maxPerSecond: 10, timeoutMs: 1000, maxRecordBytes: 65536, bufferMemoryBytes: 65536 },
  "kafka.consumer-profile": { bootstrapServers: "localhost:9092", clientDnsLookup: "use_all_dns_ips", tls: false,
    saslMechanism: "PLAIN", username: "operator", credentialRef: "broker", clientId: "ravenroot", groupLogicalName: "events",
    groupId: "events", staticMemberId: "", topics: ["events"], anchoredTopicPattern: "", approvedHeaders: ["trace-id"],
    assignmentStrategy: "cooperative-sticky", autoOffsetReset: "earliest", isolationLevel: "read_committed",
    startupTimeoutMs: 1000, pollTimeoutMs: 100, maxPollIntervalMs: 10000, sessionTimeoutMs: 3000,
    heartbeatIntervalMs: 1000, maxInFlight: 8, maxFetchBytes: 65536, maxPartitionFetchBytes: 65536,
    maxRecordBytes: 32768, maxKeyBytes: 4096, maxValueBytes: 32768, maxHeaderBytes: 4096,
    drainTimeoutMs: 1000, retryBackoffMs: 100, maxRetryBackoffMs: 1000, poisonAttempts: 3,
    poisonPolicy: "halt", deadLetterTopic: "" },
  "mail.smtp-profile": { host: "localhost", port: 465, securityMode: "SMTPS", allowPlaintext: false, username: "operator",
    credentialRef: "mail", defaultFrom: "sender@example.test", allowedRecipients: ["recipient@example.test"],
    allowedHeaders: ["subject"], maxConcurrency: 2, allowedReplyTo: ["sender@example.test"] },
  "mail.imap-profile": { host: "localhost", port: 993, securityMode: "IMAPS", username: "operator", credentialRef: "mail",
    folders: ["INBOX"], connectTimeoutMs: 1000, readTimeoutMs: 1000, maxConcurrency: 2, maxResults: 10, maxPreviewChars: 100 },
  "mail.imap-consumer": { folder: "INBOX", pollIntervalMs: 1000, batchSize: 1, scanWindow: 8, retryBackoffMs: 100,
    maxRetryBackoffMs: 1000, poisonAttempts: 3, maxMessageBytes: 65536, contentMode: "metadata", maxPreviewChars: 0,
    allowedHeaders: ["subject"] },
  "mail.imap-mutation": { operations: ["MOVE"], destinationFolders: ["Archive"], trashFolder: "" },
  "ocr.profile": { absoluteExecutable: "/usr/bin/true", absoluteTessdata: "/tmp", allowedLanguages: ["eng"],
    absoluteTempRoot: "/tmp", deadlineMs: 1000, maxInputBytes: 65536, maxOutputBytes: 65536, concurrency: 1, shutdownMs: 1000 },
  "telegram.profile": { credentialRef: "telegram", allowedChats: ["*"], allowedMethods: ["sendMessage"],
    allowedButtonHosts: ["example.test"], businessConnectionAuthority: false, maxConcurrency: 2, requestsPerSecond: 10,
    connectTimeoutMs: 1000, requestTimeoutMs: 1000, maxTextChars: 1000, maxPhotoBytes: 65536, maxButtons: 10, retries: 1 }
};

export const CONTRACTS: readonly ConfigurationContract[] = [
  {
    id: "bundle.service-grant", title: "Node package managed-service grant", family: "Package activation",
    nodeIds: [], environment: "RAVENROOT_NODE_PACKAGE_SERVICES_", identity: ["profile"], encoding: "base64-json",
    fields: [{ name: "document", label: "Strict managed-service grant", type: "json", required: true,
      schema: schemaFromTemplate({ capabilities: ["outbound-http"],
        origins: [{ scheme: "https", host: "api.example.test", port: 443 }],
        httpMethods: ["GET"], requestHeaders: ["content-type"], responseHeaders: ["content-type"],
        webSocketSubprotocols: ["protocol"], credentialBindings: ["binding"], awsSigV4Bindings: ["binding"], credentialReferences: ["reference"],
        limits: { maxRequestBytes: 1048576, maxResponseBytes: 4194304, maxDeadlineMs: 15000 } }) }],
    jsonTemplate: { capabilities: ["outbound-http"],
      origins: [{ scheme: "https", host: "api.example.test", port: 443 }],
      httpMethods: ["GET", "POST"], requestHeaders: ["content-type"], responseHeaders: ["content-type"],
      webSocketSubprotocols: [], credentialBindings: [], awsSigV4Bindings: [], credentialReferences: ["credential"],
      limits: { maxRequestBytes: 1048576, maxResponseBytes: 4194304, maxDeadlineMs: 15000 } },
    requiredCapabilities: [], externalRequirements: ["Use the exact plugin manifest ID as the profile identity."],
    schema: schemaFromTemplate({ capabilities: ["outbound-http"],
      origins: [{ scheme: "https", host: "api.example.test", port: 443 }],
      httpMethods: ["GET"], requestHeaders: ["content-type"], responseHeaders: ["content-type"],
      webSocketSubprotocols: ["protocol"], credentialBindings: ["binding"], awsSigV4Bindings: ["binding"], credentialReferences: ["reference"],
      limits: { maxRequestBytes: 1048576, maxResponseBytes: 4194304, maxDeadlineMs: 15000 } }),
    runtimeVerifier: "bundle.service-grant", restartRequired: true, credentialResolver: "shared"
  },
  jsonContract("ai.llm-profile", "AI model profile", "AI / LLM", "ai.ravenroot.extensions.ai",
    ["llm-prompt", "agent"], "RAVENROOT_LLM_PROFILE_", ["profile"], {
      endpoint: "https://models.example.test/v1", model: "model-name", credentialBindingId: "llm",
      credentialReference: "llm-token", timeoutMs: 60000, maxRequestBytes: 8388608,
      maxResponseBytes: 8388608, maxConcurrency: 4, systemPreamble: ""
    }, ["outbound-http", "tool-authorization", "agent-resources"]),
  jsonContract("ai.mcp-profile", "AI MCP server profile", "AI / MCP", "ai.ravenroot.extensions.ai",
    ["agent"], "RAVENROOT_MCP_SERVER_", ["profile"], {
      endpoint: "https://mcp.example.test/mcp", credentialBindingId: "mcp", credentialReference: "mcp-token",
      timeoutMs: 30000, maxRequestBytes: 1048576, maxResponseBytes: 1048576, maxConcurrency: 4,
      maxDiscoveredTools: 128, allowedTools: ["search"]
    }, ["outbound-http", "tool-authorization", "agent-resources"]),
  delimitedContract("amqp.profile", "AMQP publisher profile", "AMQP 0-9-1", "ai.ravenroot.extensions.amqp091",
    ["amqp.publish"], "RAVENROOT_AMQP091_PROFILE_", [
      requiredString("host"), requiredInt("port", 1, 65535), requiredBoolean("tls"), requiredString("vhost"),
      requiredString("username"), requiredString("credentialRef"), optionalString("defaultExchange"), optionalCsv("additionalExchanges"),
      requiredString("defaultRoutingKey"), optionalCsv("additionalRoutingKeys"), optionalCsv("approvedHeaders"), optionalCsv("approvedReplyTo"),
      requiredBoolean("allowPersistent"), requiredInt("maxPriority", 0, 9), requiredInt("maxExpirationMs", 1, 86400000),
      requiredInt("maxConcurrency", 1, 16), requiredInt("maxPerSecond", 1, 100), requiredInt("timeoutMs", 1, 30000),
      requiredInt("maxBodyBytes", 1, 1048576), requiredInt("retries", 0, 3)
    ], { credentials: "family" }),
  delimitedContract("amqp.consumer", "AMQP consumer policy", "AMQP 0-9-1", "ai.ravenroot.extensions.amqp091",
    ["amqp.consume"], "RAVENROOT_AMQP091_CONSUMER_", [
      requiredString("queue"), requiredInt("prefetch"), optionalCsv("approvedHeaders"), optionalString("identityHeader"),
      requiredInt("maxBodyBytes"), requiredInt("maxHeaderBytes"), requiredInt("retryBackoffMs", 100),
      requiredInt("maxRetryBackoffMs", 1000), requiredInt("poisonAttempts"),
      { ...requiredString("poisonPolicy"), allowed: ["reject", "dead-letter"] }, requiredInt("drainTimeoutMs")
    ], { credentials: "family" }),
  delimitedContract("filesystem.profile", "Filesystem profile", "Filesystem", "ai.ravenroot.extensions.filesystem",
    ["filesystem.read", "filesystem.write"], "RAVENROOT_FILESYSTEM_PROFILE_", [
      requiredString("canonicalAbsoluteRoot"), requiredBoolean("read"), requiredBoolean("write"), requiredCsv("allowedRelativeGlobs"),
      requiredInt("maxBytes", 1, 67108864), requiredInt("maxConcurrency", 1, 1024), requiredInt("deadlineMs", 1, 300000)
    ], { external: ["The canonical root must exist and support SecureDirectoryStream."] }),
  jsonContract("git-workspace.profile", "Git workspace profile", "Git workspace", "ai.ravenroot.extensions.gitworkspace",
    ["git-workspace"], "RAVENROOT_GIT_WORKSPACE_PROFILE_", ["tenant", "profile"], {
      root: "/", remote: "https://scm.example.test/team/repository.git",
      baseRef: "refs/heads/dev", issueRefPrefix: "refs/heads/issues/", gitExecutable: "/usr/bin/git",
      processShellExecutable: "/bin/sh", objectFormat: "sha1", deadlineMs: 30000, maxConcurrency: 4,
      maxOutputBytes: 262144, historyScanLimit: 1000, credentialRef: "git-token", credentialUsername: "git"
    }, ["credential-resolution"], ["Root and executable paths must exist on the target."]),
  jsonContract("jdbc.profile", "JDBC statement profile", "JDBC", "ai.ravenroot.extensions.jdbc",
    ["jdbc.query", "jdbc.insert"], "RAVENROOT_JDBC_PROFILE_", ["tenant", "profile"], {
      driverId: "postgresql-42.7.12", driverClass: "org.postgresql.Driver",
      driverSha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      url: "jdbc:postgresql://database.internal:5432/application", username: "application",
      credentialRef: "application-db-password", schema: "accounting", isolation: "READ_COMMITTED",
      deadlineMs: 2000, maxConcurrency: 4, maxParameters: 32, maxParameterBytes: 65536,
      maxRows: 1000, maxColumns: 64, maxCellBytes: 65536, maxTotalBytes: 1048576,
      maxGeneratedKeyRows: 16,
      statements: { "find-user": { kind: "QUERY", sql: "SELECT id,name FROM users WHERE id=:id", generatedKeys: [] } }
    }, ["credential-resolution"], ["The closed JDBC bundle must contain the exact driver bytes named by driverSha256."]),
  delimitedContract("kafka.producer-profile", "Kafka producer profile", "Kafka", "ai.ravenroot.extensions.kafka",
    ["kafka.produce"], "RAVENROOT_KAFKA_PROFILE_", [
      requiredString("bootstrapServers"), requiredString("clientDnsLookup"), requiredBoolean("tls"), requiredString("saslMechanism"),
      optionalString("username"), optionalString("credentialRef"), requiredString("clientId"), requiredString("defaultTopic"),
      optionalCsv("additionalTopics"), optionalCsv("approvedHeaders"), requiredBoolean("allowPartition"), requiredInt("maxPartition", 0),
      requiredBoolean("allowTimestamp"), requiredString("compression"), requiredString("acks"), requiredBoolean("idempotence"),
      requiredInt("retries", 1), requiredInt("maxInFlight", 1, 5), requiredBoolean("allowAutoCreate"),
      requiredInt("maxConcurrency"), requiredInt("maxPerSecond"), requiredInt("timeoutMs"), requiredInt("maxRecordBytes"),
      requiredInt("bufferMemoryBytes")
    ], { credentials: "family" }),
  delimitedContract("kafka.consumer-profile", "Kafka consumer profile", "Kafka", "ai.ravenroot.extensions.kafka",
    ["kafka.consume"], "RAVENROOT_KAFKA_CONSUMER_PROFILE_", [
      requiredString("bootstrapServers"), requiredString("clientDnsLookup"), requiredBoolean("tls"), requiredString("saslMechanism"),
      optionalString("username"), optionalString("credentialRef"), requiredString("clientId"), requiredString("groupLogicalName"),
      requiredString("groupId"), optionalString("staticMemberId"), optionalCsv("topics"), optionalString("anchoredTopicPattern"),
      optionalCsv("approvedHeaders"), requiredString("assignmentStrategy"), requiredString("autoOffsetReset"),
      requiredString("isolationLevel"), requiredInt("startupTimeoutMs"), requiredInt("pollTimeoutMs"), requiredInt("maxPollIntervalMs"),
      requiredInt("sessionTimeoutMs"), requiredInt("heartbeatIntervalMs"), requiredInt("maxInFlight"), requiredInt("maxFetchBytes"),
      requiredInt("maxPartitionFetchBytes"), requiredInt("maxRecordBytes"), requiredInt("maxKeyBytes"), requiredInt("maxValueBytes"),
      requiredInt("maxHeaderBytes"), requiredInt("drainTimeoutMs"), requiredInt("retryBackoffMs"), requiredInt("maxRetryBackoffMs"),
      requiredInt("poisonAttempts"), requiredString("poisonPolicy"), optionalString("deadLetterTopic")
    ], { credentials: "family" }),
  delimitedContract("mail.smtp-profile", "Mail SMTP profile", "Mail / SMTP", "ai.ravenroot.extensions.mail",
    ["mail.send"], "RAVENROOT_MAIL_PROFILE_", [
      requiredString("host"), requiredInt("port", 1, 65535), requiredString("securityMode"), requiredBoolean("allowPlaintext"),
      optionalString("username"), optionalString("credentialRef"), requiredString("defaultFrom"), requiredCsv("allowedRecipients"),
      optionalCsv("allowedHeaders"), requiredInt("maxConcurrency", 1, 16), optionalCsv("allowedReplyTo")
    ], { credentials: "family" }),
  delimitedContract("mail.imap-profile", "Mail IMAP profile", "Mail / IMAP", "ai.ravenroot.extensions.mail",
    ["mail.imap.query", "mail.imap.consume", "mail.imap.move", "mail.imap.delete"], "RAVENROOT_IMAP_PROFILE_", [
      requiredString("host"), requiredInt("port", 1, 65535), { ...requiredString("securityMode"), allowed: ["IMAPS", "STARTTLS"] },
      requiredString("username"), requiredString("credentialRef"), requiredCsv("folders"), requiredInt("connectTimeoutMs"),
      requiredInt("readTimeoutMs"), requiredInt("maxConcurrency"), requiredInt("maxResults"), requiredInt("maxPreviewChars", 0, 65536)
    ], { credentials: "family" }),
  delimitedContract("mail.imap-consumer", "IMAP consumer policy", "Mail / IMAP", "ai.ravenroot.extensions.mail",
    ["mail.imap.consume"], "RAVENROOT_IMAP_CONSUMER_", [
      requiredString("folder"), requiredInt("pollIntervalMs", 100, 60000), requiredInt("batchSize", 1, 100),
      requiredInt("scanWindow", 1, 512), requiredInt("retryBackoffMs", 100, 60000), requiredInt("maxRetryBackoffMs", 100, 60000),
      requiredInt("poisonAttempts", 1, 100), requiredInt("maxMessageBytes", 1, 1048576),
      { ...requiredString("contentMode"), allowed: ["metadata", "preview"] }, requiredInt("maxPreviewChars", 0, 65536),
      optionalCsv("allowedHeaders")
    ], { credentials: "family" }),
  delimitedContract("mail.imap-mutation", "IMAP mutation policy", "Mail / IMAP", "ai.ravenroot.extensions.mail",
    ["mail.imap.move", "mail.imap.delete"], "RAVENROOT_IMAP_MUTATION_POLICY_", [
      requiredCsv("operations"), optionalCsv("destinationFolders"), optionalString("trashFolder")
    ], { credentials: "family" }),
  jsonContract("object-storage.profile", "Object storage profile", "Object storage", "ai.ravenroot.extensions.storage",
    ["object.get", "object.put", "object.list", "object.delete"], "RAVENROOT_OBJECT_STORAGE_PROFILE_", ["profile"], {
      origin: "https://s3.eu-west-1.example.test", region: "eu-west-1", bucket: "bucket-a", keyPrefix: "tenant-data",
      addressingStyle: "path", signingBindingId: "assets-s3", operations: ["get", "put", "list", "delete"],
      contentTypes: ["text/plain", "application/octet-stream"], allowIfMatch: true, allowIfNoneMatch: true,
      maxObjectBytes: 1048576, timeoutMs: 10000, maxConcurrency: 8, maxRequestsPerSecond: 32
    }, ["outbound-http"]),
  delimitedContract("ocr.profile", "OCR profile", "OCR", "ai.ravenroot.extensions.ocr", ["ocr.extract"],
    "RAVENROOT_OCR_PROFILE_", [requiredString("absoluteExecutable"), requiredString("absoluteTessdata"),
      requiredCsv("allowedLanguages"), requiredString("absoluteTempRoot"), requiredInt("deadlineMs"),
      requiredInt("maxInputBytes"), requiredInt("maxOutputBytes"), requiredInt("concurrency"), requiredInt("shutdownMs")],
    { external: ["Tesseract, tessdata, and the temporary root must already exist on the target."] }),
  jsonContract("openapi-client.profile", "OpenAPI client profile", "OpenAPI client", "ai.ravenroot.extensions.openapi.client",
    ["openapi.call"], "RAVENROOT_OPENAPI_CLIENT_PROFILE_", ["profile"], {
      origin: "https://api.example.test", specBase64: "eyJvcGVuYXBpIjoiMy4wLjMiLCJpbmZvIjp7InRpdGxlIjoiT3JkZXJzIiwidmVyc2lvbiI6IjEifSwicGF0aHMiOnsiL29yZGVycy97aWR9Ijp7InBvc3QiOnsib3BlcmF0aW9uSWQiOiJjcmVhdGVPcmRlciIsInBhcmFtZXRlcnMiOlt7Im5hbWUiOiJpZCIsImluIjoicGF0aCIsInJlcXVpcmVkIjp0cnVlLCJzY2hlbWEiOnsidHlwZSI6InN0cmluZyIsIm1heExlbmd0aCI6MzJ9fSx7Im5hbWUiOiJ2ZXJib3NlIiwiaW4iOiJxdWVyeSIsInNjaGVtYSI6eyJ0eXBlIjoiYm9vbGVhbiJ9fSx7Im5hbWUiOiJYLVRyYWNlIiwiaW4iOiJoZWFkZXIiLCJyZXF1aXJlZCI6dHJ1ZSwic2NoZW1hIjp7InR5cGUiOiJzdHJpbmciLCJtYXhMZW5ndGgiOjY0fX1dLCJyZXF1ZXN0Qm9keSI6eyJyZXF1aXJlZCI6dHJ1ZSwiY29udGVudCI6eyJhcHBsaWNhdGlvbi9qc29uIjp7InNjaGVtYSI6eyJ0eXBlIjoib2JqZWN0IiwicHJvcGVydGllcyI6eyJhbW91bnQiOnsidHlwZSI6ImludGVnZXIiLCJtaW5pbXVtIjoxfX0sInJlcXVpcmVkIjpbImFtb3VudCJdLCJhZGRpdGlvbmFsUHJvcGVydGllcyI6ZmFsc2V9fX19LCJyZXNwb25zZXMiOnsiMjAwIjp7ImRlc2NyaXB0aW9uIjoiY29tcGxldGVkIiwiaGVhZGVycyI6eyJyZXN1bHQtaWQiOnsic2NoZW1hIjp7InR5cGUiOiJzdHJpbmciLCJtYXhMZW5ndGgiOjY0fX19LCJjb250ZW50Ijp7ImFwcGxpY2F0aW9uL2pzb24iOnsic2NoZW1hIjp7InR5cGUiOiJvYmplY3QiLCJwcm9wZXJ0aWVzIjp7InJlc3VsdCI6eyJ0eXBlIjoic3RyaW5nIiwibWF4TGVuZ3RoIjo2NH19LCJyZXF1aXJlZCI6WyJyZXN1bHQiXSwiYWRkaXRpb25hbFByb3BlcnRpZXMiOmZhbHNlfX19fSwiMjAyIjp7ImRlc2NyaXB0aW9uIjoiYWNjZXB0ZWQifX19fSwiL29yZGVycy9zcGVjaWFsIjp7InBvc3QiOnsib3BlcmF0aW9uSWQiOiJzcGVjaWFsT3JkZXIiLCJyZXNwb25zZXMiOnsiMjAyIjp7ImRlc2NyaXB0aW9uIjoiYWNjZXB0ZWQifX19fX19Cg==",
      specSha256: "31a19b3851da4630beff77bf3675b3ac6bb12c203d3b7d5c44cc3a60ff662424",
      operations: ["createOrder"], fixedHeaders: {}, inputHeaders: ["content-type", "x-trace"], responseHeaders: ["content-type", "result-id"],
      credentialBindingId: "openapi", credentialReference: "openapi-token", maxRequestBytes: 1048576,
      maxResponseBytes: 8388608, timeoutMs: 30000, maxConcurrency: 8
    }, ["outbound-http"]),
  jsonContract("openapi-server.config", "OpenAPI server configuration", "OpenAPI server", "ai.ravenroot.extensions.openapi.server",
    ["openapi.receive", "openapi.request-reply"], "RAVENROOT_OPENAPI_SERVER_CONFIG", [], {
      authority: { ...authority, listenerId: "main", pathPrefix: "/managed/openapi", requiredScopes: ["graph:execute"],
        maxRoutes: 8, maxConcurrentRequests: 8, maxRequestBytes: 8192, maxResponseBytes: 1024, requestTimeoutMs: 2000 },
      projection: { allowedHeaders: ["content-type", "idempotency-key", "x-trace"], idempotencyHeader: "idempotency-key",
        maxRelativePathBytes: 1024, maxQueryParameters: 32, maxQueryBytes: 2048, maxHeaderCount: 8,
        maxHeaderBytes: 2048, maxHeaderValueBytes: 256 },
      profiles: { operations: { routeBase: "/api",
        specBase64: "eyJvcGVuYXBpIjoiMy4wLjMiLCJpbmZvIjp7InRpdGxlIjoiT3JkZXJzIiwidmVyc2lvbiI6IjEifSwicGF0aHMiOnsiL29yZGVycy97aWR9Ijp7InBvc3QiOnsib3BlcmF0aW9uSWQiOiJjcmVhdGVPcmRlciIsInBhcmFtZXRlcnMiOlt7Im5hbWUiOiJpZCIsImluIjoicGF0aCIsInJlcXVpcmVkIjp0cnVlLCJzY2hlbWEiOnsidHlwZSI6InN0cmluZyIsIm1heExlbmd0aCI6MzJ9fSx7Im5hbWUiOiJ2ZXJib3NlIiwiaW4iOiJxdWVyeSIsInNjaGVtYSI6eyJ0eXBlIjoiYm9vbGVhbiJ9fSx7Im5hbWUiOiJYLVRyYWNlIiwiaW4iOiJoZWFkZXIiLCJyZXF1aXJlZCI6dHJ1ZSwic2NoZW1hIjp7InR5cGUiOiJzdHJpbmciLCJtYXhMZW5ndGgiOjY0fX1dLCJyZXF1ZXN0Qm9keSI6eyJyZXF1aXJlZCI6dHJ1ZSwiY29udGVudCI6eyJhcHBsaWNhdGlvbi9qc29uIjp7InNjaGVtYSI6eyJ0eXBlIjoib2JqZWN0IiwicHJvcGVydGllcyI6eyJhbW91bnQiOnsidHlwZSI6ImludGVnZXIiLCJtaW5pbXVtIjoxfX0sInJlcXVpcmVkIjpbImFtb3VudCJdLCJhZGRpdGlvbmFsUHJvcGVydGllcyI6ZmFsc2V9fX19LCJyZXNwb25zZXMiOnsiMjAwIjp7ImRlc2NyaXB0aW9uIjoiY29tcGxldGVkIiwiaGVhZGVycyI6eyJyZXN1bHQtaWQiOnsic2NoZW1hIjp7InR5cGUiOiJzdHJpbmciLCJtYXhMZW5ndGgiOjY0fX19LCJjb250ZW50Ijp7ImFwcGxpY2F0aW9uL2pzb24iOnsic2NoZW1hIjp7InR5cGUiOiJvYmplY3QiLCJwcm9wZXJ0aWVzIjp7InJlc3VsdCI6eyJ0eXBlIjoic3RyaW5nIiwibWF4TGVuZ3RoIjo2NH19LCJyZXF1aXJlZCI6WyJyZXN1bHQiXSwiYWRkaXRpb25hbFByb3BlcnRpZXMiOmZhbHNlfX19fSwiMjAyIjp7ImRlc2NyaXB0aW9uIjoiYWNjZXB0ZWQifX19fSwiL29yZGVycy9zcGVjaWFsIjp7InBvc3QiOnsib3BlcmF0aW9uSWQiOiJzcGVjaWFsT3JkZXIiLCJyZXNwb25zZXMiOnsiMjAyIjp7ImRlc2NyaXB0aW9uIjoiYWNjZXB0ZWQifX19fX19Cg==", specSha256: "31a19b3851da4630beff77bf3675b3ac6bb12c203d3b7d5c44cc3a60ff662424",
        operations: ["createOrder", "specialOrder"], principalTypes: ["USER"], idempotencyHeader: "idempotency-key",
        maxRequestBytes: 4096, maxIdempotencyBytes: 128, deadlineMs: 1000, maxConcurrency: 2 } }
    }),
  delimitedContract("telegram.profile", "Telegram bot profile", "Telegram", "ai.ravenroot.extensions.telegram",
    ["telegram.send", "telegram.answer.callback", "telegram.edit.message", "telegram.delete.message"],
    "RAVENROOT_TELEGRAM_PROFILE_", [requiredString("credentialRef"), requiredCsv("allowedChats"),
      requiredCsv("allowedMethods"), requiredCsv("allowedButtonHosts"), requiredBoolean("businessConnectionAuthority"),
      requiredInt("maxConcurrency", 1, 16), requiredInt("requestsPerSecond", 1, 30), requiredInt("connectTimeoutMs", 100, 10000),
      requiredInt("requestTimeoutMs", 100, 30000), requiredInt("maxTextChars", 1, 4096),
      requiredInt("maxPhotoBytes", 1, 10000000), requiredInt("maxButtons", 0, 100), requiredInt("retries", 0, 3)],
    { credentials: "family" }),
  jsonContract("websocket.profile", "WebSocket profile", "WebSocket", "ai.ravenroot.extensions.websocket",
    ["websocket.send", "websocket.receive"], "RAVENROOT_WEBSOCKET_PROFILE_", ["profile"], {
      destination: "wss://socket.example.test/events", headers: {}, subprotocols: [], credentialBindingId: null,
      credentialReference: null, maximumMessageBytes: 65536, maximumFragments: 32, timeoutMs: 10000,
      reconnectBackoffMs: 1000, maxConcurrency: 4, maxBufferedEvents: 128
    }, ["outbound-websocket"]),
  jsonContract("discord.config", "Discord package configuration", "Discord", "ai.ravenroot.extensions.discord",
    ["discord.interactions", "discord.send"], "RAVENROOT_DISCORD_CONFIG", [], {
      authority: { ...authority, listenerId: "managed-main", pathPrefix: "/managed/discord", requiredScopes: ["discord:interactions"] },
      projection, store: { path: "/var/lib/ravenroot/discord-deliveries.db", maxDeliveries: 100000, retentionHours: 168 },
      profiles: { operations: { tenantId: "tenant-a", apiOrigin: "https://discord.com/api/v10",
        applicationId: "123456789012345678", publicKeyHex: "0".repeat(64), guilds: { "123456789012345678": ["223456789012345678"] }, commands: ["deploy"],
        credentialBindingId: "discord-bot", credentialReference: "discord-bot-token", route: "/interactions",
        limits: { requestTimeoutMs: 2000, maxRequestBytes: 1048576, maxResponseBytes: 65536,
          maxContentChars: 2000, maxAttachmentBytes: 1048576, maxAttachments: 4, maxConcurrency: 4,
          maxPerSecond: 20, retries: 2, signatureMaxAgeSeconds: 300, futureSkewSeconds: 30 } } }
    }, ["outbound-http"]),
  jsonContract("github.config", "GitHub automation configuration", "GitHub", "ai.ravenroot.extensions.github",
    ["github-events-source", "project-transition", "github-app-review", "github-workflow-watch", "release-prepare"],
    "RAVENROOT_GITHUB_CONFIG", [], {
      authority: { ...authority, pathPrefix: "/managed/github", requiredScopes: ["github:webhook"], requestTimeoutMs: 10000 }, projection,
      store: { path: "/var/lib/ravenroot/github-operations.db", maxOperations: 100000, retentionHours: 720, leaseMs: 30000 },
      profiles: { automation: { tenantId: "tenant-a", apiOrigin: "https://api.github.com", owner: "example",
        repository: "service", repositoryId: 1234, installationId: 5678, reviewerLogin: "reviewer[bot]",
        credentialBindingId: "github-installation", credentialReference: "github-token", webhookSecretReference: "github-webhook",
        route: "/automation", events: { pull_request: ["opened"] },
        project: { projectId: "PVT_example", statusFieldId: "PVTSSF_status", attemptsFieldId: "PVTF_attempts",
          generationFieldId: "PVTF_generation", statusOptions: { Todo: "todo-id", InProgress: "progress-id", Done: "done-id" },
          allowedTransitions: ["Todo->InProgress", "InProgress->Done"], claimTransition: "Todo->InProgress" },
        workflowIds: [1001], release: { branch: "main", versionPath: "ravenroot/pom.xml", fragmentsPath: ".changes",
          allowedKinds: ["none", "patch", "minor", "major"], maxFiles: 256 },
        limits: { timeoutMs: 10000, maxRequestBytes: 1048576, maxResponseBytes: 1048576,
          maxConcurrency: 8, maxPolls: 120, pollIntervalMs: 5000 } } }
    }, ["credential-resolution", "outbound-http"]),
  jsonContract("matrix.config", "Matrix configuration", "Matrix", "ai.ravenroot.extensions.matrix",
    ["matrix.send", "matrix.sync"], "RAVENROOT_MATRIX_CONFIG", [], {
      store: { path: "/var/lib/ravenroot/matrix-sync.db", maxDeliveries: 100000, retentionHours: 168, maxSources: 1000 },
      profiles: { operations: { tenantId: "tenant-a", homeserverOrigin: "https://matrix.example.org/",
        userId: "@ravenroot:example.org", rooms: ["!operations:example.org"], eventTypes: ["m.room.message"],
        credentialBindingId: "matrix-bearer", credentialReference: "matrix-token", initialSyncMode: "deliver-bounded",
        initialSince: "operator-reviewed-token", limits: { requestTimeoutMs: 35000, maxRequestBytes: 1048576,
          maxResponseBytes: 8388608, maxTextChars: 4000, maxConcurrency: 4, maxPerSecond: 20,
          pollTimeoutMs: 30000, retryBackoffMs: 1000, maxEventsPerSync: 100 } } }
    }, ["outbound-http"]),
  jsonContract("mattermost.config", "Mattermost configuration", "Mattermost", "ai.ravenroot.extensions.mattermost",
    ["mattermost.send", "mattermost.outgoing-webhook"], "RAVENROOT_MATTERMOST_CONFIG", [], {
      authority: { ...authority, pathPrefix: "/managed/mattermost", requiredScopes: ["mattermost:callbacks"] }, projection,
      store: { path: "/var/lib/ravenroot/mattermost-deliveries.db", maxDeliveries: 100000, retentionHours: 168 },
      profiles: { operations: { tenantId: "tenant-a", origin: "https://mattermost.example.com", teamId: "aaaaaaaaaaaaaaaaaaaaaaaaaa",
        publicChannels: ["bbbbbbbbbbbbbbbbbbbbbbbbbb"], credentialBindingId: "mattermost-bearer", credentialReference: "mattermost-token",
        webhookTokenReference: "mattermost-webhook", outgoingWebhookRoute: "/outgoing/operations",
        limits: { maxTextChars: 4000, maxRequestBytes: 1048576, maxResponseBytes: 65536,
          maxConcurrency: 4, maxPerSecond: 20, requestTimeoutMs: 2500, retries: 1 } } }
    }, ["credential-resolution", "outbound-http"]),
  jsonContract("slack.config", "Slack configuration", "Slack", "ai.ravenroot.extensions.slack",
    ["slack.events", "slack.commands", "slack.post-message"], "RAVENROOT_SLACK_CONFIG", [], {
      authority: { ...authority, maxRoutes: 8, pathPrefix: "/managed/slack", requiredScopes: ["slack:callbacks"] },
      projection: { ...projection, maxHeaderCount: 5 },
      store: { path: "/var/lib/ravenroot/slack-deliveries.db", maxDeliveries: 100000, retentionHours: 168 },
      profiles: { operations: { tenantId: "tenant-a", apiOrigin: "https://slack.com", teamId: "T01234567",
        applicationId: "A01234567", credentialBindingId: "slack-bot", credentialReference: "slack-token",
        signingSecretReference: "slack-signing", eventsRoute: "/events", commandsRoute: "/commands",
        channels: ["C01234567"], eventTypes: ["message"], commands: ["/deploy"], scopes: ["chat:write", "commands"],
        limits: { requestTimeoutMs: 2500, maxRequestBytes: 1048576, maxResponseBytes: 65536,
          maxTextChars: 4000, maxConcurrency: 4, maxPerSecond: 20, retries: 2, signatureMaxAgeSeconds: 300 } } }
    }, ["credential-resolution", "outbound-http"]),
  jsonContract("teams.config", "Microsoft Teams configuration", "Microsoft Teams", "ai.ravenroot.extensions.teams",
    ["teams.send", "teams.outgoing-webhook"], "RAVENROOT_TEAMS_CONFIG", [], {
      authority: { ...authority, pathPrefix: "/managed/teams", requiredScopes: ["teams:callbacks"], requestTimeoutMs: 4500 }, projection,
      store: { path: "/var/lib/ravenroot/teams-deliveries.db", maxDeliveries: 100000, retentionHours: 168 },
      profiles: { operations: { tenantId: "tenant-a", workflowEndpoint: "https://example.logic.azure.com/workflows/example",
        microsoftTenantId: "00000000-0000-4000-8000-000000000000", teamId: "19:team@thread.tacv2",
        channels: ["19:channel@thread.tacv2"], credentialBindingId: "teams-workflow", credentialReference: "teams-token",
        signingSecretReference: "teams-signing", webhookRoute: "/outgoing",
        limits: { requestTimeoutMs: 2500, maxRequestBytes: 1048576, maxResponseBytes: 65536,
          maxTextChars: 4000, maxConcurrency: 4, maxPerSecond: 20, ackTimeoutMs: 4000, signatureMaxAgeSeconds: 300 } } }
    }, ["credential-resolution", "outbound-http"]),
  {
    id: "core.http", title: "Built-in HTTP policy", family: "Built-in HTTP", nodeIds: ["http-request"], environment: "*",
    identity: [], encoding: "plain-environment", runtimeVerifier: "core.http", restartRequired: true, credentialResolver: "shared",
    fields: [
      { ...requiredCsv("RAVENROOT_HTTP_ALLOWED_HOSTS"), suggestion: ["api.example.test"] },
      { ...requiredCsv("RAVENROOT_HTTP_ALLOWED_PORTS"), suggestion: ["443"] },
      { ...optionalCsv("RAVENROOT_EGRESS_RESERVED_EXCEPTIONS"), suggestion: [] },
      { ...requiredInt("RAVENROOT_HTTP_MAX_REQUEST_BYTES"), suggestion: 65536 },
      { ...requiredInt("RAVENROOT_HTTP_MAX_RESPONSE_BYTES"), suggestion: 1048576 },
      { ...requiredCsv("RAVENROOT_ALLOWED_TOOLS"), suggestion: ["lookup"] }
    ]
  },
  {
    id: "core.program", title: "Built-in program runtime", family: "Built-in program", nodeIds: ["program"], environment: "*",
    identity: [], encoding: "plain-environment", runtimeVerifier: "core.program", restartRequired: true, credentialResolver: "none",
    externalRequirements: ["A configured sandbox supervisor is required for effectful program execution."],
    fields: [{ ...requiredString("RAVENROOT_PROGRAM_RUNTIME"), allowed: ["graalvm", "disabled"], suggestion: "graalvm" },
      { ...requiredString("RAVENROOT_GRAAL_SANDBOX_SUPERVISOR"), suggestion: "/usr/bin/true" },
      { ...requiredInt("RAVENROOT_PROGRAM_TIMEOUT_MS", 100, 300000), suggestion: 5000 },
      { ...requiredInt("RAVENROOT_PROGRAM_MAX_HEAP_MB", 32, 1024), suggestion: 64 },
      { ...requiredCsv("RAVENROOT_ALLOWED_TOOLS"), suggestion: ["program"] }]
  },
  {
    id: "core.human-task", title: "Human task registered presentation", family: "Built-in human task", nodeIds: ["human-task"],
    environment: "*", identity: [], encoding: "plain-environment", runtimeVerifier: "core.human-task", restartRequired: true, credentialResolver: "none",
    externalRequirements: ["The configurator installs the strict interaction document as a mounted operator-owned JSON file."],
    fields: [strictJson("interactionDocument", "Interaction registry", { schemaVersion: 1, capabilityTtlSeconds: 300, maxCompletionBytes: 65536,
        capabilitySecretBase64: { $secret: "human-task-capability" }, profiles: [{ id: "review", version: 1,
          kind: "EXTERNAL", launchUri: "https://review.example.test/task", origin: "https://review.example.test",
          completionSecretBase64: { $secret: "human-task-provider" } }] }),
      { name: "RAVENROOT_HUMAN_TASK_RESPONDER_ENFORCEMENT_ENABLED", label: "Responder enforcement", type: "boolean", required: true }]
  },
  {
    id: "core.publication-policies", title: "Publication boundary policies", family: "Built-in publication guard",
    nodeIds: ["boundary-guard"], environment: "*", identity: [], encoding: "plain-environment",
    runtimeVerifier: "core.publication-policies", restartRequired: true, credentialResolver: "none",
    externalRequirements: ["The configurator installs the immutable policy registry as a mounted operator-owned JSON file."],
    fields: [strictJson("policyDocument", "Publication policy registry", { schemaVersion: 1, policies: [{ id: "public", version: "v1", maxCandidateBytes: 1048576,
        rules: [{ type: "destination", id: "destination.approved", allowedTypes: ["repository"],
          allowedAddresses: ["public"] }, { type: "provenance", id: "provenance.complete", allowedSourceTypes: ["graph"] }] }] })]
  },
  {
    id: "core.runner", title: "Governed runner and workspace configuration", family: "Governed runner",
    nodeIds: ["agent", "workspace"], environment: "*", identity: [], encoding: "plain-environment",
    runtimeVerifier: "core.runner", restartRequired: true, credentialResolver: "shared",
    externalRequirements: ["The configured artifact directory and runner identity files must exist on the target."],
    fields: [strictJson("runnerDocument", "Runner control-plane configuration", { protocolVersion: 1, runnerIssuer: "https://identity.example.test",
        artifactDirectory: "/var/lib/ravenroot/runner-artifacts", tenants: { "tenant-a": {
          policy: { capabilities: ["WORKSPACE_READ"], tools: [], network: [], secrets: [], mounts: [], limits: {
            wallTime: "PT2M", memoryBytes: 134217728, processes: 32, workspaceBytes: 67108864,
            artifactBytes: 131072, logBytes: 8192, payloadBytes: 16384 } },
          definitions: [], runners: [], workspaceProfiles: []
        } } })]
  }
] as const;

export const CONTRACT_BY_ID = new Map(CONTRACTS.map((contract) => [contract.id, contract]));

function identityPart(selection: ConfigurationSelection, part: "tenant" | "profile" | "reference"): string {
  const value = selection.identity[part];
  if (!value) throw new Error(`${selection.contractId} requires ${part}`);
  return safeName(value, part);
}

export function environmentKey(contract: ConfigurationContract, selection: ConfigurationSelection): string {
  if (contract.environment === "*") throw new Error(`${contract.id} produces multiple environment keys`);
  if (contract.identity.length === 0) return contract.environment;
  const suffix = contract.identity.map((part) => utf8Hex(identityPart(selection, part))).join("_");
  return `${contract.environment}${suffix}`;
}

function scalar(field: FieldSpec, value: unknown): string {
  if (value === undefined || value === null || value === "") {
    if (field.required) throw new Error(`${field.label} is required`);
    return "";
  }
  if (field.type === "boolean") {
    if (typeof value !== "boolean") throw new Error(`${field.label} must be true or false`);
    return value ? "true" : "false";
  }
  if (field.type === "integer") {
    const number = typeof value === "number" ? value : Number(value);
    if (!Number.isSafeInteger(number)) throw new Error(`${field.label} must be an integer`);
    if (field.minimum !== undefined && number < field.minimum) throw new Error(`${field.label} must be at least ${field.minimum}`);
    if (field.maximum !== undefined && number > field.maximum) throw new Error(`${field.label} must be at most ${field.maximum}`);
    return String(number);
  }
  if (field.type === "csv") {
    const list = Array.isArray(value) ? value : String(value).split(",").map((item) => item.trim()).filter(Boolean);
    if (field.required && list.length === 0) throw new Error(`${field.label} requires at least one value`);
    if (list.some((item) => typeof item !== "string" || item.includes(",") || item.includes(";"))) {
      throw new Error(`${field.label} contains an invalid list item`);
    }
    return list.join(",");
  }
  if (field.type === "json") {
    const object = typeof value === "string" ? JSON.parse(value) : value;
    if (object === null || typeof object !== "object" || Array.isArray(object)) throw new Error(`${field.label} must be a JSON object`);
    if (field.schema) validateValue(field.schema, object, field.label);
    return JSON.stringify(object);
  }
  const string = String(value);
  if (string.includes(";")) throw new Error(`${field.label} cannot contain a semicolon`);
  if (field.allowed && !field.allowed.includes(string)) throw new Error(`${field.label} must be one of ${field.allowed.join(", ")}`);
  return string;
}

export function serializeSelection(selection: ConfigurationSelection): Readonly<Record<string, string>> {
  const contract = CONTRACT_BY_ID.get(selection.contractId);
  if (!contract) throw new Error(`Unknown configuration contract: ${selection.contractId}`);
  const identityKeys = Object.keys(selection.identity);
  if (identityKeys.some((key) => !contract.identity.includes(key as "tenant" | "profile" | "reference"))) throw new Error(`${contract.id} identity contains unsupported axes`);
  for (const axis of contract.identity) identityPart(selection, axis);

  const allowedValues = contract.encoding === "base64-json" ? new Set(["document"]) : new Set(contract.fields.map((field) => field.name));
  const unsupported = Object.keys(selection.values).filter((key) => !allowedValues.has(key));
  if (unsupported.length) throw new Error(`${contract.id} contains unsupported values: ${unsupported.join(", ")}`);

  if (contract.encoding === "plain-environment") {
    const result: Record<string, string> = {};
    for (const field of contract.fields) {
      scalar(field, selection.values[field.name]);
      if (field.name.startsWith("RAVENROOT_")) result[field.name] = scalar(field, selection.values[field.name]);
    }
    return result;
  }
  if (contract.encoding === "base64-json") {
    const document = selection.values.document;
    if (document === null || typeof document !== "object" || Array.isArray(document)) {
      throw new Error(`${contract.title} document must be a JSON object`);
    }
    if (!contract.schema) throw new Error(`${contract.id} has no executable schema`);
    validateValue(contract.schema, document, `${contract.id}.document`);
    return { [environmentKey(contract, selection)]: base64Json(document) };
  }
  const parts = contract.fields.map((field) => scalar(field, selection.values[field.name]));
  return { [environmentKey(contract, selection)]: parts.join(contract.delimiter ?? ";") };
}

export function templateSelection(contractId: string): ConfigurationSelection {
  const contract = CONTRACT_BY_ID.get(contractId);
  if (!contract) throw new Error(`Unknown configuration contract: ${contractId}`);
  const identity = Object.fromEntries(contract.identity.map((part) => [part, part === "tenant" ? "tenant-a" : "default"]));
  if (contract.encoding === "base64-json") {
    return { contractId, identity, values: { document: structuredClone(contract.jsonTemplate ?? {}) } };
  }
  const values: Record<string, unknown> = { ...(DELIMITED_TEMPLATES[contractId] ?? {}) };
  for (const field of contract.fields) {
    if (field.name in values) continue;
    values[field.name] = field.suggestion ?? field.runtimeDefault ?? (field.type === "boolean" ? false : field.type === "integer" ? field.minimum ?? 1
      : field.type === "csv" ? ["example"] : field.allowed?.[0] ?? "configured");
  }
  return { contractId, identity, values };
}
