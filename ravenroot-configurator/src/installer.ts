import { spawn } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import { access, chmod, copyFile, lstat, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve, sep } from "node:path";
import { stringify } from "yaml";
import { canonicalJson, sha256, text, utf8 } from "./codec.js";
import { decryptSecret } from "./crypto.js";
import { CONTRACT_BY_ID, serializeSelection } from "./registry.js";
import type { DecodedPackage, EmbeddedSecretEnvelope, PlanChange, RedactedPlan, TargetKind } from "./types.js";

const STATE_DIR = ".ravenroot-config";
const STATE_FILE = "state.json";
const PENDING_FILE = "pending.json";

interface InstallerState {
  readonly version: 1;
  readonly targetId: string;
  readonly targetKind: TargetKind;
  readonly packageDigest: string;
  readonly environmentDigests: Readonly<Record<string, string>>;
  readonly files: Readonly<Record<string, string>>;
  readonly lastTransaction?: string;
}

interface GeneratedArtifact {
  readonly path: string;
  readonly bytes: Uint8Array;
  readonly mode: number;
  readonly sensitive: boolean;
}

interface ExternalDocument {
  readonly environmentKey: string;
  readonly fileName: string;
  readonly bytes: Uint8Array;
}

const EXTERNAL_DOCUMENTS: ReadonlyMap<string, { readonly valueKey: string; readonly environmentKey: string; readonly fileName: string }> = new Map([
  ["core.human-task", { valueKey: "interactionDocument", environmentKey: "RAVENROOT_HUMAN_TASK_INTERACTION_CONFIG", fileName: "human-task-interactions.json" }],
  ["core.publication-policies", { valueKey: "policyDocument", environmentKey: "RAVENROOT_PUBLICATION_POLICY_CONFIG", fileName: "publication-policies.json" }],
  ["core.runner", { valueKey: "runnerDocument", environmentKey: "RAVENROOT_RUNNER_CONFIG", fileName: "runner-control-plane.json" }]
] as const);

export interface InstallOptions {
  readonly targetRoot: string;
  readonly password?: string;
  readonly replace?: boolean;
  readonly execute?: boolean;
  readonly commandRunner?: (command: readonly string[], environment: Readonly<Record<string, string>>) => Promise<void>;
}

export interface PreparedInstall {
  readonly plan: RedactedPlan;
  readonly artifacts: readonly GeneratedArtifact[];
  readonly environment: Readonly<Record<string, string>>;
  readonly commands: readonly (readonly string[])[];
  readonly state: InstallerState;
}

function confined(root: string, relative: string): string {
  if (relative.startsWith("/") || relative.split(/[\\/]/).some((part) => part === ".." || part === "")) throw new Error(`Unsafe target path: ${relative}`);
  const target = resolve(root, relative);
  const prefix = resolve(root) + sep;
  if (!target.startsWith(prefix)) throw new Error(`Target path escapes root: ${relative}`);
  return target;
}

async function exists(path: string): Promise<boolean> {
  try { await access(path, fsConstants.F_OK); return true; } catch { return false; }
}

async function readState(root: string): Promise<InstallerState | undefined> {
  const path = confined(root, `${STATE_DIR}/${STATE_FILE}`);
  if (!await exists(path)) return undefined;
  const value = JSON.parse(await readFile(path, "utf8")) as InstallerState;
  if (value.version !== 1) throw new Error("Installer state version is unsupported");
  return value;
}

async function resolveInputs(pkg: DecodedPackage, password?: string): Promise<{ environment: Record<string, string>; documents: ExternalDocument[] }> {
  const environment: Record<string, string> = {};
  const embeddedSecrets = new Map<string, string>();
  for (const selection of pkg.manifest.configurations) {
    for (const [key, value] of Object.entries(serializeSelection(selection))) {
      if (environment[key] !== undefined && environment[key] !== value) throw new Error(`Package defines conflicting values for ${key}`);
      environment[key] = value;
    }
  }
  for (const binding of pkg.manifest.secrets) {
    if (binding.mode === "embedded") {
      if (!password) throw new Error(`Password is required to unlock ${binding.bindingId}`);
      const entry = binding.entry ? pkg.entries[binding.entry] : undefined;
      if (!entry) throw new Error(`Encrypted secret entry is missing for ${binding.bindingId}`);
      const value = await decryptSecret(binding.bindingId, JSON.parse(text(entry)) as EmbeddedSecretEnvelope, password);
      environment[binding.environmentKey] = value;
      embeddedSecrets.set(binding.bindingId, value);
    }
  }
  const enabled = pkg.manifest.bundles.map((bundle) => bundle.id).sort().join(",");
  if (enabled) environment.RAVENROOT_ENABLED_PLUGINS = enabled;
  const documents: ExternalDocument[] = [];
  for (const selection of pkg.manifest.configurations) {
    const spec = EXTERNAL_DOCUMENTS.get(selection.contractId);
    if (!spec) continue;
    const source = selection.values[spec.valueKey];
    if (source === undefined) throw new Error(`${selection.contractId} requires ${spec.valueKey}`);
    const resolved = replaceDocumentSecrets(source, embeddedSecrets);
    documents.push({ environmentKey: spec.environmentKey, fileName: spec.fileName,
      bytes: utf8(`${canonicalJson(resolved)}\n`) });
  }
  return { environment, documents };
}

function replaceDocumentSecrets(value: unknown, secrets: ReadonlyMap<string, string>): unknown {
  if (Array.isArray(value)) return value.map((item) => replaceDocumentSecrets(item, secrets));
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>);
    if (entries.length === 1 && entries[0]?.[0] === "$secret") {
      const bindingId = entries[0][1];
      if (typeof bindingId !== "string" || !secrets.has(bindingId)) {
        throw new Error(`External document references unavailable embedded secret ${String(bindingId)}`);
      }
      return secrets.get(bindingId) as string;
    }
    return Object.fromEntries(entries.map(([key, item]) => [key, replaceDocumentSecrets(item, secrets)]));
  }
  return value;
}

function bundleArtifacts(pkg: DecodedPackage, prefix: string): GeneratedArtifact[] {
  return pkg.manifest.bundles.flatMap((bundle) => Object.entries(pkg.entries)
    .filter(([path]) => path.startsWith(bundle.entryPrefix))
    .map(([path, bytes]) => ({
      path: `${prefix}/${bundle.id}/${path.slice(bundle.entryPrefix.length)}`,
      bytes,
      mode: 0o644,
      sensitive: false
    })));
}

function targetReferenceEnvironment(pkg: DecodedPackage): Record<string, string> {
  const references: Record<string, string> = {};
  for (const binding of pkg.manifest.secrets) {
    if (binding.mode !== "target-reference") continue;
    references[binding.environmentKey] = binding.composeVariable ?? binding.environmentKey;
  }
  return references;
}

function composeArtifacts(pkg: DecodedPackage, environment: Readonly<Record<string, string>>, documents: readonly ExternalDocument[]): GeneratedArtifact[] {
  const references = targetReferenceEnvironment(pkg);
  const renderedEnvironment: Record<string, string> = {};
  for (const [key, value] of Object.entries(environment)) renderedEnvironment[key] = pkg.manifest.secrets.some((secret) => secret.environmentKey === key)
    ? `\${${references[key] ?? key}}` : value;
  for (const [key, variable] of Object.entries(references)) renderedEnvironment[key] = `\${${variable}}`;
  const service: Record<string, unknown> = { environment: renderedEnvironment };
  if (documents.length) {
    service.volumes = documents.map((document) => `./${STATE_DIR}/compose/config/${document.fileName}:/etc/ravenroot/configurator/${document.fileName}:ro`);
    for (const document of documents) renderedEnvironment[document.environmentKey] = `/etc/ravenroot/configurator/${document.fileName}`;
  }
  if (pkg.manifest.bundles.length) {
    service.build = { args: { RAVENROOT_PLUGINS_DIR: `${STATE_DIR}/compose/plugins` } };
  }
  return [
    { path: `${STATE_DIR}/compose/compose.rrcfg.yaml`, bytes: utf8(stringify({ services: { ravenroot: service } })), mode: 0o600, sensitive: true },
    ...documents.map((document) => ({ path: `${STATE_DIR}/compose/config/${document.fileName}`, bytes: document.bytes, mode: 0o600, sensitive: true })),
    ...bundleArtifacts(pkg, `${STATE_DIR}/compose/plugins`)
  ];
}

function yamlScalar(value: string): string {
  return Buffer.from(value, "utf8").toString("base64");
}

function kubernetesArtifacts(pkg: DecodedPackage, environment: Record<string, string>, documents: readonly ExternalDocument[]): GeneratedArtifact[] {
  const namespace = String(pkg.manifest.target.options.namespace ?? "default");
  const release = String(pkg.manifest.target.options.release ?? "ravenroot");
  const deployment = String(pkg.manifest.target.options.deploymentName ?? `${release}-ravenroot`);
  const secretName = `ravenroot-config-${pkg.manifest.target.id.toLowerCase().replace(/[^a-z0-9-]/g, "-").slice(0, 40)}`;
  const references = pkg.manifest.secrets.filter((binding) => binding.mode === "target-reference");
  for (const document of documents) environment[document.environmentKey] = `/etc/ravenroot/configurator/${document.fileName}`;
  const environmentSecret = {
    apiVersion: "v1", kind: "Secret", metadata: { name: secretName, namespace, labels: { "app.kubernetes.io/managed-by": "ravenroot-config" } },
    type: "Opaque", data: Object.fromEntries(Object.entries(environment).map(([key, value]) => [key, yamlScalar(value)]))
  };
  const fileSecretName = `${secretName}-files`;
  const fileSecret = {
    apiVersion: "v1", kind: "Secret", metadata: { name: fileSecretName, namespace, labels: { "app.kubernetes.io/managed-by": "ravenroot-config" } },
    type: "Opaque", data: Object.fromEntries(documents.map((document) => [document.fileName, yamlScalar(text(document.bytes))]))
  };
  const environmentRefs = Object.keys(environment).sort().map((name) => ({
    name, valueFrom: { secretKeyRef: { name: secretName, key: name } }
  }));
  const targetRefs = references.map((binding) => ({
    name: binding.environmentKey,
    valueFrom: { secretKeyRef: { name: binding.kubernetesSecret ?? "REQUIRED_TARGET_SECRET",
      key: binding.kubernetesKey ?? binding.bindingId } }
  }));
  const patch = {
    apiVersion: "apps/v1", kind: "Deployment",
    metadata: { name: deployment, namespace },
    spec: { template: { spec: {
      containers: [{ name: "ravenroot", env: [...environmentRefs, ...targetRefs],
        ...(documents.length ? { volumeMounts: [{ name: "ravenroot-configurator-files",
          mountPath: "/etc/ravenroot/configurator", readOnly: true }] } : {}) }],
      ...(documents.length ? { volumes: [{ name: "ravenroot-configurator-files", secret: { secretName: fileSecretName } }] } : {})
    } } }
  };
  const values: Record<string, unknown> = {};
  const artifacts: GeneratedArtifact[] = [
    { path: `${STATE_DIR}/kubernetes/configuration-secret.yaml`, bytes: utf8(documents.length
      ? `${stringify(environmentSecret)}---\n${stringify(fileSecret)}` : stringify(environmentSecret)), mode: 0o600, sensitive: true },
    { path: `${STATE_DIR}/kubernetes/values.rrcfg.yaml`, bytes: utf8(stringify(values)), mode: 0o600, sensitive: true },
    { path: `${STATE_DIR}/kubernetes/deployment-patch.rrcfg.yaml`, bytes: utf8(stringify(patch)), mode: 0o600, sensitive: true }
  ];
  if (pkg.manifest.bundles.length) {
    const baseImage = String(pkg.manifest.target.options.baseImage ?? "");
    const derivedImage = String(pkg.manifest.target.options.derivedImage ?? "");
    if (!baseImage || !derivedImage) throw new Error("Kubernetes bundle application requires target options baseImage and derivedImage");
    artifacts.push({
      path: `${STATE_DIR}/kubernetes/bundle-image/Dockerfile`, mode: 0o644, sensitive: false,
      bytes: utf8(`FROM ${baseImage}\nUSER 0\nCOPY --chown=10001:10001 plugins/ /opt/ravenroot/plugins/\nUSER 10001:10001\n`)
    });
    artifacts.push(...bundleArtifacts(pkg, `${STATE_DIR}/kubernetes/bundle-image/plugins`));
    artifacts.push({ path: `${STATE_DIR}/kubernetes/image.txt`, bytes: utf8(`${derivedImage}\n`), mode: 0o644, sensitive: false });
    Object.assign(values, { image: helmImage(derivedImage) });
    artifacts[1] = { path: `${STATE_DIR}/kubernetes/values.rrcfg.yaml`, bytes: utf8(stringify(values)), mode: 0o600, sensitive: true };
  }
  return artifacts;
}

function helmImage(reference: string): Record<string, string> {
  const digestAt = reference.lastIndexOf("@sha256:");
  if (digestAt > 0) return { repository: reference.slice(0, digestAt), tag: "", digest: reference.slice(digestAt + 1) };
  const slash = reference.lastIndexOf("/");
  const colon = reference.lastIndexOf(":");
  return colon > slash ? { repository: reference.slice(0, colon), tag: reference.slice(colon + 1), digest: "" }
    : { repository: reference, tag: "latest", digest: "" };
}

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", `'"'"'`)}'`;
}

function prestartArtifacts(pkg: DecodedPackage, environment: Readonly<Record<string, string>>, documents: readonly ExternalDocument[], root: string): GeneratedArtifact[] {
  const installDir = confined(root, `${STATE_DIR}/prestart/plugins`);
  const values: Record<string, string> = { ...environment, RAVENROOT_PLUGINS_INSTALL_DIR: installDir };
  for (const document of documents) values[document.environmentKey] = confined(root, `${STATE_DIR}/prestart/config/${document.fileName}`);
  const references = targetReferenceEnvironment(pkg);
  const lines = Object.entries(values).sort(([left], [right]) => left.localeCompare(right)).map(([key, value]) => `export ${key}=${shellQuote(value)}`);
  for (const [key, variable] of Object.entries(references)) lines.push(`: "\${${variable}:?target secret ${variable} is required}"`, `export ${key}="\${${variable}}"`);
  return [
    { path: `${STATE_DIR}/prestart/environment.sh`, bytes: utf8(`${lines.join("\n")}\n`), mode: 0o600, sensitive: true },
    ...documents.map((document) => ({ path: `${STATE_DIR}/prestart/config/${document.fileName}`, bytes: document.bytes, mode: 0o600, sensitive: true })),
    ...bundleArtifacts(pkg, `${STATE_DIR}/prestart/plugins`)
  ];
}

function commands(pkg: DecodedPackage, root: string): readonly (readonly string[])[] {
  if (pkg.manifest.target.kind === "compose") {
    return [["docker", "compose", "-f", join(root, "compose.yaml"), "-f", confined(root, `${STATE_DIR}/compose/compose.rrcfg.yaml`), "up", "-d", "--build", "--force-recreate", "ravenroot"]];
  }
  if (pkg.manifest.target.kind === "kubernetes") {
    const namespace = String(pkg.manifest.target.options.namespace ?? "default");
    const release = String(pkg.manifest.target.options.release ?? "ravenroot");
    const chart = confined(root, String(pkg.manifest.target.options.chart ?? "deploy/helm/ravenroot"));
    const deployment = String(pkg.manifest.target.options.deploymentName ?? `${release}-ravenroot`);
    const result: string[][] = [];
    if (pkg.manifest.bundles.length) {
      const image = String(pkg.manifest.target.options.derivedImage);
      result.push(["docker", "build", "-t", image, confined(root, `${STATE_DIR}/kubernetes/bundle-image`)]);
      if (pkg.manifest.target.options.pushImage === true) result.push(["docker", "push", image]);
    }
    result.push(["kubectl", "apply", "-f", confined(root, `${STATE_DIR}/kubernetes/configuration-secret.yaml`)]);
    result.push(["helm", "upgrade", "--install", release, chart, "--namespace", namespace, "-f", confined(root, `${STATE_DIR}/kubernetes/values.rrcfg.yaml`)]);
    result.push(["kubectl", "patch", `deployment/${deployment}`, "--namespace", namespace, "--type", "strategic", "--patch-file", confined(root, `${STATE_DIR}/kubernetes/deployment-patch.rrcfg.yaml`)]);
    result.push(["kubectl", "rollout", "status", `deployment/${deployment}`, "--namespace", namespace, "--timeout=5m"]);
    return result;
  }
  const configured = pkg.manifest.target.options.restartCommandJson;
  if (configured === undefined) return [];
  let command: unknown;
  try { command = JSON.parse(String(configured)); } catch { throw new Error("prestart restartCommandJson must be a JSON string array"); }
  if (!Array.isArray(command) || command.length === 0 || command.some((part) => typeof part !== "string" || !part)) {
    throw new Error("prestart restartCommandJson must be a non-empty JSON string array");
  }
  return [command as string[]];
}

async function artifactChanges(root: string, artifacts: readonly GeneratedArtifact[], replace: boolean): Promise<{ changes: PlanChange[]; conflicts: string[]; digests: Record<string, string> }> {
  const changes: PlanChange[] = [];
  const conflicts: string[] = [];
  const digests: Record<string, string> = {};
  for (const artifact of artifacts) {
    const target = confined(root, artifact.path);
    const after = await sha256(artifact.bytes);
    digests[artifact.path] = after;
    if (!await exists(target)) {
      changes.push({ kind: artifact.path.includes("plugins/") ? "bundle" : "file", target: artifact.path, action: "add", after, sensitive: artifact.sensitive });
      continue;
    }
    const metadata = await lstat(target);
    if (!metadata.isFile() || metadata.isSymbolicLink()) {
      conflicts.push(`${artifact.path} is not an ordinary file`);
      continue;
    }
    const before = await sha256(new Uint8Array(await readFile(target)));
    if (before === after) changes.push({ kind: "file", target: artifact.path, action: "keep", before, after, sensitive: artifact.sensitive });
    else if (replace) changes.push({ kind: "file", target: artifact.path, action: "replace", before, after, sensitive: artifact.sensitive });
    else conflicts.push(`${artifact.path} differs; use the separate replacement operation`);
  }
  return { changes, conflicts, digests };
}

export async function prepareInstall(pkg: DecodedPackage, options: InstallOptions): Promise<PreparedInstall> {
  const root = resolve(options.targetRoot);
  const rootStat = await stat(root);
  if (!rootStat.isDirectory()) throw new Error("Target root is not a directory");
  if (await exists(confined(root, `${STATE_DIR}/${PENDING_FILE}`))) {
    throw new Error("An interrupted installer transaction requires rollback before a new plan can be applied");
  }
  const previous = await readState(root);
  if (previous && (previous.targetId !== pkg.manifest.target.id || previous.targetKind !== pkg.manifest.target.kind)) {
    throw new Error(`Target is already bound to ${previous.targetKind}/${previous.targetId}`);
  }
  const resolvedInputs = await resolveInputs(pkg, options.password);
  const environment = resolvedInputs.environment;
  const artifacts = pkg.manifest.target.kind === "compose" ? composeArtifacts(pkg, environment, resolvedInputs.documents)
    : pkg.manifest.target.kind === "kubernetes" ? kubernetesArtifacts(pkg, environment, resolvedInputs.documents)
      : prestartArtifacts(pkg, environment, resolvedInputs.documents, root);
  const evaluated = await artifactChanges(root, artifacts, options.replace === true);
  const environmentDigests: Record<string, string> = {};
  for (const [key, value] of Object.entries(environment)) environmentDigests[key] = await sha256(utf8(value));
  if (previous && previous.packageDigest !== pkg.digest && options.replace !== true) {
    for (const [key, digest] of Object.entries(environmentDigests)) {
      if (previous.environmentDigests[key] && previous.environmentDigests[key] !== digest) evaluated.conflicts.push(`${key} differs from the installed value`);
    }
  }
  const changes = [...evaluated.changes];
  for (const secret of pkg.manifest.secrets) changes.push({ kind: "environment", target: secret.environmentKey,
    action: previous?.environmentDigests[secret.environmentKey] === environmentDigests[secret.environmentKey] ? "keep" : options.replace ? "replace" : "add",
    after: `<secret:${secret.bindingId}>`, sensitive: true });
  const targetCommands = commands(pkg, root);
  const plan: RedactedPlan = {
    version: 1, targetKind: pkg.manifest.target.kind, targetId: pkg.manifest.target.id,
    changes, conflicts: [...new Set(evaluated.conflicts)].sort(), restartRequired: pkg.manifest.plan.restartRequired,
    restart: targetCommands.map((command) => command.join(" ")),
    verification: pkg.manifest.target.kind === "kubernetes"
      ? ["kubectl rollout status deployment/ravenroot", "query /ready and /v1/node-types"]
      : ["query /ready and /v1/node-types after restart"]
  };
  const state: InstallerState = {
    version: 1, targetId: pkg.manifest.target.id, targetKind: pkg.manifest.target.kind,
    packageDigest: pkg.digest, environmentDigests, files: evaluated.digests
  };
  return { plan, artifacts, environment, commands: targetCommands, state };
}

async function atomicWrite(path: string, bytes: Uint8Array, mode: number): Promise<void> {
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  const temporary = `${path}.tmp-${process.pid}-${crypto.randomUUID()}`;
  await writeFile(temporary, bytes, { mode, flag: "wx" });
  await chmod(temporary, mode);
  await rename(temporary, path);
}

async function defaultRunner(command: readonly string[], environment: Readonly<Record<string, string>>): Promise<void> {
  await new Promise<void>((resolvePromise, reject) => {
    const child = spawn(command[0] as string, command.slice(1), { stdio: "inherit", env: { ...process.env, ...environment } });
    child.once("error", reject);
    child.once("exit", (code) => code === 0 ? resolvePromise() : reject(new Error(`${basename(command[0] as string)} exited with ${code}`)));
  });
}

export async function applyInstall(prepared: PreparedInstall, options: InstallOptions): Promise<void> {
  if (prepared.plan.conflicts.length) throw new Error(`Installation has conflicts:\n${prepared.plan.conflicts.join("\n")}`);
  const root = resolve(options.targetRoot);
  const transaction = `${new Date().toISOString().replace(/[:.]/g, "-")}-${crypto.randomUUID()}`;
  const backupRoot = confined(root, `${STATE_DIR}/backups/${transaction}`);
  const backupIndex: Record<string, boolean> = {};
  await mkdir(backupRoot, { recursive: true, mode: 0o700 });
  const backedPaths = [...prepared.artifacts.map((artifact) => ({ path: artifact.path, sensitive: artifact.sensitive })),
    { path: `${STATE_DIR}/${STATE_FILE}`, sensitive: true }];
  for (const artifact of backedPaths) {
    const target = confined(root, artifact.path);
    const backup = confined(backupRoot, artifact.path);
    const present = await exists(target);
    backupIndex[artifact.path] = present;
    if (present) {
      await mkdir(dirname(backup), { recursive: true, mode: 0o700 });
      await copyFile(target, backup);
      if (artifact.sensitive) await chmod(backup, 0o600);
    }
  }
  await atomicWrite(confined(backupRoot, "index.json"), utf8(canonicalJson(backupIndex)), 0o600);
  await atomicWrite(confined(root, `${STATE_DIR}/${PENDING_FILE}`), utf8(canonicalJson({ version: 1, transaction })), 0o600);
  try {
    for (const artifact of prepared.artifacts) await atomicWrite(confined(root, artifact.path), artifact.bytes, artifact.mode);
    const state = { ...prepared.state, lastTransaction: transaction };
    await atomicWrite(confined(root, `${STATE_DIR}/${STATE_FILE}`), utf8(canonicalJson(state)), 0o600);
    if (options.execute) {
      const runner = options.commandRunner ?? defaultRunner;
      for (const command of prepared.commands) await runner(command, prepared.environment);
    }
    await rm(confined(root, `${STATE_DIR}/${PENDING_FILE}`));
  } catch (failure) {
    await rollback(root);
    throw failure;
  }
}

export async function rollback(targetRoot: string): Promise<void> {
  const root = resolve(targetRoot);
  const state = await readState(root);
  const pendingPath = confined(root, `${STATE_DIR}/${PENDING_FILE}`);
  const pending = await exists(pendingPath)
    ? JSON.parse(await readFile(pendingPath, "utf8")) as { version: number; transaction: string }
    : undefined;
  const transaction = pending?.transaction ?? state?.lastTransaction;
  if (!transaction) throw new Error("No recoverable installer transaction exists");
  const backupRoot = confined(root, `${STATE_DIR}/backups/${transaction}`);
  const index = JSON.parse(await readFile(confined(backupRoot, "index.json"), "utf8")) as Record<string, boolean>;
  for (const [relative, wasPresent] of Object.entries(index)) {
    const target = confined(root, relative);
    if (wasPresent) {
      const backup = confined(backupRoot, relative);
      await mkdir(dirname(target), { recursive: true, mode: 0o700 });
      await copyFile(backup, target);
    } else if (await exists(target)) {
      await rm(target);
    }
  }
  if (await exists(pendingPath)) await rm(pendingPath);
}

export function redactedPlanJson(plan: RedactedPlan): string {
  return `${canonicalJson(plan)}\n`;
}

export async function verifyTarget(pkg: DecodedPackage, baseUrl: string, bearer = process.env.RAVENROOT_CONFIG_VERIFY_BEARER): Promise<void> {
  const origin = new URL(baseUrl);
  if (!new Set(["http:", "https:"]).has(origin.protocol) || origin.username || origin.password || origin.search || origin.hash) {
    throw new Error("Verification base URL must be an HTTP(S) origin or base path without credentials, query, or fragment");
  }
  const headers = bearer ? { authorization: `Bearer ${bearer}` } : undefined;
  const request = headers ? { headers } : undefined;
  let lastFailure = "target did not become ready";
  for (let attempt = 0; attempt < 30; attempt += 1) {
    try {
      const response = await fetch(new URL("ready", origin.toString().replace(/\/?$/, "/")), request);
      if (response.ok) { lastFailure = ""; break; }
      lastFailure = `readiness returned HTTP ${response.status}`;
    } catch (error) { lastFailure = error instanceof Error ? error.message : String(error); }
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 1000));
  }
  if (lastFailure) throw new Error(`Target verification failed: ${lastFailure}`);
  const response = await fetch(new URL("v1/node-types", origin.toString().replace(/\/?$/, "/")), request);
  if (!response.ok) throw new Error(`Node catalog verification returned HTTP ${response.status}`);
  const catalog = await response.json() as unknown;
  const behaviors = new Set<string>();
  collectBehaviors(catalog, behaviors);
  const required = new Set(pkg.manifest.configurations.flatMap((selection) => {
    const contract = CONTRACT_BY_ID.get(selection.contractId);
    if (!contract) throw new Error(`Unknown configuration contract: ${selection.contractId}`);
    return contract.nodeIds;
  }));
  const missing = [...required].filter((behavior) => !behaviors.has(behavior)).sort();
  if (missing.length) throw new Error(`Target catalog is missing configured node IDs: ${missing.join(", ")}`);
}

function collectBehaviors(value: unknown, found: Set<string>): void {
  if (Array.isArray(value)) { for (const item of value) collectBehaviors(item, found); return; }
  if (!value || typeof value !== "object") return;
  const record = value as Record<string, unknown>;
  if (typeof record.behavior === "string") found.add(record.behavior);
  for (const item of Object.values(record)) collectBehaviors(item, found);
}
