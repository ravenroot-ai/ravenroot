import { canonicalJson, sha256, text, utf8 } from "./codec.js";
import type { BundleArtifactManifest, BundleManifestSummary, BundlePayload } from "./types.js";

interface RawArtifact {
  readonly fileName: string;
  readonly sha256: string;
  readonly sizeBytes: number;
}

interface RawPluginManifest {
  readonly schemaVersion: string;
  readonly id: string;
  readonly version: string;
  readonly sdkContract: string;
  readonly nodePackageClasses: readonly string[];
  readonly behaviors: readonly string[];
  readonly nodeCapabilities?: readonly string[];
  readonly mainArtifact: RawArtifact;
  readonly dependencyArtifacts?: readonly RawArtifact[];
}

function artifact(value: unknown, label: string): RawArtifact {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(`${label} must be an object`);
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.fileName !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]*\.jar$/.test(candidate.fileName)) {
    throw new Error(`${label} has an invalid artifact filename`);
  }
  if (typeof candidate.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(candidate.sha256)) throw new Error(`${label} has an invalid SHA-256`);
  if (!Number.isSafeInteger(candidate.sizeBytes) || Number(candidate.sizeBytes) <= 0) throw new Error(`${label} has an invalid size`);
  return { fileName: candidate.fileName, sha256: candidate.sha256, sizeBytes: Number(candidate.sizeBytes) };
}

function parseManifest(bytes: Uint8Array): RawPluginManifest {
  const decoded = JSON.parse(text(bytes)) as Record<string, unknown>;
  const allowed = new Set(["schemaVersion", "id", "version", "sdkContract", "nodePackageClasses", "behaviors", "nodeCapabilities", "mainArtifact", "dependencyArtifacts"]);
  const unknown = Object.keys(decoded).filter((key) => !allowed.has(key));
  if (unknown.length) throw new Error(`Plugin manifest has unknown fields: ${unknown.join(", ")}`);
  if (decoded.schemaVersion !== "1") throw new Error("Plugin manifest schemaVersion is not supported");
  if (typeof decoded.id !== "string" || !/^[a-z][a-z0-9.-]+$/.test(decoded.id)) throw new Error("Plugin manifest id is invalid");
  if (typeof decoded.version !== "string" || !decoded.version.trim()) throw new Error("Plugin manifest version is invalid");
  if (decoded.sdkContract !== "ravenroot.node-sdk/2") throw new Error("Plugin bundle targets an incompatible Node SDK contract");
  if (!Array.isArray(decoded.nodePackageClasses) || decoded.nodePackageClasses.length === 0 || decoded.nodePackageClasses.some((item) => typeof item !== "string" || !item)) {
    throw new Error("Plugin manifest nodePackageClasses are invalid");
  }
  if (!Array.isArray(decoded.behaviors) || decoded.behaviors.length === 0 || decoded.behaviors.some((item) => typeof item !== "string" || !item)) {
    throw new Error("Plugin manifest behaviors are invalid");
  }
  const dependencies = decoded.dependencyArtifacts === undefined ? [] : decoded.dependencyArtifacts;
  if (!Array.isArray(dependencies)) throw new Error("Plugin manifest dependencyArtifacts must be an array");
  return {
    schemaVersion: decoded.schemaVersion,
    id: decoded.id,
    version: decoded.version,
    sdkContract: decoded.sdkContract,
    nodePackageClasses: decoded.nodePackageClasses as string[],
    behaviors: decoded.behaviors as string[],
    ...(decoded.nodeCapabilities === undefined ? {} : { nodeCapabilities: decoded.nodeCapabilities as string[] }),
    mainArtifact: artifact(decoded.mainArtifact, "mainArtifact"),
    dependencyArtifacts: dependencies.map((item, index) => artifact(item, `dependencyArtifacts[${index}]`))
  };
}

export async function validateBundleFiles(files: Readonly<Record<string, Uint8Array>>): Promise<BundlePayload> {
  const names = Object.keys(files).sort();
  if (!names.includes("ravenroot-plugin.json")) throw new Error("Bundle is missing ravenroot-plugin.json");
  if (names.some((name) => name.includes("..") || name.includes("/") || name.includes("\\") || name.startsWith("."))) {
    throw new Error("Bundle contains an unsafe or nested filename");
  }
  const manifestBytes = files["ravenroot-plugin.json"] as Uint8Array;
  const manifest = parseManifest(manifestBytes);
  const declared = [manifest.mainArtifact, ...(manifest.dependencyArtifacts ?? [])];
  const allowedNames = new Set(["ravenroot-plugin.json", ...declared.map((entry) => entry.fileName)]);
  const undeclared = names.filter((name) => !allowedNames.has(name));
  if (undeclared.length) throw new Error(`Bundle contains undeclared files: ${undeclared.join(", ")}`);
  for (const entry of declared) {
    const bytes = files[entry.fileName];
    if (!bytes) throw new Error(`Bundle artifact is missing: ${entry.fileName}`);
    if (bytes.byteLength !== entry.sizeBytes) throw new Error(`Bundle artifact size mismatch: ${entry.fileName}`);
    if (await sha256(bytes) !== entry.sha256) throw new Error(`Bundle artifact digest mismatch: ${entry.fileName}`);
  }
  const summaryBase = {
    id: manifest.id,
    version: manifest.version,
    schemaVersion: manifest.schemaVersion,
    sdkContract: manifest.sdkContract,
    artifacts: declared as readonly BundleArtifactManifest[],
    entryPrefix: `bundles/${manifest.id}/`
  };
  const digest = await sha256(utf8(canonicalJson({ manifest: JSON.parse(text(manifestBytes)), files: await Promise.all(names.map(async (name) => ({ name, digest: await sha256(files[name] as Uint8Array) }))) })));
  const summary: BundleManifestSummary = { ...summaryBase, digest };
  return { manifest: summary, files };
}
