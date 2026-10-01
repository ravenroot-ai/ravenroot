import { unzipSync, zipSync } from "fflate";
import { validateBundleFiles } from "./bundles.js";
import { canonicalJson, safeName, sha256, text, utf8 } from "./codec.js";
import { encryptSecret } from "./crypto.js";
import { CONTRACT_BY_ID, serializeSelection } from "./registry.js";
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
  safeName(input.target.id, "target id");
  if (!input.configurations.length) throw new Error("At least one configuration is required");
  const tenantIds = new Set(input.target.tenantIds.map((tenant) => safeName(tenant, "tenant id")));
  if (tenantIds.size !== input.target.tenantIds.length) throw new Error("Target tenant identities must be unique");
  for (const selection of input.configurations) {
    serializeSelection(selection);
    if (selection.identity.tenant && !tenantIds.has(selection.identity.tenant)) {
      throw new Error(`${selection.contractId} tenant is outside the target tenant identities`);
    }
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
  const manifest = JSON.parse(text(manifestBytes)) as PortableManifest;
  if (manifest.schema !== "ravenroot.config-package.v1" || manifest.connectorContractVersion !== 1) throw new Error("Package schema or connector contract is unsupported");
  if (!Array.isArray(manifest.entries) || !Array.isArray(manifest.configurations) || !Array.isArray(manifest.secrets)
      || !Array.isArray(manifest.bundles) || !manifest.target || typeof manifest.target.id !== "string") {
    throw new Error("Package manifest structure is invalid");
  }
  const secretIdentities = new Set<string>();
  for (const secret of manifest.secrets) {
    if (!secret || !["embedded", "target-reference"].includes(secret.mode)
        || typeof secret.bindingId !== "string" || typeof secret.environmentKey !== "string"
        || secretIdentities.has(secret.bindingId) || secretIdentities.has(secret.environmentKey)) {
      throw new Error("Package secret binding structure is invalid or duplicated");
    }
    secretIdentities.add(secret.bindingId);
    secretIdentities.add(secret.environmentKey);
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
  for (const selection of manifest.configurations) serializeSelection(selection);
  validateConfigurationSecrets(manifest.configurations, manifest.secrets);
  const declared = new Set(manifest.entries.map((entry) => entry.path));
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
  return { manifest, entries, digest: await sha256(bytes) };
}
