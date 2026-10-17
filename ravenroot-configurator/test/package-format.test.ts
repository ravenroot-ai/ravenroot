import { unzipSync, zipSync } from "fflate";
import { describe, expect, test } from "vitest";
import { createPackage, inspectPackage } from "../src/package-format.js";
import { templateSelection } from "../src/registry.js";
import { coreHttp, fakeBundle, serviceGrant, target } from "./helpers.js";

describe("portable package", () => {
  async function tamperedPackage(mutate: (manifest: Record<string, any>) => void): Promise<Uint8Array> {
    const created = await createPackage({ target: target("compose"), configurations: [coreHttp], secrets: [], bundles: [] });
    const entries = unzipSync(created.bytes);
    const manifest = JSON.parse(Buffer.from(entries["manifest.json"]!).toString("utf8")) as Record<string, any>;
    mutate(manifest); entries["manifest.json"] = Buffer.from(JSON.stringify(manifest)); return zipSync(entries);
  }

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
    const selection = templateSelection("ai.llm-profile");
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
      schemaVersion: 1, capabilityTtlSeconds: 300, maxCompletionBytes: 65536, capabilitySecretBase64: "raw-secret",
      profiles: [{ id: "review", version: 1, kind: "EXTERNAL", launchUri: "https://review.example.test/task",
        origin: "https://review.example.test", completionSecretBase64: { $secret: "capability" } }]
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

  test("rejects semantically tampered target, identity, and plan fields during import", async () => {
    await expect(inspectPackage(await tamperedPackage((manifest) => { manifest.target.kind = "nomad"; }))).rejects.toThrow("Unknown target kind");
    await expect(inspectPackage(await tamperedPackage((manifest) => { manifest.target.options.unreviewed = "value"; }))).rejects.toThrow("unsupported fields");
    await expect(inspectPackage(await tamperedPackage((manifest) => { manifest.configurations[0].identity.profile = "foreign"; }))).rejects.toThrow("unsupported fields");
    await expect(inspectPackage(await tamperedPackage((manifest) => { manifest.plan.targetId = "another-target"; }))).rejects.toThrow("not bound to its target");
    await expect(inspectPackage(await tamperedPackage((manifest) => { manifest.plan.changes[0].action = "overwrite"; }))).rejects.toThrow("plan change is invalid");
  });

  test("requires pinned single-line OCI references on export and import", async () => {
    const digest = `sha256:${"1".repeat(64)}`;
    await expect(createPackage({ target: target("kubernetes", { baseImage: "ravenroot:latest", derivedImage: "registry.example.test/ravenroot:configured" }),
      configurations: [coreHttp], secrets: [], bundles: [] })).rejects.toThrow("pinned by a sha256 digest");
    for (const hostile of ["ravenroot@" + digest + "\nRUN id", "ravenroot@" + digest + " RUN id", "ravenroot@sha256:" + "A".repeat(64)]) {
      await expect(createPackage({ target: target("kubernetes", { baseImage: hostile, derivedImage: "registry.example.test/ravenroot:configured" }),
        configurations: [coreHttp], secrets: [], bundles: [] })).rejects.toThrow("single-line OCI image reference");
    }
    await expect(inspectPackage(await tamperedPackage((manifest) => {
      manifest.target.kind = "kubernetes";
      manifest.target.options = { baseImage: `ravenroot@${digest}\nUSER 0`, derivedImage: "registry.example.test/ravenroot:configured" };
      manifest.plan.targetKind = "kubernetes";
    }))).rejects.toThrow("single-line OCI image reference");
  });
});
