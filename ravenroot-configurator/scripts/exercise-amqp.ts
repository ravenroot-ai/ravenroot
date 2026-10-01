import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "yaml";
import { readBundleDirectory } from "../src/bundle-directory.js";
import { sha256, utf8 } from "../src/codec.js";
import { applyInstall, prepareInstall, rollback } from "../src/installer.js";
import { createPackage, inspectPackage, type PackageInput } from "../src/package-format.js";

const component = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const work = await mkdtemp(join(tmpdir(), "ravenroot-amqp-example-"));
const target = join(work, "target");
const bundle = join(work, "prebuilt-amqp091");
await mkdir(target);
await mkdir(bundle);
await writeFile(join(target, "compose.yaml"), "services:\n  ravenroot:\n    image: ravenroot:example\n");
const jar = utf8("sanitized closed AMQP bundle fixture");
await writeFile(join(bundle, "bundle.jar"), jar);
await writeFile(join(bundle, "ravenroot-plugin.json"), JSON.stringify({
  schemaVersion: "1", id: "ai.ravenroot.extensions.amqp091", version: "example",
  sdkContract: "ravenroot.node-sdk/2", nodePackageClasses: ["example.AmqpPackage"],
  behaviors: ["amqp.publish", "amqp.consume"],
  mainArtifact: { fileName: "bundle.jar", sha256: await sha256(jar), sizeBytes: jar.byteLength },
  dependencyArtifacts: []
}));

const spec = JSON.parse(await readFile(join(component, "examples/amqp/package-spec.json"), "utf8")) as Omit<PackageInput, "bundles"> & { bundleDirectories: string[] };
const packaged = await createPackage({ ...spec, bundles: [await readBundleDirectory(bundle)] });
const decoded = await inspectPackage(packaged.bytes);
const first = await prepareInstall(decoded, { targetRoot: target });
if (first.plan.conflicts.length) throw new Error(first.plan.conflicts.join("\n"));
await applyInstall(first, { targetRoot: target });
const overlay = parse(await readFile(join(target, ".ravenroot-config/compose/compose.rrcfg.yaml"), "utf8"));
if (overlay.services.ravenroot.environment.RAVENROOT_AMQP091_CREDENTIAL_62726F6B6572 !== "${RABBITMQ_PASSWORD}") {
  throw new Error("target-side credential reference was not preserved");
}
const second = await prepareInstall(decoded, { targetRoot: target });
if (second.plan.changes.some((change) => change.action !== "keep")) throw new Error("reapply was not idempotent");
await rollback(target);
process.stdout.write(`AMQP example passed: package=${packaged.digest} changes=${first.plan.changes.length} secret=target-reference rollback=complete\n`);
