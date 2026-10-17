import { describe, expect, test } from "vitest";
import { readFile } from "node:fs/promises";
import { CONTRACT_BY_ID, serializeSelection, templateSelection } from "../src/registry.js";
import { validateValue } from "../src/schema.js";
import { BUNDLE_COVERAGE, NODE_COVERAGE } from "../src/inventory.js";

const template = () => templateSelection("system-one.profile");
const defaults = () => template().values.document as Record<string, unknown>;
const accepts = (document: Record<string, unknown>) => serializeSelection({ ...template(), values: { document } });

describe("System One operator contract", () => {
  test("maps exact published identities and Java field vocabulary", async () => {
    const contract = CONTRACT_BY_ID.get("system-one.profile")!;
    expect(contract.bundleId).toBe("ai.ravenroot.extensions.systemone");
    expect(contract.nodeIds).toEqual(["system-one-evaluate"]);
    expect(contract.environment).toBe("RAVENROOT_SYSTEM_ONE_PROFILE_");
    expect(contract.identity).toEqual(["profile"]);
    expect(contract.requiredCapabilities).toEqual(["outbound-http"]);
    expect(contract.credentialResolver).toBe("shared");
    expect(NODE_COVERAGE.find((row) => row.nodeId === "system-one-evaluate")?.contracts).toEqual([contract.id]);
    expect(BUNDLE_COVERAGE.find((row) => row.manifestId === contract.bundleId)?.contracts).toEqual([contract.id]);
    const source = await readFile("../ravenroot/ravenroot-extensions/ravenroot-system-one/src/main/java/ai/ravenroot/extensions/systemone/EnvironmentSystemOneProfileResolver.java", "utf8");
    const fields = [...source.match(/FIELDS = Set\.of\(([\s\S]*?)\);/)![1]!.matchAll(/"([^"]+)"/g)].map((match) => match[1]).sort();
    if (contract.schema?.kind !== "object") throw new Error("missing object schema");
    expect(Object.keys(contract.schema.properties).sort()).toEqual(fields);
  });
  test("preserves fractional default, pinned Kev and inclusive tolerance boundaries", () => {
    for (const value of [0, 0.001, 0.02, 0.05]) {
      const document = { ...defaults(), probabilityTolerance: value };
      const serialized = accepts(document);
      expect(Object.keys(serialized)).toEqual(["RAVENROOT_SYSTEM_ONE_PROFILE_64656661756C74"]);
      const encoded = Object.values(serialized)[0]!;
      expect(JSON.parse(Buffer.from(encoded, "base64").toString("utf8"))).toEqual(document);
      expect(Buffer.from(encoded, "base64").toString("base64")).toBe(encoded);
    }
    for (const value of [-Number.EPSILON, 0.050000001, NaN, Infinity, -Infinity, "0.02", true]) {
      expect(() => accepts({ ...defaults(), probabilityTolerance: value })).toThrow();
    }
    expect(() => validateValue({ kind: "integer", minimum: 0, maximum: 1 }, 0.02)).toThrow("integer");
  });
  test("validates effective cross-field limits including omitted and null defaults", () => {
    const minimal = { endpoint: "https://system-one.example.test/v1/systemone", model: "pinned-model" };
    expect(() => accepts(minimal)).not.toThrow();
    expect(() => accepts({ ...minimal, probabilityTolerance: null, supportedTypes: null })).not.toThrow();
    for (const field of ["maxStateBytes", "maxInstructionBytes"]) {
      expect(() => accepts({ ...minimal, [field]: 1048577 })).toThrow("exceeds maxRequestBytes");
      const bounded: Record<string, unknown> = { ...minimal, maxRequestBytes: 1, maxStateBytes: 1, maxInstructionBytes: 1 };
      expect(() => accepts(bounded)).not.toThrow();
      delete bounded[field]; expect(() => accepts(bounded)).toThrow("exceeds maxRequestBytes");
      bounded[field] = null; expect(() => accepts(bounded)).toThrow("exceeds maxRequestBytes");
    }
  });
  test("enforces integer ceilings and primitive and credential boundaries", () => {
    const bounds = { timeoutMs: [1, 600000], maxRequestBytes: [1, 67108864], maxResponseBytes: [1, 67108864],
      maxConcurrency: [1, 1024], maxStateBytes: [1, 67108864], maxQuestions: [1, 1000], maxInstructionBytes: [1, 67108864],
      maxChoiceOptions: [1, 255], maxScoreLevels: [2, 255] };
    for (const [field, [minimum, maximum]] of Object.entries(bounds)) {
      for (const value of [minimum! - 1, maximum! + 1, 1.5, "2", true]) expect(() => accepts({ ...defaults(), [field]: value }), field).toThrow();
      for (const value of [minimum, maximum]) expect(() => accepts({ ...defaults(), maxRequestBytes: 67108864,
        maxStateBytes: 1, maxInstructionBytes: 1, [field]: value }), field).not.toThrow();
    }
    for (const supportedTypes of [[], ["numerical"], [1], "choice", Array(65).fill("choice")]) expect(() => accepts({ ...defaults(), supportedTypes })).toThrow();
    expect(() => accepts({ ...defaults(), supportedTypes: ["CHOICE", "noul", "choice"], maxChoiceOptions: 26 })).not.toThrow();
    for (const credentialBindingId of [null, "", "bad\u0001token", "x".repeat(257), false]) expect(() => accepts({ ...defaults(), credentialBindingId })).toThrow();
    expect(() => accepts({ ...defaults(), credentialBindingId: null, credentialReference: null })).not.toThrow();
    expect(() => accepts({ ...defaults(), credentialBindingId: "\u00a0", credentialReference: "" })).toThrow("supplied together");
    for (const field of ["credentialBindingId", "credentialReference"]) {
      const partial = { ...defaults() }; delete partial[field]; expect(() => accepts(partial)).toThrow("supplied together");
    }
  });
  test("rejects unknown fields and Java-incompatible endpoint/model forms", () => {
    for (const endpoint of ["ftp://service.example.test/v1", "https://user@service.example.test/v1", "https://@service.example.test/v1",
      "https://service.example.test/v1#", "https://example.123/v1", "https://127.1/v1", "https://example.test/[abc]", "https://invalid_host/v1", "https://service.example.test/a b", "https://service.example.test/%zz",
      "http://service.example.test/v1", "/v1/systemone", null, 42]) expect(() => accepts({ ...defaults(), endpoint })).toThrow();
    for (const model of ["", "   ", "\u001c", "x".repeat(257), null, 42]) expect(() => accepts({ ...defaults(), model })).toThrow();
    for (const key of ["unsupportedByRuntime", "constructor", "toString", "__proto__"]) expect(() => accepts({ ...defaults(), [key]: true })).toThrow("not supported");
    expect(() => accepts({ ...defaults(), endpoint: "http://[::1]:8080/v1/systemone", credentialBindingId: "", credentialReference: "" })).not.toThrow();
  });
});
