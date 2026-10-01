import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parse } from "yaml";
import { describe, expect, test } from "vitest";
import { applyInstall, prepareInstall, rollback } from "../src/installer.js";
import { createPackage, inspectPackage } from "../src/package-format.js";
import { coreHttp, fakeBundle, serviceGrant, target } from "./helpers.js";

async function root(): Promise<string> { return mkdtemp(join(tmpdir(), "ravenroot-configurator-")); }

describe("target adapters", () => {
  test("applies Compose additively, is idempotent, refuses drift, and rolls back", async () => {
    const directory = await root();
    await writeFile(join(directory, "compose.yaml"), "services:\n  ravenroot:\n    image: ravenroot:test\n");
    const pkg = await inspectPackage((await createPackage({ target: target("compose"), configurations: [coreHttp], secrets: [], bundles: [] })).bytes);
    const first = await prepareInstall(pkg, { targetRoot: directory });
    expect(first.plan.conflicts).toEqual([]);
    await applyInstall(first, { targetRoot: directory });
    const second = await prepareInstall(pkg, { targetRoot: directory });
    expect(second.plan.changes.every((change) => change.action === "keep")).toBe(true);
    await writeFile(join(directory, ".ravenroot-config/compose/compose.rrcfg.yaml"), "operator drift\n");
    expect((await prepareInstall(pkg, { targetRoot: directory })).plan.conflicts).toHaveLength(1);
    await rollback(directory);
    await expect(readFile(join(directory, ".ravenroot-config/compose/compose.rrcfg.yaml"))).rejects.toThrow();
  });

  test("materializes secret placeholders only after decryption", async () => {
    const directory = await root();
    await writeFile(join(directory, "compose.yaml"), "services:\n  ravenroot:\n    image: ravenroot:test\n");
    const document = { schemaVersion: 1, capabilityTtlSeconds: 60, maxCompletionBytes: 4096,
      capabilitySecretBase64: { $secret: "capability" }, profiles: [] };
    const pkg = await inspectPackage((await createPackage({ target: target("compose"),
      configurations: [{ contractId: "core.human-task", identity: {}, values: { interactionDocument: document,
        RAVENROOT_HUMAN_TASK_RESPONDER_ENFORCEMENT_ENABLED: true } }],
      password: "package-password", secrets: [{ mode: "embedded", bindingId: "capability",
        environmentKey: "RAVENROOT_UNUSED_DOCUMENT_SECRET", value: "c2VjcmV0LW1hdGVyaWFs" }], bundles: [] })).bytes);
    const prepared = await prepareInstall(pkg, { targetRoot: directory, password: "package-password" });
    await applyInstall(prepared, { targetRoot: directory });
    const installed = await readFile(join(directory, ".ravenroot-config/compose/config/human-task-interactions.json"), "utf8");
    expect(installed).toContain("c2VjcmV0LW1hdGVyaWFs");
    expect(installed).not.toContain("$secret");
  });

  test("generates a Kubernetes Secret, chart values, and derived bundle image", async () => {
    const directory = await root();
    const bundle = await fakeBundle();
    const pkg = await inspectPackage((await createPackage({ target: target("kubernetes", {
      namespace: "ravenroot", release: "ravenroot", baseImage: "ravenroot@sha256:" + "1".repeat(64),
      derivedImage: "registry.example.test/ravenroot-configured:2026-10-02"
    }), configurations: [{ contractId: "ai.llm-profile", identity: { profile: "default" }, values: { document: { endpoint: "https://model.example.test", model: "bounded" } } },
      serviceGrant("ai.ravenroot.extensions.ai", ["outbound-http", "tool-authorization", "agent-resources"])],
    secrets: [], bundles: [bundle] })).bytes);
    await applyInstall(await prepareInstall(pkg, { targetRoot: directory }), { targetRoot: directory });
    const values = parse(await readFile(join(directory, ".ravenroot-config/kubernetes/values.rrcfg.yaml"), "utf8"));
    expect(values.image).toMatchObject({ repository: "registry.example.test/ravenroot-configured", tag: "2026-10-02" });
    const deploymentPatch = parse(await readFile(join(directory, ".ravenroot-config/kubernetes/deployment-patch.rrcfg.yaml"), "utf8"));
    expect(deploymentPatch).toMatchObject({ metadata: { name: "ravenroot-ravenroot", namespace: "ravenroot" } });
    expect(deploymentPatch.spec.template.spec.containers[0].name).toBe("ravenroot");
    expect(await readFile(join(directory, ".ravenroot-config/kubernetes/bundle-image/plugins/ai.ravenroot.extensions.ai/bundle.jar"), "utf8"))
      .toBe("closed prebuilt bundle bytes");
  });

  test("restores the prior transaction when restart fails", async () => {
    const directory = await root();
    await mkdir(join(directory, ".ravenroot-config/prestart"), { recursive: true });
    await writeFile(join(directory, ".ravenroot-config/prestart/environment.sh"), "original\n");
    const pkg = await inspectPackage((await createPackage({ target: target("prestart", { restartCommandJson: "[\"service\",\"restart\"]" }), configurations: [coreHttp], secrets: [], bundles: [] })).bytes);
    const prepared = await prepareInstall(pkg, { targetRoot: directory, replace: true });
    await expect(applyInstall(prepared, { targetRoot: directory, replace: true, execute: true,
      commandRunner: async () => { throw new Error("restart refused"); } })).rejects.toThrow("restart refused");
    expect(await readFile(join(directory, ".ravenroot-config/prestart/environment.sh"), "utf8")).toBe("original\n");
  });

  test("points pre-start activation at the staged closed bundle directory", async () => {
    const directory = await root();
    const pkg = await inspectPackage((await createPackage({ target: target("prestart"),
      configurations: [
        { contractId: "ai.llm-profile", identity: { profile: "default" }, values: { document: { endpoint: "https://model.example.test", model: "bounded" } } },
        serviceGrant("ai.ravenroot.extensions.ai", ["outbound-http", "tool-authorization", "agent-resources"])
      ], secrets: [], bundles: [await fakeBundle()] })).bytes);
    await applyInstall(await prepareInstall(pkg, { targetRoot: directory }), { targetRoot: directory });
    const environment = await readFile(join(directory, ".ravenroot-config/prestart/environment.sh"), "utf8");
    expect(environment).toContain(`RAVENROOT_PLUGINS_INSTALL_DIR='${join(directory, ".ravenroot-config/prestart/plugins")}'`);
    expect(await readFile(join(directory, ".ravenroot-config/prestart/plugins/ai.ravenroot.extensions.ai/bundle.jar"), "utf8"))
      .toBe("closed prebuilt bundle bytes");
  });
});
