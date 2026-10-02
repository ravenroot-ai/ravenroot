import { spawn } from "node:child_process";
import { constants as fsConstants } from "node:fs";
import { access, chmod, copyFile, lstat, mkdir, readFile, rename, rm, stat, writeFile } from "node:fs/promises";
import { basename, dirname, join, resolve, sep } from "node:path";
import { parse, parseAllDocuments, stringify } from "yaml";
import { canonicalJson, sha256, text, utf8 } from "./codec.js";
import { decryptSecret } from "./crypto.js";
import { CONTRACT_BY_ID, serializeSelection } from "./registry.js";
import type { DecodedPackage, EmbeddedSecretEnvelope, ExternalMutationStep, PlanChange, RedactedPlan, TargetKind } from "./types.js";

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
  readonly queryRunner?: (command: readonly string[]) => Promise<string>;
}

export interface PreparedInstall {
  readonly source: DecodedPackage;
  readonly plan: RedactedPlan;
  readonly artifacts: readonly GeneratedArtifact[];
  readonly environment: Readonly<Record<string, string>>;
  readonly commands: readonly (readonly string[])[];
  readonly externalSteps: readonly ExternalMutationStep[];
  readonly recoveryFiles: Readonly<Record<string, Uint8Array>>;
  readonly state: InstallerState;
}

interface TransactionJournal {
  readonly version: 2;
  readonly transaction: string;
  readonly steps: readonly ExternalMutationStep[];
  readonly completed: readonly string[];
  readonly inFlight?: string;
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
  for (const selection of pkg.manifest.configurations) {
    const resolved = { ...selection, values: replaceDocumentSecrets(selection.values, embeddedSecrets) as Readonly<Record<string, unknown>> };
    const serialized = serializeSelection(EXTERNAL_DOCUMENTS.has(selection.contractId) ? selection : resolved);
    for (const [key, value] of Object.entries(serialized)) {
      if (environment[key] !== undefined && environment[key] !== value) throw new Error(`Package defines conflicting values for ${key}`);
      environment[key] = value;
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
        throw new Error(`Configuration references unavailable embedded secret ${String(bindingId)}`);
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
    if (!binding.composeVariable) throw new Error(`Target reference ${binding.bindingId} has no environment source`);
    references[binding.environmentKey] = binding.composeVariable;
  }
  return references;
}

function composeEnvironmentValues(value: unknown): Map<string, string | undefined> {
  const result = new Map<string, string | undefined>();
  if (value === undefined) return result;
  if (Array.isArray(value)) {
    for (const item of value) {
      if (typeof item !== "string") throw new Error("Compose ravenroot environment list contains a non-string entry");
      const separator = item.indexOf("=");
      const key = separator < 0 ? item : item.slice(0, separator);
      if (!/^[A-Z_][A-Z0-9_]*$/.test(key) || result.has(key)) throw new Error(`Compose ravenroot environment contains invalid or duplicate key ${key}`);
      result.set(key, separator < 0 ? undefined : item.slice(separator + 1));
    }
    return result;
  }
  if (!value || typeof value !== "object") throw new Error("Compose ravenroot environment must be a map or list");
  for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
    if (!/^[A-Z_][A-Z0-9_]*$/.test(key) || item !== null && !["string", "number", "boolean"].includes(typeof item)) {
      throw new Error(`Compose ravenroot environment contains invalid value for ${key}`);
    }
    result.set(key, item === null ? undefined : String(item));
  }
  return result;
}

async function composeCollisions(root: string, desired: Readonly<Record<string, string>>): Promise<string[]> {
  const path = confined(root, "compose.yaml");
  if (!await exists(path)) throw new Error("Compose target requires compose.yaml for structural collision preflight");
  let document: unknown;
  try { document = parse(await readFile(path, "utf8")); }
  catch (error) { throw new Error(`Unable to parse Compose target for collision preflight: ${error instanceof Error ? error.message : String(error)}`); }
  const service = (document as { services?: Record<string, { environment?: unknown }> } | null)?.services?.ravenroot;
  if (!service) throw new Error("Compose target has no services.ravenroot for collision preflight");
  const existing = composeEnvironmentValues(service.environment);
  return Object.entries(desired).flatMap(([key, value]) => {
    if (!existing.has(key)) return [];
    const current = existing.get(key);
    if (current === value || current === `\${${key}:-}`) return [];
    return [`compose.yaml services.ravenroot.environment.${key} has a non-identical existing value`];
  });
}

async function defaultQueryRunner(command: readonly string[]): Promise<string> {
  return new Promise<string>((resolvePromise, reject) => {
    const child = spawn(command[0] as string, command.slice(1), { stdio: ["ignore", "pipe", "pipe"] });
    const output: Buffer[] = [];
    const errors: Buffer[] = [];
    let size = 0;
    child.stdout.on("data", (chunk: Buffer) => { size += chunk.length; if (size <= 4 * 1024 * 1024) output.push(chunk); });
    child.stderr.on("data", (chunk: Buffer) => { if (errors.reduce((sum, entry) => sum + entry.length, 0) < 64 * 1024) errors.push(chunk); });
    child.once("error", (error) => reject(new Error(`Kubernetes collision preflight could not execute ${command[0]}: ${error.message}`)));
    child.once("exit", (code) => {
      if (size > 4 * 1024 * 1024) reject(new Error("Kubernetes collision preflight response exceeded 4 MiB"));
      else if (code !== 0) reject(new Error(`Kubernetes collision preflight failed: ${Buffer.concat(errors).toString("utf8").trim() || `exit ${code}`}`));
      else resolvePromise(Buffer.concat(output).toString("utf8"));
    });
  });
}

async function queryKubernetesObject(runner: (command: readonly string[]) => Promise<string>, command: readonly string[]): Promise<Record<string, unknown> | undefined> {
  const raw = (await runner(command)).trim();
  if (!raw) return undefined;
  let value: unknown;
  try { value = JSON.parse(raw); } catch { throw new Error("Kubernetes collision preflight returned invalid JSON"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("Kubernetes collision preflight returned a non-object");
  return value as Record<string, unknown>;
}

interface HelmReleaseRow { readonly name: string; readonly revision: string; readonly status: string }

async function helmRelease(runner: (command: readonly string[]) => Promise<string>, release: string,
                           namespace: string): Promise<HelmReleaseRow | undefined> {
  const raw = (await runner(["helm", "list", "--namespace", namespace, "--filter", `^${release.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`, "-o", "json"])).trim();
  let value: unknown;
  try { value = JSON.parse(raw || "[]"); } catch { throw new Error("Helm recovery preflight returned invalid JSON"); }
  if (!Array.isArray(value)) throw new Error("Helm recovery preflight returned a non-list");
  const matches = value.filter((entry) => entry && typeof entry === "object" && (entry as { name?: unknown }).name === release);
  if (matches.length === 0) return undefined;
  if (matches.length !== 1) throw new Error(`Helm recovery preflight returned duplicate release ${release}`);
  const row = matches[0] as { name?: unknown; revision?: unknown; status?: unknown };
  const revision = String(row.revision ?? "");
  const status = String(row.status ?? "");
  if (!/^\d+$/.test(revision) || !status) throw new Error("Helm recovery preflight returned an invalid release row");
  return { name: release, revision, status };
}

async function kubernetesCollisions(pkg: DecodedPackage, environment: Readonly<Record<string, string>>,
                                    runner: (command: readonly string[]) => Promise<string>): Promise<string[]> {
  const namespace = String(pkg.manifest.target.options.namespace ?? "default");
  const release = String(pkg.manifest.target.options.release ?? "ravenroot");
  const deploymentName = String(pkg.manifest.target.options.deploymentName ?? `${release}-ravenroot`);
  const secretName = `ravenroot-config-${pkg.manifest.target.id.toLowerCase().replace(/[^a-z0-9-]/g, "-").slice(0, 40)}`;
  const get = (kind: string, name: string) => ["kubectl", "get", kind, name, "--namespace", namespace, "--ignore-not-found", "-o", "json"];
  const [deployment, secret] = await Promise.all([
    queryKubernetesObject(runner, get("deployment", deploymentName)),
    queryKubernetesObject(runner, get("secret", secretName))
  ]);
  const conflicts: string[] = [];
  const secretData = secret?.data;
  if (secretData && typeof secretData !== "object") throw new Error("Kubernetes configuration Secret has invalid data");
  for (const [key, value] of Object.entries(environment)) {
    const existing = (secretData as Record<string, unknown> | undefined)?.[key];
    if (existing !== undefined && existing !== yamlScalar(value)) conflicts.push(`Kubernetes Secret ${secretName} key ${key} has a non-identical existing value`);
  }
  for (const binding of pkg.manifest.secrets.filter((candidate) => candidate.mode === "target-reference")) {
    const referenced = await queryKubernetesObject(runner, get("secret", binding.kubernetesSecret as string));
    const data = referenced?.data;
    if (!data || typeof data !== "object" || Array.isArray(data) || typeof (data as Record<string, unknown>)[binding.kubernetesKey as string] !== "string") {
      throw new Error(`Kubernetes target reference ${binding.bindingId} cannot verify Secret ${binding.kubernetesSecret} key ${binding.kubernetesKey}`);
    }
  }
  if (!deployment) return conflicts;
  const spec = deployment.spec as { template?: { spec?: { containers?: unknown[] } } } | undefined;
  const containers = spec?.template?.spec?.containers;
  if (!Array.isArray(containers)) throw new Error("Kubernetes Deployment has no inspectable containers");
  const container = containers.find((item) => item && typeof item === "object" && (item as { name?: unknown }).name === "ravenroot") as {
    env?: unknown[]; envFrom?: unknown[]
  } | undefined;
  if (!container) throw new Error("Kubernetes Deployment has no ravenroot container for collision preflight");
  const expected = new Map<string, unknown>();
  for (const key of Object.keys(environment)) expected.set(key, { secretKeyRef: { name: secretName, key } });
  for (const binding of pkg.manifest.secrets.filter((candidate) => candidate.mode === "target-reference")) {
    expected.set(binding.environmentKey, { secretKeyRef: { name: binding.kubernetesSecret, key: binding.kubernetesKey } });
  }
  for (const entry of container.env ?? []) {
    if (!entry || typeof entry !== "object") throw new Error("Kubernetes Deployment contains an invalid env entry");
    const value = entry as { name?: unknown; value?: unknown; valueFrom?: unknown };
    if (typeof value.name !== "string" || !expected.has(value.name)) continue;
    if (value.valueFrom === undefined || canonicalJson(value.valueFrom) !== canonicalJson(expected.get(value.name))) {
      conflicts.push(`Kubernetes Deployment environment ${value.name} has a non-identical existing source`);
    }
  }
  for (const source of container.envFrom ?? []) {
    if (!source || typeof source !== "object") throw new Error("Kubernetes Deployment contains an invalid envFrom entry");
    const row = source as { prefix?: unknown; secretRef?: { name?: unknown }; configMapRef?: { name?: unknown } };
    const prefix = typeof row.prefix === "string" ? row.prefix : "";
    const kind = row.secretRef ? "secret" : row.configMapRef ? "configmap" : undefined;
    const name = row.secretRef?.name ?? row.configMapRef?.name;
    if (!kind || typeof name !== "string") throw new Error("Kubernetes Deployment contains an unverifiable envFrom source");
    const object = await queryKubernetesObject(runner, get(kind, name));
    if (!object) throw new Error(`Kubernetes Deployment envFrom ${kind}/${name} cannot be verified`);
    const data = object.data;
    if (!data || typeof data !== "object") continue;
    for (const key of Object.keys(data as Record<string, unknown>)) {
      const effective = `${prefix}${key}`;
      if (expected.has(effective)) conflicts.push(`Kubernetes Deployment envFrom ${kind}/${name} also defines ${effective}`);
    }
  }
  return conflicts;
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
    valueFrom: { secretKeyRef: { name: binding.kubernetesSecret as string,
      key: binding.kubernetesKey as string } }
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

async function prepareExternalSteps(pkg: DecodedPackage, root: string,
                                    runner: (command: readonly string[]) => Promise<string>): Promise<{
  steps: ExternalMutationStep[]; recoveryFiles: Record<string, Uint8Array>;
}> {
  const apply = commands(pkg, root);
  if (pkg.manifest.target.kind === "compose") {
    const restart = apply[0];
    return { steps: restart ? [{ id: "compose-recreate", apply: restart, compensate: [restart], mutatesTarget: true }] : [], recoveryFiles: {} };
  }
  if (pkg.manifest.target.kind === "prestart") {
    const restart = apply[0];
    if (!restart) return { steps: [], recoveryFiles: {} };
    const steps: ExternalMutationStep[] = [{ id: "prestart-restart", apply: restart, compensate: [restart], mutatesTarget: true }];
    const verifyRaw = pkg.manifest.target.options.verifyCommandJson;
    if (typeof verifyRaw === "string") {
      const verify = JSON.parse(verifyRaw) as string[];
      steps.push({ id: "prestart-effective-verification", apply: verify, compensate: [], mutatesTarget: false });
    }
    return { steps, recoveryFiles: {} };
  }
  const namespace = String(pkg.manifest.target.options.namespace ?? "default");
  const release = String(pkg.manifest.target.options.release ?? "ravenroot");
  const deployment = String(pkg.manifest.target.options.deploymentName ?? `${release}-ravenroot`);
  const secretName = `ravenroot-config-${pkg.manifest.target.id.toLowerCase().replace(/[^a-z0-9-]/g, "-").slice(0, 40)}`;
  const fileSecretName = `${secretName}-files`;
  const get = (kind: string, name: string) => ["kubectl", "get", kind, name, "--namespace", namespace, "--ignore-not-found", "-o", "json"];
  const recoveryFiles: Record<string, Uint8Array> = {};
  const compensationFor = async (kind: string, name: string): Promise<readonly string[]> => {
    const raw = (await runner(get(kind, name))).trim();
    if (!raw) return ["kubectl", "delete", kind, name, "--namespace", namespace, "--ignore-not-found"];
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error(`Kubernetes recovery snapshot for ${kind}/${name} is invalid`);
    const metadata = parsed.metadata as Record<string, unknown> | undefined;
    if (metadata) { delete metadata.resourceVersion; delete metadata.uid; delete metadata.creationTimestamp; delete metadata.managedFields; }
    delete parsed.status;
    const path = `recovery/${kind}-${name}.json`;
    recoveryFiles[path] = utf8(canonicalJson(parsed));
    return ["kubectl", "apply", "-f", `__BACKUP__/${path}`];
  };
  const deploymentCompensation = await compensationFor("deployment", deployment);
  const secretCompensations: (readonly string[])[] = [await compensationFor("secret", secretName)];
  if (pkg.manifest.configurations.some((selection) => EXTERNAL_DOCUMENTS.has(selection.contractId))) {
    secretCompensations.push(await compensationFor("secret", fileSecretName));
  }
  const previousRelease = await helmRelease(runner, release, namespace);
  const helmCompensation: readonly string[] = previousRelease
    ? ["helm", "rollback", release, previousRelease.revision, "--namespace", namespace, "--wait"]
    : ["helm", "uninstall", release, "--namespace", namespace, "--wait"];
  const steps: ExternalMutationStep[] = [];
  let cursor = 0;
  while (cursor < apply.length && apply[cursor]?.[0] === "docker") {
    steps.push({ id: `image-stage-${cursor}`, apply: apply[cursor]!, compensate: [], mutatesTarget: false }); cursor += 1;
  }
  steps.push({ id: "kubernetes-secret-apply", apply: apply[cursor++]!, compensate: secretCompensations, mutatesTarget: true });
  steps.push({ id: "helm-upgrade", apply: apply[cursor++]!, compensate: [helmCompensation], mutatesTarget: true });
  steps.push({ id: "deployment-patch", apply: apply[cursor++]!, compensate: [deploymentCompensation], mutatesTarget: true });
  steps.push({ id: "deployment-rollout", apply: apply[cursor++]!, compensate: [], mutatesTarget: false });
  return { steps, recoveryFiles };
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
  if (pkg.manifest.target.kind === "compose") {
    const references = targetReferenceEnvironment(pkg);
    const documentEnvironment = Object.fromEntries(resolvedInputs.documents.map((document) => [
      document.environmentKey, `/etc/ravenroot/configurator/${document.fileName}`
    ]));
    const desired = { ...environment, ...documentEnvironment,
      ...Object.fromEntries(Object.entries(references).map(([key, source]) => [key, `\${${source}}`])) };
    evaluated.conflicts.push(...await composeCollisions(root, desired));
  } else if (pkg.manifest.target.kind === "kubernetes") {
    evaluated.conflicts.push(...await kubernetesCollisions(pkg, environment, options.queryRunner ?? defaultQueryRunner));
  }
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
  const external = await prepareExternalSteps(pkg, root, options.queryRunner ?? defaultQueryRunner);
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
  return { source: pkg, plan, artifacts, environment, commands: targetCommands, externalSteps: external.steps,
    recoveryFiles: external.recoveryFiles, state };
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

async function verifyEffectiveInstall(prepared: PreparedInstall,
                                      runner: (command: readonly string[]) => Promise<string>): Promise<void> {
  const pkg = prepared.source;
  if (pkg.manifest.target.kind === "prestart") return;
  if (pkg.manifest.target.kind === "kubernetes") {
    const namespace = String(pkg.manifest.target.options.namespace ?? "default");
    const release = String(pkg.manifest.target.options.release ?? "ravenroot");
    const deploymentName = String(pkg.manifest.target.options.deploymentName ?? `${release}-ravenroot`);
    const get = (kind: string, name: string) => ["kubectl", "get", kind, name, "--namespace", namespace, "--ignore-not-found", "-o", "json"];
    const secretArtifact = prepared.artifacts.find((artifact) => artifact.path.endsWith("configuration-secret.yaml"));
    const patchArtifact = prepared.artifacts.find((artifact) => artifact.path.endsWith("deployment-patch.rrcfg.yaml"));
    if (!secretArtifact || !patchArtifact) throw new Error("Kubernetes verification artifacts are incomplete");
    const expectedSecrets = parseAllDocuments(text(secretArtifact.bytes)).map((document) => document.toJSON() as {
      metadata: { name: string }; data: Record<string, string>
    });
    for (const expected of expectedSecrets) {
      const actual = await queryKubernetesObject(runner, get("secret", expected.metadata.name));
      if (!actual) throw new Error(`Effective Kubernetes configuration is missing Secret ${expected.metadata.name}`);
      const data = actual.data;
      if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error(`Effective Kubernetes Secret ${expected.metadata.name} has invalid data`);
      for (const [key, value] of Object.entries(expected.data)) {
        if ((data as Record<string, unknown>)[key] !== value) throw new Error(`Effective Kubernetes Secret ${expected.metadata.name} differs for ${key}`);
      }
    }
    for (const binding of pkg.manifest.secrets.filter((candidate) => candidate.mode === "target-reference")) {
      const referenced = await queryKubernetesObject(runner, get("secret", binding.kubernetesSecret as string));
      const data = referenced?.data;
      if (!data || typeof data !== "object" || Array.isArray(data) || typeof (data as Record<string, unknown>)[binding.kubernetesKey as string] !== "string") {
        throw new Error(`Effective Kubernetes target reference ${binding.bindingId} is missing Secret ${binding.kubernetesSecret} key ${binding.kubernetesKey}`);
      }
    }
    const deployment = await queryKubernetesObject(runner, get("deployment", deploymentName));
    if (!deployment) throw new Error(`Effective Kubernetes configuration is missing Deployment ${deploymentName}`);
    const expectedPatch = parse(text(patchArtifact.bytes)) as {
      spec: { template: { spec: { containers: Array<{ name: string; env?: unknown[]; volumeMounts?: unknown[] }>; volumes?: unknown[] } } }
    };
    const actualSpec = (deployment.spec as { template?: { spec?: { containers?: unknown[]; volumes?: unknown[] } } } | undefined)?.template?.spec;
    const actualContainer = actualSpec?.containers?.find((entry) => entry && typeof entry === "object" && (entry as { name?: unknown }).name === "ravenroot") as {
      env?: unknown[]; volumeMounts?: unknown[]; image?: unknown
    } | undefined;
    if (!actualContainer) throw new Error("Effective Kubernetes Deployment has no ravenroot container");
    const expectedContainer = expectedPatch.spec.template.spec.containers[0]!;
    const actualEnv = new Map((actualContainer.env ?? []).map((entry) => [(entry as { name: string }).name, entry]));
    for (const entry of expectedContainer.env ?? []) {
      const name = (entry as { name: string }).name;
      if (canonicalJson(actualEnv.get(name)) !== canonicalJson(entry)) throw new Error(`Effective Kubernetes Deployment differs for environment ${name}`);
    }
    for (const required of expectedContainer.volumeMounts ?? []) {
      if (!(actualContainer.volumeMounts ?? []).some((actual) => canonicalJson(actual) === canonicalJson(required))) {
        throw new Error("Effective Kubernetes Deployment is missing a configuration file mount");
      }
    }
    for (const required of expectedPatch.spec.template.spec.volumes ?? []) {
      if (!(actualSpec?.volumes ?? []).some((actual) => canonicalJson(actual) === canonicalJson(required))) {
        throw new Error("Effective Kubernetes Deployment is missing a configuration file Secret volume");
      }
    }
    if (pkg.manifest.bundles.length && actualContainer.image !== pkg.manifest.target.options.derivedImage) {
      throw new Error("Effective Kubernetes Deployment has not activated the configured bundle image");
    }
    const deploymentMetadata = deployment.metadata as { generation?: unknown } | undefined;
    const desired = Number((deployment.spec as { replicas?: unknown } | undefined)?.replicas ?? 1);
    const status = deployment.status as { observedGeneration?: unknown; updatedReplicas?: unknown; availableReplicas?: unknown; conditions?: unknown[] } | undefined;
    if (!status || Number(status.observedGeneration) < Number(deploymentMetadata?.generation ?? 1)
      || Number(status.updatedReplicas ?? 0) < desired || Number(status.availableReplicas ?? 0) < desired
      || !status.conditions?.some((condition) => (condition as { type?: unknown; status?: unknown }).type === "Available"
        && (condition as { status?: unknown }).status === "True")) {
      throw new Error("Effective Kubernetes Deployment rollout is not available");
    }
    const activeRelease = await helmRelease(runner, release, namespace);
    if (!activeRelease || activeRelease.status.toLowerCase() !== "deployed") {
      throw new Error(`Effective Kubernetes Helm release ${release} is not deployed`);
    }
    return;
  }
  const root = dirname((prepared.externalSteps[0]?.apply[3] as string | undefined) ?? resolve("compose.yaml"));
  const command = ["docker", "compose", "-f", join(root, "compose.yaml"), "-f",
    confined(root, `${STATE_DIR}/compose/compose.rrcfg.yaml`), "config", "--format", "json"];
  const raw = await runner(command);
  let model: unknown; try { model = JSON.parse(raw); } catch { throw new Error("Compose effective configuration verification returned invalid JSON"); }
  const service = (model as { services?: Record<string, { environment?: unknown }> } | null)?.services?.ravenroot;
  if (!service) throw new Error("Compose effective configuration has no ravenroot service");
  const actual = composeEnvironmentValues(service.environment);
  for (const [key, digest] of Object.entries(prepared.state.environmentDigests)) {
    const value = actual.get(key);
    if (value === undefined || await sha256(utf8(value)) !== digest) throw new Error(`Compose effective configuration differs for ${key}`);
  }
  const composePrefix = command.slice(0, -3);
  const containerId = (await runner([...composePrefix, "ps", "-q", "ravenroot"])).trim();
  if (!/^[a-zA-Z0-9_.-]+$/.test(containerId)) throw new Error("Compose effective verification found no running ravenroot container");
  let inspected: unknown;
  try { inspected = JSON.parse(await runner(["docker", "inspect", containerId])); }
  catch { throw new Error("Compose runtime inspection returned invalid JSON"); }
  const row = Array.isArray(inspected) && inspected.length === 1 ? inspected[0] as {
    Config?: { Env?: unknown[] }; State?: { Running?: unknown; Health?: { Status?: unknown } }
  } : undefined;
  if (!row || row.State?.Running !== true || row.State.Health?.Status === "unhealthy") {
    throw new Error("Compose ravenroot container is not running and healthy");
  }
  const runtime = composeEnvironmentValues(row.Config?.Env);
  for (const [key, digest] of Object.entries(prepared.state.environmentDigests)) {
    const value = runtime.get(key);
    if (value === undefined || await sha256(utf8(value)) !== digest) throw new Error(`Compose running container differs for ${key}`);
  }
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
  for (const [path, bytes] of Object.entries(prepared.recoveryFiles)) await atomicWrite(confined(backupRoot, path), bytes, 0o600);
  const steps = prepared.externalSteps.map((step) => ({
    ...step,
    compensate: step.compensate.map((command) => command.map((part) => part.replaceAll("__BACKUP__", backupRoot)))
  }));
  let journal: TransactionJournal = { version: 2, transaction, steps, completed: [] };
  const journalPath = confined(backupRoot, "journal.json");
  const pendingPath = confined(root, `${STATE_DIR}/${PENDING_FILE}`);
  const persist = async (): Promise<void> => {
    await atomicWrite(journalPath, utf8(canonicalJson(journal)), 0o600);
    await atomicWrite(pendingPath, utf8(canonicalJson({ version: 2, transaction })), 0o600);
  };
  await persist();
  try {
    for (const artifact of prepared.artifacts) await atomicWrite(confined(root, artifact.path), artifact.bytes, artifact.mode);
    const state = { ...prepared.state, lastTransaction: transaction };
    await atomicWrite(confined(root, `${STATE_DIR}/${STATE_FILE}`), utf8(canonicalJson(state)), 0o600);
    if (options.execute) {
      const runner = options.commandRunner ?? defaultRunner;
      if (prepared.plan.restartRequired && !steps.some((step) => step.mutatesTarget)) {
        throw new Error("Executable installation requires a concrete target restart command");
      }
      const completed: string[] = [];
      for (const step of steps) {
        journal = { ...journal, completed: [...completed], inFlight: step.id }; await persist();
        await runner(step.apply, prepared.environment);
        completed.push(step.id); journal = { ...journal, completed: [...completed] }; delete (journal as { inFlight?: string }).inFlight; await persist();
      }
      await verifyEffectiveInstall(prepared, options.queryRunner ?? defaultQueryRunner);
      const baseUrl = prepared.source.manifest.target.options.verifyBaseUrl;
      if (typeof baseUrl === "string" && baseUrl) await verifyTarget(prepared.source, baseUrl);
    }
    await rm(pendingPath);
  } catch (failure) {
    await rollback(root, options.commandRunner);
    throw failure;
  }
}

export async function rollback(targetRoot: string,
                               commandRunner?: (command: readonly string[], environment: Readonly<Record<string, string>>) => Promise<void>): Promise<void> {
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
  const journalPath = confined(backupRoot, "journal.json");
  if (await exists(journalPath)) {
    const journal = JSON.parse(await readFile(journalPath, "utf8")) as TransactionJournal;
    if (journal.version !== 2 || journal.transaction !== transaction) throw new Error("Installer recovery journal is invalid");
    const applied = [...journal.completed];
    if (journal.inFlight && !applied.includes(journal.inFlight)) applied.push(journal.inFlight);
    const runner = commandRunner ?? defaultRunner;
    for (const id of applied.reverse()) {
      const step = journal.steps.find((candidate) => candidate.id === id);
      if (!step || !step.mutatesTarget) continue;
      for (const command of step.compensate) await runner(command, {});
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
