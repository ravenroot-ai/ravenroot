import type { ValueSchema } from "./types.js";

const INT_MAX = 2_147_483_647;
const SAFE_LONG_MAX = Number.MAX_SAFE_INTEGER;
const IDENTIFIER = "[a-z][a-z0-9._-]{0,63}";
const POLICY_TOKEN = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
const RULE_ID = "[a-z][a-z0-9._-]{0,127}";
const CANONICAL_TOKEN = "[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}";

const integer = (minimum = 1, maximum = INT_MAX): ValueSchema => ({ kind: "integer", minimum, maximum });
const identifier = (): ValueSchema => ({ kind: "string", pattern: IDENTIFIER, minimumLength: 1, maximumLength: 64, nonBlank: true });
const duration = (): ValueSchema => ({ kind: "string", format: "duration", minimumLength: 3, maximumLength: 64, nonBlank: true });
const identifiers = (minimumItems = 0): ValueSchema => ({ kind: "array", items: identifier(), minimumItems, maximumItems: 128, unique: true });
const secretReference = (): ValueSchema => ({ kind: "object", properties: { $secret: identifier() } });

const runnerLimits: ValueSchema = {
  kind: "object",
  properties: {
    wallTime: duration(), memoryBytes: integer(1, SAFE_LONG_MAX), processes: integer(),
    workspaceBytes: integer(1, SAFE_LONG_MAX), artifactBytes: integer(1, SAFE_LONG_MAX),
    logBytes: integer(1, SAFE_LONG_MAX), payloadBytes: integer(1, 1_048_576)
  }
};

const runnerPolicy: ValueSchema = {
  kind: "object",
  properties: {
    capabilities: { kind: "array", items: { kind: "string", allowed: ["WORKSPACE_READ", "WORKSPACE_WRITE", "PROCESS_EXECUTE", "TOOL_CALL", "NETWORK_EGRESS", "SECRET_ACCESS", "ADDITIONAL_MOUNTS"] }, minimumItems: 0, maximumItems: 7, unique: true },
    tools: identifiers(), network: identifiers(), secrets: identifiers(), mounts: identifiers(), limits: runnerLimits
  }
};

const command: ValueSchema = {
  kind: "object",
  properties: {
    name: identifier(), readOnly: { kind: "boolean" }, policy: runnerPolicy,
    outcomes: identifiers(1)
  }
};

const definition: ValueSchema = {
  kind: "object",
  optional: ["budgets", "skillInstructions"],
  properties: {
    name: identifier(), version: integer(1, SAFE_LONG_MAX),
    instructions: { kind: "string", minimumLength: 1, maximumLength: 65_536, nonBlank: true },
    runtimeProfile: identifier(), modelProfile: identifier(),
    budgets: { kind: "object", properties: {
      modelTurns: integer(), toolCalls: integer(), modelTokens: integer(1, SAFE_LONG_MAX), tokensPerTurn: integer()
    } },
    skillInstructions: { kind: "map", values: { kind: "string", minimumLength: 1, maximumLength: 65_536, nonBlank: true }, maximumEntries: 128, keyPattern: IDENTIFIER },
    skills: identifiers(), runnerRequirements: identifiers(), policy: runnerPolicy,
    workspaceRetention: { kind: "string", pattern: "P(?:0D|(?=\\d|T\\d)(?:\\d+D)?(?:T(?=\\d)(?:\\d+H)?(?:\\d+M)?(?:\\d+(?:\\.\\d+)?S)?)?)", minimumLength: 3, maximumLength: 64 },
    outputSchema: identifier(), commands: { kind: "array", items: command, minimumItems: 1, maximumItems: 64, unique: true }
  }
};

const registration: ValueSchema = {
  kind: "object",
  properties: {
    protocolVersion: { kind: "integer", minimum: 1, maximum: 1 }, runnerId: identifier(), trustProfile: identifier(),
    labels: identifiers(), capabilities: runnerPolicy
  }
};

const capacity: ValueSchema = {
  kind: "object",
  properties: {
    mutatingUsers: integer(), readOnlyUsers: integer(), materializedWorkspaces: integer(),
    aggregateStorageBytes: integer(1, SAFE_LONG_MAX), queuedJobs: integer(), retainedJobs: integer(),
    admission: { kind: "string", allowed: ["QUEUE", "REJECT", "AUTOSCALE"] }
  }
};

const fleetCeiling: ValueSchema = {
  kind: "object",
  properties: {
    claimedJobs: integer(), queuedJobs: integer(), retainedWorkspaces: integer(), storageBytes: integer(1, SAFE_LONG_MAX)
  }
};

const fleetLimits: ValueSchema = {
  kind: "object",
  properties: {
    GLOBAL: fleetCeiling, POOL: fleetCeiling, WORKER: fleetCeiling, TENANT: fleetCeiling, PROFILE: fleetCeiling
  }
};

const workspaceProfile: ValueSchema = {
  kind: "object",
  optional: ["driver", "fleetLimits", "cpuMillicores"],
  properties: {
    name: identifier(), version: integer(1, SAFE_LONG_MAX),
    workspaceScope: { kind: "string", allowed: ["EPHEMERAL", "PROCESS_INSTANCE", "NAMED"] },
    runtimeLifecycle: { kind: "string", allowed: ["PER_INVOCATION", "PER_WORKSPACE"] },
    driver: { kind: "string", allowed: ["DOCKER", "KUBERNETES"] }, runnerPool: identifier(), runtimeProfile: identifier(),
    policy: runnerPolicy, capacity, fleetLimits, cpuMillicores: integer(),
    retention: { kind: "string", pattern: "P(?:0D|(?=\\d|T\\d)(?:\\d+D)?(?:T(?=\\d)(?:\\d+H)?(?:\\d+M)?(?:\\d+(?:\\.\\d+)?S)?)?)", minimumLength: 3, maximumLength: 64 },
    completionPolicy: { kind: "string", allowed: ["REQUIRE_CLOSED", "ABORT"] }, allowedAgents: identifiers(1)
  }
};

const publicationText = (maximumLength: number, pattern?: string): ValueSchema => ({
  kind: "string", minimumLength: 1, maximumLength, nonBlank: true, ...(pattern ? { pattern } : {})
});
const publicationStrings = (item: ValueSchema, minimumItems = 0, maximumItems = 32_768): ValueSchema => ({
  kind: "array", items: item, minimumItems, maximumItems, unique: true
});
const publicationRuleBase = { type: publicationText(32), id: publicationText(128, RULE_ID) } as const;

const publicationRule: ValueSchema = {
  kind: "union",
  choices: [
    { kind: "object", properties: { ...publicationRuleBase, type: { kind: "string", allowed: ["destination"] }, allowedTypes: publicationStrings(publicationText(128, CANONICAL_TOKEN)), allowedAddresses: publicationStrings(publicationText(2_048)) } },
    { kind: "object", properties: { ...publicationRuleBase, type: { kind: "string", allowed: ["logical-path"] }, privatePrefixes: publicationStrings(publicationText(2_048)), denyAbsolute: { kind: "boolean" }, denyParentTraversal: { kind: "boolean" }, denyHomeRelative: { kind: "boolean" } } },
    { kind: "object", properties: { ...publicationRuleBase, type: { kind: "string", allowed: ["sensitive-content"] }, kind: { kind: "string", allowed: ["SECRET", "CREDENTIAL", "PRIVATE_IDENTIFIER", "PRIVATE_REFERENCE"] }, signatures: { kind: "array", minimumItems: 1, maximumItems: 1_024, unique: true, items: { kind: "object", properties: { literal: publicationText(512), mode: { kind: "string", allowed: ["SUBSTRING", "TOKEN", "PREFIX"] } } } }, inspectEncodings: { kind: "boolean" }, joinFragments: { kind: "boolean" }, inspectConfusables: { kind: "boolean" }, maxNormalizedCharacters: integer(1, 16_777_216) } },
    { kind: "object", properties: { ...publicationRuleBase, type: { kind: "string", allowed: ["language"] }, allowedLanguages: publicationStrings(publicationText(63, "[a-z0-9]{1,8}(?:-[a-z0-9]{1,8})*")), allowSubtags: { kind: "boolean" } } },
    { kind: "object", properties: { ...publicationRuleBase, type: { kind: "string", allowed: ["artifact-type"] }, allowedTypes: publicationStrings(publicationText(128, CANONICAL_TOKEN)), allowBinary: { kind: "boolean" } } },
    { kind: "object", properties: { ...publicationRuleBase, type: { kind: "string", allowed: ["required-file-pair"] }, firstSuffix: publicationText(128), requiredSuffix: publicationText(128) } },
    { kind: "object", properties: { ...publicationRuleBase, type: { kind: "string", allowed: ["provenance"] }, allowedSourceTypes: publicationStrings(publicationText(128, CANONICAL_TOKEN)) } }
  ]
};

const externalHumanProfile: ValueSchema = {
  kind: "object",
  properties: {
    id: { kind: "string", minimumLength: 1, maximumLength: 256, nonBlank: true }, version: integer(),
    kind: { kind: "string", allowed: ["EXTERNAL"] },
    launchUri: { kind: "string", format: "uri", schemes: ["http", "https"], requireHost: true, allowFragment: false, maximumLength: 2_048 },
    origin: { kind: "string", format: "uri", schemes: ["http", "https"], requireHost: true, allowFragment: false, authorityOnly: true, maximumLength: 2_048 },
    completionSecretBase64: secretReference()
  }
};
const customHumanProfile: ValueSchema = {
  kind: "object",
  properties: {
    id: { kind: "string", minimumLength: 1, maximumLength: 256, nonBlank: true }, version: integer(),
    kind: { kind: "string", allowed: ["CUSTOM"] },
    launchUri: { kind: "string", format: "uri", schemes: ["http", "https"], requireHost: true, allowFragment: false, maximumLength: 2_048 },
    origin: { kind: "string", format: "uri", schemes: ["http", "https"], requireHost: true, allowFragment: false, authorityOnly: true, maximumLength: 2_048 }
  }
};

export const CORE_CONTRACT_SCHEMAS = {
  "core.human-task:interactionDocument": {
    kind: "object",
    properties: {
      schemaVersion: { kind: "integer", minimum: 1, maximum: 1 }, capabilityTtlSeconds: integer(1, 1_800),
      maxCompletionBytes: integer(1_024, 1_048_576), capabilitySecretBase64: secretReference(),
      profiles: { kind: "array", items: { kind: "union", choices: [externalHumanProfile, customHumanProfile] }, minimumItems: 1, maximumItems: 64, unique: true }
    }
  },
  "core.publication-policies:policyDocument": {
    kind: "object",
    properties: {
      schemaVersion: { kind: "integer", minimum: 1, maximum: 1 },
      policies: { kind: "array", minimumItems: 1, maximumItems: 1_024, unique: true, items: {
        kind: "object", properties: {
          id: publicationText(128, POLICY_TOKEN), version: publicationText(128, POLICY_TOKEN),
          maxCandidateBytes: integer(1, 16_777_216), rules: { kind: "array", items: publicationRule, minimumItems: 1, maximumItems: 4_096, unique: true }
        }
      } }
    }
  },
  "core.runner:runnerDocument": {
    kind: "object",
    optional: ["control"],
    properties: {
      protocolVersion: { kind: "integer", minimum: 1, maximum: 1 },
      runnerIssuer: { kind: "string", minimumLength: 0, maximumLength: 131_072 },
      artifactDirectory: { kind: "string", minimumLength: 0, maximumLength: 131_072 },
      control: { kind: "object", optional: ["continuationThreads", "continuationQueue", "recoveryPageSize", "recoveryInterval", "continuationLease", "nodeTimeout"], properties: {
        continuationThreads: integer(), continuationQueue: integer(), recoveryPageSize: integer(),
        recoveryInterval: duration(), continuationLease: duration(), nodeTimeout: duration()
      } },
      tenants: { kind: "map", minimumEntries: 1, maximumEntries: 1_024, keyPattern: ".{1,256}", values: {
        kind: "object", optional: ["workspaceProfiles"], properties: {
          policy: runnerPolicy, definitions: { kind: "array", items: definition, minimumItems: 0, maximumItems: 1_024, unique: true },
          runners: { kind: "array", items: registration, minimumItems: 0, maximumItems: 1_024, unique: true },
          workspaceProfiles: { kind: "array", items: workspaceProfile, minimumItems: 0, maximumItems: 1_024, unique: true }
        }
      } }
    }
  }
} as const satisfies Readonly<Record<string, ValueSchema>>;
