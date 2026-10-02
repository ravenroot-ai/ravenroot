import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { parse } from "yaml";
import { readBundleDirectory } from "../src/bundle-directory.js";
import { sha256, utf8 } from "../src/codec.js";
import { applyInstall, prepareInstall, rollback, verifyTarget } from "../src/installer.js";
import { createPackage, inspectPackage, type PackageInput } from "../src/package-format.js";

const component = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const work = await mkdtemp(join(tmpdir(), "ravenroot-amqp-example-"));
const target = join(work, "target");
const bundle = join(work, "prebuilt-amqp091");
await mkdir(target);
await mkdir(bundle);
const baseCompose = "services:\n  ravenroot:\n    image: ravenroot:example\n    environment:\n      UNRELATED_SETTING: retained\n  metrics:\n    image: metrics:example\n";
await writeFile(join(target, "compose.yaml"), baseCompose);
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
let recreations = 0;
const commandRunner = async (command: readonly string[]): Promise<void> => {
  if (command[0] !== "docker" || !command.includes("up")) throw new Error(`unexpected mutation command ${command.join(" ")}`);
  recreations += 1;
  if (recreations === 1) await readFile(join(target, ".ravenroot-config/compose/plugins/ai.ravenroot.extensions.amqp091/bundle.jar"));
};
const effectiveEnvironment = { ...first.environment, RAVENROOT_AMQP091_CREDENTIAL_62726F6B6572: "sanitized-target-secret" };
const queryRunner = async (command: readonly string[]): Promise<string> => {
  if (command.includes("config")) return JSON.stringify({ services: { ravenroot: { environment: effectiveEnvironment }, metrics: { image: "metrics:example" } } });
  if (command.includes("ps")) return "ravenroot-example-1\n";
  if (command[1] === "inspect") return JSON.stringify([{ Config: { Env: Object.entries(effectiveEnvironment).map(([key, value]) => `${key}=${value}`) }, State: { Running: true, Health: { Status: "healthy" } } }]);
  throw new Error(`unexpected verification command ${command.join(" ")}`);
};
await applyInstall(first, { targetRoot: target, execute: true, commandRunner, queryRunner });
if (recreations !== 1) throw new Error("Compose target was not recreated exactly once");
if (await readFile(join(target, "compose.yaml"), "utf8") !== baseCompose) throw new Error("base Compose services or settings changed");
const overlay = parse(await readFile(join(target, ".ravenroot-config/compose/compose.rrcfg.yaml"), "utf8"));
if (overlay.services.ravenroot.environment.RAVENROOT_AMQP091_CREDENTIAL_62726F6B6572 !== "${RABBITMQ_PASSWORD}") {
  throw new Error("target-side credential reference was not preserved");
}
const second = await prepareInstall(decoded, { targetRoot: target });
if (second.plan.changes.some((change) => change.action !== "keep")) throw new Error("reapply was not idempotent");
const server = createServer((request, response) => {
  response.setHeader("content-type", "application/json");
  if (request.url === "/ready") response.end('{"status":"ready"}');
  else if (request.url === "/v1/node-types") response.end('{"nodes":[{"behavior":"amqp.publish"},{"behavior":"amqp.consume"}]}');
  else { response.statusCode = 404; response.end('{}'); }
});
await new Promise<void>((resolvePromise) => server.listen(0, "127.0.0.1", resolvePromise));
try {
  const address = server.address(); if (!address || typeof address === "string") throw new Error("verification server did not bind");
  await verifyTarget(decoded, `http://127.0.0.1:${address.port}`);
} finally { await new Promise<void>((resolvePromise, reject) => server.close((failure) => failure ? reject(failure) : resolvePromise())); }
await rollback(target, commandRunner);
if (recreations !== 2) throw new Error("rollback did not recreate the restored Compose target");
process.stdout.write(`AMQP example passed: package=${packaged.digest} changes=${first.plan.changes.length} secret=target-reference activation=recreated runtime=inspected readiness=verified rollback=compensated\n`);
