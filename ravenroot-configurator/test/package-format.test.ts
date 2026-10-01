import { unzipSync, zipSync } from "fflate";
import { describe, expect, test } from "vitest";
import { createPackage, inspectPackage } from "../src/package-format.js";
import { coreHttp, fakeBundle, serviceGrant, target } from "./helpers.js";

describe("portable package", () => {
  test("encrypts embedded secrets and verifies every declared entry", async () => {
    const secret = "correct horse battery staple";
    const created = await createPackage({
      target: target("compose"), configurations: [coreHttp], bundles: [], password: "package-password",
      secrets: [{ mode: "embedded", bindingId: "api-token", environmentKey: "RAVENROOT_CREDENTIAL_API", value: secret }],
      createdAt: "2026-01-01T00:00:00.000Z"
    });
    expect(Buffer.from(created.bytes).includes(Buffer.from(secret))).toBe(false);
    expect((await inspectPackage(created.bytes)).digest).toBe(created.digest);

    const entries = unzipSync(created.bytes);
    const configurations = entries["configurations.json"] as Uint8Array;
    configurations[0] = (configurations[0] as number) ^ 1;
    await expect(inspectPackage(zipSync(entries))).rejects.toThrow("integrity verification");
  });

  test("requires exactly the prebuilt bundles selected by contracts", async () => {
    const selection = { contractId: "ai.llm-profile", identity: { profile: "default" }, values: { document: { endpoint: "https://models.example.test", model: "m" } } };
    await expect(createPackage({ target: target("compose"), configurations: [selection], secrets: [], bundles: [] }))
      .rejects.toThrow("requires prebuilt bundles");
    await expect(createPackage({ target: target("compose"), configurations: [selection], secrets: [], bundles: [await fakeBundle()] }))
      .rejects.toThrow("requires managed-service grants");
    await expect(createPackage({ target: target("compose"),
      configurations: [selection, serviceGrant("ai.ravenroot.extensions.ai", ["outbound-http"])],
      secrets: [], bundles: [await fakeBundle()] })).rejects.toThrow("omits required capabilities");
    const created = await createPackage({ target: target("compose"),
      configurations: [selection, serviceGrant("ai.ravenroot.extensions.ai", ["outbound-http", "tool-authorization", "agent-resources"])],
      secrets: [], bundles: [await fakeBundle()] });
    expect(created.manifest.bundles).toHaveLength(1);
  });

  test("rejects configuration tenants outside the bound target", async () => {
    const selection = { contractId: "jdbc.profile", identity: { tenant: "other", profile: "default" }, values: { document: {} } };
    await expect(createPackage({ target: target("compose"), configurations: [selection], secrets: [], bundles: [await fakeBundle("ai.ravenroot.extensions.jdbc")] }))
      .rejects.toThrow("outside the target tenant");
  });

  test("requires encrypted placeholders for credential material in JSON documents", async () => {
    const raw = { contractId: "core.human-task", identity: {}, values: { interactionDocument: {
      schemaVersion: 1, capabilitySecretBase64: "raw-secret", profiles: []
    }, RAVENROOT_HUMAN_TASK_RESPONDER_ENFORCEMENT_ENABLED: true } };
    await expect(createPackage({ target: target("compose"), configurations: [raw], secrets: [], bundles: [] }))
      .rejects.toThrow("is sensitive and must use an encrypted");
    const protectedSelection = { ...raw, values: { ...raw.values, interactionDocument: {
      ...raw.values.interactionDocument, capabilitySecretBase64: { $secret: "capability" }
    } } };
    await expect(createPackage({ target: target("compose"), configurations: [protectedSelection], secrets: [], bundles: [] }))
      .rejects.toThrow("unavailable encrypted secret binding");
    const created = await createPackage({ target: target("compose"), configurations: [protectedSelection],
      password: "package-password", secrets: [{ mode: "embedded", bindingId: "capability",
        environmentKey: "RAVENROOT_UNUSED_DOCUMENT_SECRET", value: "secret-material" }], bundles: [] });
    expect(Buffer.from(created.bytes).includes(Buffer.from("secret-material"))).toBe(false);
  });

  test("requires concrete target-side secret coordinates", async () => {
    await expect(createPackage({ target: target("kubernetes"), configurations: [coreHttp], bundles: [],
      secrets: [{ mode: "target-reference", bindingId: "token", environmentKey: "RAVENROOT_CREDENTIAL_TOKEN" }] }))
      .rejects.toThrow("requires kubernetesSecret and kubernetesKey");
    await expect(createPackage({ target: target("compose"), configurations: [coreHttp], bundles: [],
      secrets: [{ mode: "target-reference", bindingId: "token", environmentKey: "RAVENROOT_CREDENTIAL_TOKEN" }] }))
      .rejects.toThrow("requires composeVariable");
  });
});
