import { createServer } from "node:http";
import { readFile, writeFile } from "node:fs/promises";
import { extname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { stdin as input, stdout as output } from "node:process";
import { CONTRACTS } from "./registry.js";
import { readBundleDirectory } from "./bundle-directory.js";
import { applyInstall, prepareInstall, redactedPlanJson, rollback, verifyTarget } from "./installer.js";
import { createPackage, inspectPackage, type PackageInput } from "./package-format.js";
import type { SecretInput } from "./types.js";

function option(args: readonly string[], name: string): string | undefined {
  const index = args.indexOf(name);
  return index >= 0 ? args[index + 1] : undefined;
}

function has(args: readonly string[], name: string): boolean {
  return args.includes(name);
}

async function password(args: readonly string[]): Promise<string | undefined> {
  if (!has(args, "--password-stdin")) return undefined;
  if (input.isTTY) output.write("Package password: ");
  const chunks: Buffer[] = [];
  for await (const chunk of input) chunks.push(Buffer.from(chunk));
  const value = Buffer.concat(chunks).toString("utf8").replace(/[\r\n]+$/, "");
  if (input.isTTY) output.write("\n");
  return value;
}

async function load(path: string) {
  return inspectPackage(new Uint8Array(await readFile(path)));
}

function usage(): never {
  throw new Error(`usage:
  ravenroot-config contracts
  ravenroot-config ui [--port 4178]
  ravenroot-config pack SPEC.json OUTPUT.rrcfg [--password-stdin]
  ravenroot-config inspect PACKAGE.rrcfg
  ravenroot-config verify PACKAGE.rrcfg --base-url URL
  ravenroot-config plan PACKAGE.rrcfg --target-root DIR [--password-stdin] [--replace]
  ravenroot-config apply PACKAGE.rrcfg --target-root DIR [--password-stdin] [--replace] [--execute]
  ravenroot-config rollback --target-root DIR`);
}

interface PackSpec extends Omit<PackageInput, "bundles" | "secrets" | "password"> {
  readonly bundleDirectories: readonly string[];
  readonly secrets: readonly (SecretInput | { readonly mode: "embedded"; readonly bindingId: string; readonly environmentKey: string; readonly valueEnvironment: string })[];
}

async function pack(args: readonly string[]): Promise<void> {
  const specPath = args[1];
  const outputPath = args[2];
  if (!specPath || !outputPath) usage();
  const spec = JSON.parse(await readFile(specPath, "utf8")) as PackSpec;
  const bundles = await Promise.all(spec.bundleDirectories.map((directory) => readBundleDirectory(resolve(directory))));
  const secrets: SecretInput[] = spec.secrets.map((secret) => {
    if (secret.mode === "target-reference") return secret;
    if ("value" in secret) throw new Error("Pack specs must not contain embedded secret values; use valueEnvironment");
    const value = process.env[secret.valueEnvironment];
    if (value === undefined) throw new Error(`Missing embedded secret environment variable ${secret.valueEnvironment}`);
    return { mode: "embedded", bindingId: secret.bindingId, environmentKey: secret.environmentKey, value };
  });
  const packagePassword = await password(args);
  const result = await createPackage({ ...spec, bundles, secrets, ...(packagePassword === undefined ? {} : { password: packagePassword }) });
  await writeFile(outputPath, result.bytes, { mode: 0o600 });
  output.write(`package=${outputPath} sha256=${result.digest} entries=${result.manifest.entries.length}\n`);
}

async function serve(args: readonly string[]): Promise<void> {
  const port = Number(option(args, "--port") ?? "4178");
  if (!Number.isSafeInteger(port) || port < 1 || port > 65535) throw new Error("Invalid UI port");
  const root = fileURLToPath(new URL("../../dist/ui/", import.meta.url));
  const types: Record<string, string> = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml" };
  const server = createServer(async (request, response) => {
    const requestPath = request.url === "/" ? "index.html" : (request.url ?? "/").replace(/^\//, "");
    if (requestPath.includes("..")) { response.writeHead(400).end(); return; }
    try {
      const bytes = await readFile(join(root, requestPath));
      response.writeHead(200, { "content-type": types[extname(requestPath)] ?? "application/octet-stream", "cache-control": "no-store", "content-security-policy": "default-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'none'; img-src 'self' data:" });
      response.end(bytes);
    } catch {
      response.writeHead(404).end();
    }
  });
  server.listen(port, "127.0.0.1", () => output.write(`Ravenroot configurator: http://127.0.0.1:${port}\n`));
}

export async function main(args: readonly string[]): Promise<void> {
  const command = args[0];
  if (!command) usage();
  if (command === "contracts") {
    for (const contract of CONTRACTS) output.write(`${contract.id}\t${contract.title}\t${contract.nodeIds.join(",")}\n`);
    return;
  }
  if (command === "ui") return serve(args);
  if (command === "pack") return pack(args);
  if (command === "inspect") {
    const path = args[1];
    if (!path) usage();
    const pkg = await load(path);
    output.write(`${JSON.stringify({ digest: pkg.digest, ...pkg.manifest, plan: pkg.manifest.plan }, null, 2)}\n`);
    return;
  }
  if (command === "rollback") {
    const root = option(args, "--target-root");
    if (!root) usage();
    await rollback(root);
    output.write("rollback=complete\n");
    return;
  }
  if (command === "verify") {
    const path = args[1];
    const baseUrl = option(args, "--base-url");
    if (!path || !baseUrl) usage();
    await verifyTarget(await load(path), baseUrl);
    output.write("verify=complete readiness=ready catalog=complete\n");
    return;
  }
  if (command === "plan" || command === "apply") {
    const path = args[1];
    const root = option(args, "--target-root");
    if (!path || !root) usage();
    const packagePassword = await password(args);
    const pkg = await load(path);
    const prepared = await prepareInstall(pkg, {
      targetRoot: root, replace: has(args, "--replace"),
      ...(packagePassword === undefined ? {} : { password: packagePassword })
    });
    output.write(redactedPlanJson(prepared.plan));
    if (command === "apply") {
      await applyInstall(prepared, { targetRoot: root, replace: has(args, "--replace"), execute: has(args, "--execute") });
      output.write(`apply=complete restart=${has(args, "--execute") ? "executed" : "pending"}\n`);
    }
    return;
  }
  usage();
}
