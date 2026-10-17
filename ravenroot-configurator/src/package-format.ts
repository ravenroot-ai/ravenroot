import { unzipSync, zipSync } from "fflate";
import { validateBundleFiles } from "./bundles.js";
import { canonicalJson, safeName, sha256, text, utf8 } from "./codec.js";
import { encryptSecret } from "./crypto.js";
import { CONTRACT_BY_ID, serializeSelection } from "./registry.js";
import { ociImageReference } from "./oci.js";
import type {
  BundlePayload, ConfigurationSelection, DecodedPackage, PackageEntryDigest, PackageTarget,
  PlanChange, PortableManifest, PortablePackage, RedactedPlan, SecretBindingManifest, SecretInput
} from "./types.js";

const MANIFEST_PATH = "manifest.json";
const MAX_PACKAGE_BYTES = 512 * 1024 * 1024;
const MAX_EXPANDED_BYTES = 1024 * 1024 * 1024;
const MAX_ENTRIES = 4096;
const SENSITIVE_DOCUMENT_FIELDS = new Set([
  "apikey", "accesskey", "capabilitysecretbase64", "clientsecret", "completionsecretbase64",
  "password", "passwordbase64", "privatekey", "secret", "secretaccesskey", "secretbase64",
  "signingsecret", "token", "tokenbase64"
]);
const TARGET_OPTIONS: Readonly<Record<PackageTarget["kind"], Readonly<Record<string, "string" | "boolean" | "json-command">>>> = {
  compose: { verifyBaseUrl: "string" },
  kubernetes: { namespace: "string", release: "string", deploymentName: "string", chart: "string", baseImage: "string",
    derivedImage: "string", pushImage: "boolean", verifyBaseUrl: "string" },
  prestart: { restartCommandJson: "json-command", verifyCommandJson: "json-command", verifyBaseUrl: "string" }
};

function exactObject(value: unknown, allowed: readonly string[], label: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
  const record = value as Record<string, unknown>;
  const extra = Object.keys(record).filter((key) => !allowed.includes(key));
  if (extra.length) throw new Error(`${label} contains unsupported fields: ${extra.join(", ")}`);
  return record;
}

function validateTarget(value: unknown): asserts value is PackageTarget {
  const target = exactObject(value, ["kind", "id", "tenantIds", "options"], "Package target");
  if (!new Set(["compose", "kubernetes", "prestart"]).has(String(target.kind))) throw new Error(`Unknown target kind: ${String(target.kind)}`);
  if (typeof target.id !== "string") throw new Error("Package target id is invalid");
  safeName(target.id, "target id");
  if (!Array.isArray(target.tenantIds) || target.tenantIds.some((tenant) => typeof tenant !== "string")) throw new Error("Package target tenant identities are invalid");
  const tenants = target.tenantIds.map((tenant) => safeName(tenant as string, "tenant id"));
  if (new Set(tenants).size !== tenants.length) throw new Error("Target tenant identities must be unique");
  const rules = TARGET_OPTIONS[target.kind as PackageTarget["kind"]];
  const options = exactObject(target.options, Object.keys(rules), `${String(target.kind)} target options`);
  for (const [key, item] of Object.entries(options)) {
    const rule = rules[key];
    if (rule === "boolean") { if (typeof item !== "boolean") throw new Error(`${key} must be true or false`); continue; }
    if (typeof item !== "string" || !item) throw new Error(`${key} must be a non-empty string`);
    if (rule === "json-command") {
      let command: unknown; try { command = JSON.parse(item); } catch { throw new Error(`${key} must be a JSON command array`); }
      if (!Array.isArray(command) || !command.length || command.some((part) => typeof part !== "string" || !part)) throw new Error(`${key} must be a non-empty JSON string array`);
    }
    if (key === "verifyBaseUrl") {
      let url: URL; try { url = new URL(item); } catch { throw new Error("verifyBaseUrl must be an HTTP(S) URL"); }
      if (!new Set(["http:", "https:"]).has(url.protocol) || url.username || url.password) throw new Error("verifyBaseUrl must be an HTTP(S) URL without credentials");
    }
    if (key === "baseImage") ociImageReference(item, "baseImage", true);
    if (key === "derivedImage") ociImageReference(item, "derivedImage");
  }
}

function validateSelection(selection: unknown, target: PackageTarget): asserts selection is ConfigurationSelection {
  const value = exactObject(selection, ["contractId", "identity", "values"], "Configuration selection");
  if (typeof value.contractId !== "string") throw new Error("Configuration contract id is invalid");
  const contract = CONTRACT_BY_ID.get(value.contractId);
  if (!contract) throw new Error(`Unknown configuration contract: ${value.contractId}`);
  const identity = exactObject(value.identity, contract.identity, `${value.contractId} identity`);
  for (const axis of contract.identity) if (typeof identity[axis] !== "string" || !identity[axis]) throw new Error(`${value.contractId} requires ${axis}`);
  if (typeof identity.tenant === "string" && !target.tenantIds.includes(identity.tenant)) throw new Error(`${value.contractId} tenant is outside the target tenant identities`);
  exactObject(value.values, Object.keys(value.values as object), `${value.contractId} values`);
  serializeSelection(value as unknown as ConfigurationSelection);
}

function validateManifestShape(value: unknown): asserts value is PortableManifest {
  const manifest = exactObject(value, ["schema", "createdAt", "connectorContractVersion", "target", "configurations", "secrets", "bundles", "requiredCapabilities", "entries", "plan"], "Package manifest");
  if (manifest.schema !== "ravenroot.config-package.v1" || manifest.connectorContractVersion !== 1) throw new Error("Package schema or connector contract is unsupported");
  if (typeof manifest.createdAt !== "string" || Number.isNaN(Date.parse(manifest.createdAt)) || new Date(manifest.createdAt).toISOString() !== manifest.createdAt) throw new Error("Package createdAt must be a canonical ISO timestamp");
  validateTarget(manifest.target);
  if (!Array.isArray(manifest.configurations) || !manifest.configurations.length) throw new Error("Package configurations are invalid");
  for (const selection of manifest.configurations) validateSelection(selection, manifest.target);
  if (!Array.isArray(manifest.secrets) || !Array.isArray(manifest.bundles) || !Array.isArray(manifest.entries) || !Array.isArray(manifest.requiredCapabilities)) throw new Error("Package manifest collections are invalid");
  const plan = exactObject(manifest.plan, ["version", "targetKind", "targetId", "changes", "conflicts", "restartRequired", "restart", "verification"], "Package plan");
  if (plan.version !== 1 || plan.targetKind !== manifest.target.kind || plan.targetId !== manifest.target.id || !Array.isArray(plan.changes)
      || !Array.isArray(plan.conflicts) || typeof plan.restartRequired !== "boolean" || !Array.isArray(plan.restart) || !Array.isArray(plan.verification)) {
    throw new Error("Package plan is not bound to its target");
  }
  for (const change of plan.changes) {
    const row = exactObject(change, ["kind", "target", "action", "before", "after", "sensitive"], "Package plan change");
    if (!new Set(["environment", "file", "bundle"]).has(String(row.kind)) || typeof row.target !== "string" || !row.target
        || !new Set(["add", "keep", "replace", "remove"]).has(String(row.action)) || typeof row.sensitive !== "boolean"
        || row.before !== undefined && typeof row.before !== "string" || row.after !== undefined && typeof row.after !== "string") {
      throw new Error("Package plan change is invalid");
    }
  }
  if (plan.conflicts.some((item) => typeof item !== "string") || plan.restart.some((item) => typeof item !== "string")
      || plan.verification.some((item) => typeof item !== "string")) throw new Error("Package plan text is invalid");
  if (manifest.requiredCapabilities.some((item) => typeof item !== "string" || !item)
      || new Set(manifest.requiredCapabilities).size !== manifest.requiredCapabilities.length) throw new Error("Package required capabilities are invalid");
}

export interface PackageInput {
  readonly target: PackageTarget;
  readonly configurations: readonly ConfigurationSelection[];
  readonly secrets: readonly SecretInput[];
  readonly bundles: readonly BundlePayload[];
  readonly password?: string;
  readonly createdAt?: string;
}

function validateBundleBindings(configurations: readonly ConfigurationSelection[], bundleIds: ReadonlySet<string>): void {
  const requiredBundles = new Set(configurations.map((selection) => CONTRACT_BY_ID.get(selection.contractId)?.bundleId)
    .filter((value): value is string => Boolean(value)));
  const missing = [...requiredBundles].filter((id) => !bundleIds.has(id));
  if (missing.length) throw new Error(`Selected configuration requires prebuilt bundles: ${missing.join(", ")}`);
  const unrelated = [...bundleIds].filter((id) => !requiredBundles.has(id));
  if (unrelated.length) throw new Error(`Package contains unselected bundles: ${unrelated.join(", ")}`);

  const grants = new Map<string, Set<string>>();
  for (const selection of configurations.filter((candidate) => candidate.contractId === "bundle.service-grant")) {
    const id = selection.identity.profile as string;
    if (grants.has(id)) throw new Error(`Package contains duplicate managed-service grant: ${id}`);
    const document = selection.values.document as Record<string, unknown>;
    const capabilities = document.capabilities;
    if (!Array.isArray(capabilities) || capabilities.some((capability) => typeof capability !== "string" || !capability)) {
      throw new Error(`Managed-service grant for ${id} has invalid capabilities`);
    }
    grants.set(id, new Set(capabilities));
  }
  const invalidGrants = [...grants.keys()].filter((id) => !bundleIds.has(id));
  if (invalidGrants.length) throw new Error(`Managed-service grants target unselected bundles: ${invalidGrants.join(", ")}`);
  const requiredByBundle = new Map<string, Set<string>>();
  for (const selection of configurations) {
    const contract = CONTRACT_BY_ID.get(selection.contractId);
    if (!contract?.bundleId || !contract.requiredCapabilities?.length) continue;
    const required = requiredByBundle.get(contract.bundleId) ?? new Set<string>();
    for (const capability of contract.requiredCapabilities) required.add(capability);
    requiredByBundle.set(contract.bundleId, required);
  }
  const missingGrants = [...requiredByBundle.keys()].filter((id) => !grants.has(id));
  if (missingGrants.length) throw new Error(`Selected configuration requires managed-service grants: ${missingGrants.join(", ")}`);
  for (const [id, required] of requiredByBundle) {
    const missingCapabilities = [...required].filter((capability) => !grants.get(id)?.has(capability));
    if (missingCapabilities.length) {
      throw new Error(`Managed-service grant for ${id} omits required capabilities: ${missingCapabilities.join(", ")}`);
    }
  }
}

function validateDocumentSecrets(value: unknown, embeddedBindings: ReadonlySet<string>, path = "configuration"): void {
  if (Array.isArray(value)) {
    value.forEach((item, index) => validateDocumentSecrets(item, embeddedBindings, `${path}[${index}]`));
    return;
  }
  if (!value || typeof value !== "object") return;
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 1 && entries[0]?.[0] === "$secret") {
    const bindingId = entries[0][1];
    if (typeof bindingId !== "string" || !embeddedBindings.has(bindingId)) {
      throw new Error(`${path} references an unavailable encrypted secret binding`);
    }
    return;
  }
  for (const [key, item] of entries) {
    const sensitive = SENSITIVE_DOCUMENT_FIELDS.has(key.toLowerCase().replace(/[^a-z0-9]/g, ""));
    const itemEntries = item && typeof item === "object" && !Array.isArray(item)
      ? Object.entries(item as Record<string, unknown>) : [];
    const placeholder = itemEntries.length === 1 && itemEntries[0]?.[0] === "$secret";
    if (sensitive && !placeholder) {
      throw new Error(`${path}.${key} is sensitive and must use an encrypted { "$secret": "binding-id" } placeholder`);
    }
    validateDocumentSecrets(item, embeddedBindings, `${path}.${key}`);
  }
}

function validateConfigurationSecrets(configurations: readonly ConfigurationSelection[], secrets: readonly SecretBindingManifest[]): void {
  const embedded = new Set(secrets.filter((secret) => secret.mode === "embedded").map((secret) => secret.bindingId));
  for (const selection of configurations) validateDocumentSecrets(selection.values, embedded, selection.contractId);
}

function preview(input: PackageInput): RedactedPlan {
  const changes: PlanChange[] = input.configurations.flatMap((selection) => Object.keys(serializeSelection(selection)).map((key) => ({
    kind: "environment" as const, target: key, action: "add" as const, after: "<configured>", sensitive: false
  })));
  for (const secret of input.secrets) changes.push({
    kind: "environment", target: secret.environmentKey, action: "add", after: `<secret:${secret.bindingId}>`, sensitive: true
  });
  for (const bundle of input.bundles) changes.push({
    kind: "bundle", target: bundle.manifest.id, action: "add", after: bundle.manifest.digest, sensitive: false
  });
  const restart = input.target.kind === "compose"
    ? ["docker compose up -d --build --force-recreate ravenroot"]
    : input.target.kind === "kubernetes"
      ? ["build or load the derived image", "apply the generated Secret and workload patch", "wait for rollout"]
      : ["restart the embedding process with the generated environment and bundle directory"];
  return {
    version: 1,
    targetKind: input.target.kind,
    targetId: input.target.id,
    changes,
    conflicts: [],
    restartRequired: input.configurations.some((selection) => CONTRACT_BY_ID.get(selection.contractId)?.restartRequired) || input.bundles.length > 0,
    restart,
    verification: ["verify target readiness", "verify required node IDs in the effective catalog"]
  };
}

export async function createPackage(input: PackageInput): Promise<PortablePackage> {
  validateTarget(input.target);
  if (!input.configurations.length) throw new Error("At least one configuration is required");
  validateConfigurationSecrets(input.configurations, input.secrets.map((secret) => ({
    mode: secret.mode, bindingId: secret.bindingId, environmentKey: secret.environmentKey
  })));
  const tenantIds = new Set(input.target.tenantIds);
  for (const selection of input.configurations) {
    validateSelection(selection, input.target);
  }
  const supplied = new Map(input.bundles.map((bundle) => [bundle.manifest.id, bundle]));
  if (supplied.size !== input.bundles.length) throw new Error("Package contains duplicate bundle IDs");
  validateBundleBindings(input.configurations, new Set(supplied.keys()));

  const entries: Record<string, Uint8Array> = {};
  const secrets: SecretBindingManifest[] = [];
  for (const secret of input.secrets) {
    safeName(secret.bindingId, "secret binding id");
    if (!/^[A-Z_][A-Z0-9_]*$/.test(secret.environmentKey)) throw new Error(`Invalid secret environment key: ${secret.environmentKey}`);
    if (secrets.some((candidate) => candidate.bindingId === secret.bindingId || candidate.environmentKey === secret.environmentKey)) {
      throw new Error(`Duplicate secret binding: ${secret.bindingId}`);
    }
    if (secret.mode === "target-reference") {
      if (input.target.kind === "kubernetes" && (!secret.kubernetesSecret || !secret.kubernetesKey)) {
        throw new Error(`Kubernetes target reference ${secret.bindingId} requires kubernetesSecret and kubernetesKey`);
      }
      if (input.target.kind !== "kubernetes" && !secret.composeVariable) {
        throw new Error(`${input.target.kind} target reference ${secret.bindingId} requires composeVariable`);
      }
      if (secret.composeVariable && !/^[A-Z_][A-Z0-9_]*$/.test(secret.composeVariable)) {
        throw new Error(`Target reference ${secret.bindingId} has an invalid environment variable`);
      }
      if (secret.kubernetesSecret && !/^[a-z0-9](?:[-a-z0-9.]*[a-z0-9])?$/.test(secret.kubernetesSecret)) {
        throw new Error(`Target reference ${secret.bindingId} has an invalid Kubernetes Secret name`);
      }
      if (secret.kubernetesKey && !/^[A-Za-z0-9._-]+$/.test(secret.kubernetesKey)) {
        throw new Error(`Target reference ${secret.bindingId} has an invalid Kubernetes Secret key`);
      }
    }
    if (secret.mode === "embedded") {
      if (!input.password) throw new Error("A password is required for embedded secrets");
      const entry = `secrets/${secret.bindingId}.json`;
      entries[entry] = utf8(canonicalJson(await encryptSecret(secret.bindingId, secret.value, input.password)));
      secrets.push({ mode: "embedded", bindingId: secret.bindingId, environmentKey: secret.environmentKey, entry });
    } else {
      secrets.push({
        mode: "target-reference", bindingId: secret.bindingId, environmentKey: secret.environmentKey,
        ...(secret.composeVariable ? { composeVariable: secret.composeVariable } : {}),
        ...(secret.kubernetesSecret ? { kubernetesSecret: secret.kubernetesSecret } : {}),
        ...(secret.kubernetesKey ? { kubernetesKey: secret.kubernetesKey } : {})
      });
    }
  }
  validateConfigurationSecrets(input.configurations, secrets);
  for (const bundle of input.bundles) {
    for (const [name, bytes] of Object.entries(bundle.files)) entries[`${bundle.manifest.entryPrefix}${name}`] = bytes;
  }
  entries["configurations.json"] = utf8(canonicalJson(input.configurations));

  const digests: PackageEntryDigest[] = [];
  for (const path of Object.keys(entries).sort()) digests.push({ path, sha256: await sha256(entries[path] as Uint8Array), sizeBytes: (entries[path] as Uint8Array).byteLength });
  const requiredCapabilities = [...new Set(input.configurations.flatMap((selection) => CONTRACT_BY_ID.get(selection.contractId)?.requiredCapabilities ?? []))].sort();
  const manifest: PortableManifest = {
    schema: "ravenroot.config-package.v1",
    createdAt: input.createdAt ?? new Date().toISOString(),
    connectorContractVersion: 1,
    target: input.target,
    configurations: input.configurations,
    secrets,
    bundles: input.bundles.map((bundle) => bundle.manifest),
    requiredCapabilities,
    entries: digests,
    plan: preview(input)
  };
  entries[MANIFEST_PATH] = utf8(canonicalJson(manifest));
  const zipEntries: Record<string, [Uint8Array, { level: 0; mtime: Date }]> = {};
  for (const path of Object.keys(entries).sort()) zipEntries[path] = [entries[path] as Uint8Array, { level: 0, mtime: new Date("1980-01-01T00:00:00Z") }];
  const bytes = zipSync(zipEntries);
  return { manifest, entries, bytes, digest: await sha256(bytes) };
}

export async function inspectPackage(bytes: Uint8Array): Promise<DecodedPackage> {
  if (bytes.byteLength < 22 || bytes.byteLength > MAX_PACKAGE_BYTES) throw new Error("Package size is outside the supported range");
  let entries: Record<string, Uint8Array>;
  try {
    let expanded = 0;
    let count = 0;
    entries = unzipSync(bytes, { filter: (entry) => {
      expanded += entry.originalSize;
      count += 1;
      if (count > MAX_ENTRIES || entry.originalSize > MAX_PACKAGE_BYTES || expanded > MAX_EXPANDED_BYTES) {
        throw new Error("Package expansion limits exceeded");
      }
      return true;
    } });
  } catch {
    throw new Error("Package is not a valid .rrcfg ZIP archive");
  }
  for (const path of Object.keys(entries)) {
    if (path.startsWith("/") || path.split("/").some((part) => part === ".." || part === "")) throw new Error(`Package contains unsafe path: ${path}`);
  }
  const manifestBytes = entries[MANIFEST_PATH];
  if (!manifestBytes) throw new Error("Package has no manifest.json");
  const parsed: unknown = JSON.parse(text(manifestBytes));
  validateManifestShape(parsed);
  const manifest = parsed;
  const secretIdentities = new Set<string>();
  for (const secret of manifest.secrets) {
    const rawSecret = exactObject(secret, ["mode", "bindingId", "environmentKey", "entry", "composeVariable", "kubernetesSecret", "kubernetesKey"], "Package secret binding");
    if (!secret || !["embedded", "target-reference"].includes(secret.mode)
        || typeof secret.bindingId !== "string" || typeof secret.environmentKey !== "string"
        || secretIdentities.has(secret.bindingId) || secretIdentities.has(secret.environmentKey)) {
      throw new Error("Package secret binding structure is invalid or duplicated");
    }
    exactObject(rawSecret, secret.mode === "embedded" ? ["mode", "bindingId", "environmentKey", "entry"]
      : ["mode", "bindingId", "environmentKey", "composeVariable", "kubernetesSecret", "kubernetesKey"], "Package secret binding");
    secretIdentities.add(secret.bindingId);
    secretIdentities.add(secret.environmentKey);
    safeName(secret.bindingId, "secret binding id");
    if (!/^[A-Z_][A-Z0-9_]*$/.test(secret.environmentKey)) throw new Error(`Invalid secret environment key: ${secret.environmentKey}`);
    if (secret.mode === "embedded" && (typeof secret.entry !== "string" || !secret.entry.startsWith("secrets/"))) {
      throw new Error(`Embedded secret ${secret.bindingId} has an invalid entry`);
    }
    if (secret.mode === "target-reference" && manifest.target.kind === "kubernetes"
        && (!secret.kubernetesSecret || !secret.kubernetesKey)) {
      throw new Error(`Kubernetes target reference ${secret.bindingId} is incomplete`);
    }
    if (secret.mode === "target-reference" && manifest.target.kind !== "kubernetes" && !secret.composeVariable) {
      throw new Error(`${manifest.target.kind} target reference ${secret.bindingId} is incomplete`);
    }
  }
  const configurationIdentities = new Set<string>();
  for (const selection of manifest.configurations) {
    serializeSelection(selection);
    const identity = canonicalJson([selection.contractId, selection.identity]);
    if (configurationIdentities.has(identity)) throw new Error(`Package contains duplicate configuration identity: ${selection.contractId}`);
    configurationIdentities.add(identity);
  }
  validateConfigurationSecrets(manifest.configurations, manifest.secrets);
  for (const bundle of manifest.bundles) {
    const row = exactObject(bundle, ["id", "version", "schemaVersion", "sdkContract", "artifacts", "entryPrefix", "digest"], "Package bundle");
    if (typeof row.id !== "string" || typeof row.version !== "string" || typeof row.schemaVersion !== "string"
        || typeof row.sdkContract !== "string" || typeof row.entryPrefix !== "string" || !row.entryPrefix.startsWith("bundles/")
        || !row.entryPrefix.endsWith("/") || typeof row.digest !== "string" || !/^[0-9a-f]{64}$/.test(row.digest)
        || !Array.isArray(row.artifacts)) throw new Error("Package bundle structure is invalid");
    for (const artifact of row.artifacts) {
      const file = exactObject(artifact, ["fileName", "sha256", "sizeBytes"], "Package bundle artifact");
      if (typeof file.fileName !== "string" || !/^[A-Za-z0-9._-]+$/.test(file.fileName)
          || typeof file.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(file.sha256)
          || !Number.isSafeInteger(file.sizeBytes) || Number(file.sizeBytes) < 0) throw new Error("Package bundle artifact is invalid");
    }
  }
  for (const entry of manifest.entries) {
    exactObject(entry, ["path", "sha256", "sizeBytes"], "Package entry digest");
    if (typeof entry.path !== "string" || !/^[A-Za-z0-9._/-]+$/.test(entry.path) || entry.path.startsWith("/") || entry.path.split("/").includes("..")
        || typeof entry.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(entry.sha256) || !Number.isSafeInteger(entry.sizeBytes) || entry.sizeBytes < 0) {
      throw new Error("Package entry digest is invalid");
    }
  }
  const declared = new Set(manifest.entries.map((entry) => entry.path));
  if (declared.size !== manifest.entries.length) throw new Error("Package declares duplicate entries");
  const undeclared = Object.keys(entries).filter((path) => path !== MANIFEST_PATH && !declared.has(path));
  if (undeclared.length) throw new Error(`Package contains undeclared entries: ${undeclared.join(", ")}`);
  const allowedEntries = new Set(["configurations.json",
    ...manifest.secrets.flatMap((secret) => secret.mode === "embedded" && secret.entry ? [secret.entry] : []),
    ...manifest.entries.map((entry) => entry.path).filter((path) => manifest.bundles.some((bundle) => path.startsWith(bundle.entryPrefix)))]);
  const unexpectedEntries = manifest.entries.map((entry) => entry.path).filter((path) => !allowedEntries.has(path));
  if (unexpectedEntries.length) throw new Error(`Package declares unsupported entries: ${unexpectedEntries.join(", ")}`);
  for (const entry of manifest.entries) {
    const content = entries[entry.path];
    if (!content) throw new Error(`Package entry is missing: ${entry.path}`);
    if (content.byteLength !== entry.sizeBytes || await sha256(content) !== entry.sha256) throw new Error(`Package entry failed integrity verification: ${entry.path}`);
  }
  const configurationsEntry = entries["configurations.json"];
  if (!configurationsEntry || text(configurationsEntry) !== canonicalJson(manifest.configurations)) {
    throw new Error("Package configurations entry does not exactly match the manifest");
  }
  for (const bundle of manifest.bundles) {
    if (bundle.schemaVersion !== "1" || bundle.sdkContract !== "ravenroot.node-sdk/2") throw new Error(`Bundle is incompatible: ${bundle.id}`);
    const files = Object.fromEntries(Object.entries(entries)
      .filter(([path]) => path.startsWith(bundle.entryPrefix))
      .map(([path, content]) => [path.slice(bundle.entryPrefix.length), content]));
    const validated = await validateBundleFiles(files);
    if (validated.manifest.id !== bundle.id || validated.manifest.version !== bundle.version
        || validated.manifest.digest !== bundle.digest) throw new Error(`Bundle manifest binding failed: ${bundle.id}`);
  }
  const suppliedBundles = new Set(manifest.bundles.map((bundle) => bundle.id));
  if (suppliedBundles.size !== manifest.bundles.length) throw new Error("Package contains duplicate bundle IDs");
  validateBundleBindings(manifest.configurations, suppliedBundles);
  const expectedCapabilities = [...new Set(manifest.configurations.flatMap((selection) =>
    CONTRACT_BY_ID.get(selection.contractId)?.requiredCapabilities ?? []))].sort();
  if (canonicalJson(expectedCapabilities) !== canonicalJson([...manifest.requiredCapabilities].sort())) {
    throw new Error("Package required capabilities do not match its configurations");
  }
  return { manifest, entries, digest: await sha256(bytes) };
}
