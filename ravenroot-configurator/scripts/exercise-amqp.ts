import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { parse } from "yaml";
import { readBundleDirectory } from "../src/bundle-directory.js";
import { applyInstall, prepareInstall, rollback, verifyTarget } from "../src/installer.js";
import { createPackage, inspectPackage, type PackageInput } from "../src/package-format.js";

const runFile = promisify(execFile);
const component = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repository = resolve(component, "..");
const work = await mkdtemp(join(tmpdir(), "ravenroot-amqp-example-"));
const target = join(work, "target");
const bundle = resolve(repository, "ravenroot/ravenroot-extensions/ravenroot-amqp091/target/plugin-bundle");
const project = `rrcfg-amqp-${process.pid}`;
const image = `${project}:latest`;
const password = "sanitized-fixture-password";
let containerBefore = "";

async function command(command: readonly string[], environment: Readonly<Record<string, string>> = {}): Promise<void> {
  await runFile(command[0]!, command.slice(1), { cwd: target, env: { ...process.env, ...environment }, maxBuffer: 8 * 1024 * 1024 });
}
async function query(commandLine: readonly string[]): Promise<string> {
  const result = await runFile(commandLine[0]!, commandLine.slice(1), { cwd: target, env: { ...process.env, RABBITMQ_PASSWORD: password }, maxBuffer: 8 * 1024 * 1024 });
  return result.stdout;
}
async function freePort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((ok, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", ok); });
  const address = server.address(); if (!address || typeof address === "string") throw new Error("failed to reserve a port");
  await new Promise<void>((ok, reject) => server.close((failure) => failure ? reject(failure) : ok()));
  return address.port;
}
async function behaviors(baseUrl: string): Promise<Set<string>> {
  for (let attempt = 0; attempt < 45; attempt += 1) {
    try {
      const ready = await fetch(`${baseUrl}/ready`);
      if (ready.ok) {
        const response = await fetch(`${baseUrl}/v1/node-types`);
        if (!response.ok) throw new Error(`catalog returned ${response.status}`);
        const body = JSON.stringify(await response.json());
        return new Set([...body.matchAll(/"behavior":"([^"]+)"/g)].map((match) => match[1]!));
      }
    } catch {}
    await new Promise((ok) => setTimeout(ok, 1000));
  }
  throw new Error("real Ravenroot container did not become ready");
}

await mkdir(target);
try {
  // Producer-side build only. The package carries these closed bytes; target apply never invokes plugin.sh.
  await runFile(resolve(repository, "plugin.sh"), ["build", "amqp091", "--skip-tests"], { cwd: repository, maxBuffer: 16 * 1024 * 1024 });
  await readFile(join(bundle, "ravenroot-plugin.json"));
  await mkdir(join(target, "empty-plugins"));
  await writeFile(join(target, "Dockerfile"), `ARG BASE_IMAGE=ravenroot:local\nFROM \${BASE_IMAGE}\nUSER 0\nRUN rm -rf /opt/ravenroot/plugins/ai.ravenroot.extensions.amqp091\nARG RAVENROOT_PLUGINS_DIR=empty-plugins\nCOPY \${RAVENROOT_PLUGINS_DIR}/ /tmp/configurator-plugin/\nRUN if [ -f /tmp/configurator-plugin/ai.ravenroot.extensions.amqp091/ravenroot-plugin.json ]; then mkdir -p /opt/ravenroot/plugins && cp -a /tmp/configurator-plugin/ai.ravenroot.extensions.amqp091 /opt/ravenroot/plugins/ && chown -R 10001:10001 /opt/ravenroot/plugins; fi && rm -rf /tmp/configurator-plugin\nUSER 10001:10001\n`);
  const port = await freePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const baseCompose = `name: ${project}\nservices:\n  rabbitmq:\n    image: rabbitmq:4.1-management-alpine\n    environment:\n      RABBITMQ_DEFAULT_USER: fixture\n      RABBITMQ_DEFAULT_PASS: \${RABBITMQ_PASSWORD}\n    healthcheck:\n      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]\n      interval: 2s\n      timeout: 2s\n      retries: 30\n  ravenroot:\n    image: ${image}\n    build:\n      context: .\n      args:\n        BASE_IMAGE: ravenroot:local\n    depends_on:\n      rabbitmq:\n        condition: service_healthy\n    ports:\n      - "127.0.0.1:${port}:8080"\n    environment:\n      RAVENROOT_PORT: "8080"\n      RAVENROOT_REPLICAS: "1"\n      RAVENROOT_BIND_ADDRESS: "0.0.0.0"\n      RAVENROOT_AUTH_MODE: disabled\n      RAVENROOT_CONTAINER_LOOPBACK_ONLY: "true"\n      RAVENROOT_LOCAL_HOST_BIND_ADDRESS: "127.0.0.1"\n      RABBITMQ_PASSWORD: \${RABBITMQ_PASSWORD}\n      UNRELATED_SETTING: retained\n    tmpfs:\n      - /tmp:rw,noexec,nosuid,size=64m\n    volumes:\n      - data:/opt/ravenroot/data\nvolumes:\n  data: {}\n`;
  await writeFile(join(target, "compose.yaml"), baseCompose);
  process.env.RABBITMQ_PASSWORD = password;
  await command(["docker", "compose", "-f", join(target, "compose.yaml"), "up", "-d", "--build", "ravenroot"], { RABBITMQ_PASSWORD: password });
  const baseline = await behaviors(baseUrl);
  if (baseline.has("amqp.publish") || baseline.has("amqp.consume")) throw new Error("baseline unexpectedly activated AMQP");
  containerBefore = (await query(["docker", "compose", "-f", join(target, "compose.yaml"), "ps", "-q", "ravenroot"])).trim();

  const rawSpec = JSON.parse(await readFile(join(component, "examples/amqp/package-spec.json"), "utf8")) as Omit<PackageInput, "bundles"> & { bundleDirectories: string[] };
  const configurations = structuredClone(rawSpec.configurations) as any[];
  const publisher = configurations.find((item) => item.contractId === "amqp.profile");
  Object.assign(publisher.values, { host: "rabbitmq", port: 5672, tls: false, vhost: "/", username: "fixture" });
  const spec = { ...rawSpec, target: { ...rawSpec.target, options: { verifyBaseUrl: baseUrl } }, configurations };
  const packaged = await createPackage({ ...spec, bundles: [await readBundleDirectory(bundle)] });
  const decoded = await inspectPackage(packaged.bytes);
  const first = await prepareInstall(decoded, { targetRoot: target });
  if (first.plan.conflicts.length) throw new Error(first.plan.conflicts.join("\n"));
  await applyInstall(first, { targetRoot: target, execute: true, commandRunner: command, queryRunner: query });

  const containerAfter = (await query(["docker", "compose", "-f", join(target, "compose.yaml"), "-f", join(target, ".ravenroot-config/compose/compose.rrcfg.yaml"), "ps", "-q", "ravenroot"])).trim();
  if (!containerAfter || containerAfter === containerBefore) throw new Error("Ravenroot was not recreated after package application");
  const catalog = await behaviors(baseUrl);
  if (!catalog.has("amqp.publish") || !catalog.has("amqp.consume")) throw new Error("real Ravenroot catalog did not activate both AMQP nodes");
  await verifyTarget(decoded, baseUrl);
  const inspected = JSON.parse(await query(["docker", "inspect", containerAfter]))[0];
  const environment = new Map<string, string>((inspected.Config.Env as string[]).map((row: string) => [row.slice(0, row.indexOf("=")), row.slice(row.indexOf("=") + 1)]));
  for (const [key, value] of Object.entries(first.environment)) if (environment.get(key) !== value) throw new Error(`running container differs for ${key}`);
  if (environment.get("RAVENROOT_AMQP091_CREDENTIAL_62726F6B6572") !== password) throw new Error("target-side AMQP secret was not bound in the running container");
  if (environment.get("UNRELATED_SETTING") !== "retained") throw new Error("additive Compose setting was not preserved");
  const rabbit = (await query(["docker", "compose", "-f", join(target, "compose.yaml"), "ps", "-q", "rabbitmq"])).trim();
  if (!rabbit) throw new Error("local AMQP broker fixture is not running");
  const overlay = parse(await readFile(join(target, ".ravenroot-config/compose/compose.rrcfg.yaml"), "utf8"));
  if (overlay.services.ravenroot.environment.RAVENROOT_AMQP091_CREDENTIAL_62726F6B6572 !== "\${RABBITMQ_PASSWORD}") throw new Error("secret reference was not preserved");
  const second = await prepareInstall(decoded, { targetRoot: target });
  if (second.plan.changes.some((change) => change.action !== "keep")) throw new Error("reapply was not idempotent");

  await rollback(target, command, query);
  const restored = (await query(["docker", "compose", "-f", join(target, "compose.yaml"), "ps", "-q", "ravenroot"])).trim();
  if (!restored || restored === containerAfter) throw new Error("rollback did not recreate the prior runtime");
  if ((await behaviors(baseUrl)).has("amqp.publish")) throw new Error("rollback did not remove AMQP activation");
  if (await readFile(join(target, "compose.yaml"), "utf8") !== baseCompose) throw new Error("base Compose file changed");
  process.stdout.write(`AMQP real-runtime example passed: package=${packaged.digest} producerBundle=closed targetBuild=prebuilt restart=observed broker=healthy catalog=verified settings=inspected secret=bound additive=preserved readiness=verified rollback=compensated\n`);
} finally {
  try { await command(["docker", "compose", "-f", join(target, "compose.yaml"), "down", "--volumes", "--remove-orphans", "--rmi", "local"], { RABBITMQ_PASSWORD: password }); } catch {}
  await rm(work, { recursive: true, force: true });
}
