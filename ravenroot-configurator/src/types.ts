export type TargetKind = "compose" | "kubernetes" | "prestart";

export type ScalarType = "string" | "integer" | "boolean" | "csv" | "json" | "secret-reference";

export interface FieldSpec {
  readonly name: string;
  readonly label: string;
  readonly type: ScalarType;
  readonly required: boolean;
  readonly sensitive?: boolean;
  readonly description?: string;
  readonly suggestion?: unknown;
  readonly runtimeDefault?: unknown;
  readonly allowed?: readonly string[];
  readonly minimum?: number;
  readonly maximum?: number;
}

export type ContractEncoding = "delimited" | "base64-json" | "plain-environment";

export interface ConfigurationContract {
  readonly id: string;
  readonly title: string;
  readonly family: string;
  readonly bundleId?: string;
  readonly nodeIds: readonly string[];
  readonly environment: string;
  readonly identity: readonly ("tenant" | "profile" | "reference")[];
  readonly encoding: ContractEncoding;
  readonly fields: readonly FieldSpec[];
  readonly delimiter?: string;
  readonly jsonTemplate?: Readonly<Record<string, unknown>>;
  readonly requiredCapabilities?: readonly string[];
  readonly externalRequirements?: readonly string[];
  readonly restartRequired: boolean;
  readonly credentialResolver: "none" | "shared" | "family";
}

export interface ContractIdentity {
  readonly tenant?: string;
  readonly profile?: string;
  readonly reference?: string;
}

export interface ConfigurationSelection {
  readonly contractId: string;
  readonly identity: ContractIdentity;
  readonly values: Readonly<Record<string, unknown>>;
}

export interface TargetReference {
  readonly mode: "target-reference";
  readonly bindingId: string;
  readonly environmentKey: string;
  readonly composeVariable?: string;
  readonly kubernetesSecret?: string;
  readonly kubernetesKey?: string;
}

export interface EmbeddedSecretInput {
  readonly mode: "embedded";
  readonly bindingId: string;
  readonly environmentKey: string;
  readonly value: string;
}

export type SecretInput = TargetReference | EmbeddedSecretInput;

export interface EmbeddedSecretEnvelope {
  readonly format: "ravenroot.encrypted-secret.v1";
  readonly kdf: {
    readonly name: "argon2id";
    readonly salt: string;
    readonly memoryKiB: number;
    readonly iterations: number;
    readonly parallelism: number;
  };
  readonly cipher: {
    readonly name: "aes-256-gcm";
    readonly nonce: string;
    readonly ciphertext: string;
  };
}

export interface SecretBindingManifest {
  readonly mode: "target-reference" | "embedded";
  readonly bindingId: string;
  readonly environmentKey: string;
  readonly entry?: string;
  readonly composeVariable?: string;
  readonly kubernetesSecret?: string;
  readonly kubernetesKey?: string;
}

export interface BundleArtifactManifest {
  readonly fileName: string;
  readonly sha256: string;
  readonly sizeBytes: number;
}

export interface BundleManifestSummary {
  readonly id: string;
  readonly version: string;
  readonly schemaVersion: string;
  readonly sdkContract: string;
  readonly artifacts: readonly BundleArtifactManifest[];
  readonly entryPrefix: string;
  readonly digest: string;
}

export interface BundlePayload {
  readonly manifest: BundleManifestSummary;
  readonly files: Readonly<Record<string, Uint8Array>>;
}

export interface PackageTarget {
  readonly kind: TargetKind;
  readonly id: string;
  readonly tenantIds: readonly string[];
  readonly options: Readonly<Record<string, string | number | boolean>>;
}

export interface PackageEntryDigest {
  readonly path: string;
  readonly sha256: string;
  readonly sizeBytes: number;
}

export interface PortableManifest {
  readonly schema: "ravenroot.config-package.v1";
  readonly createdAt: string;
  readonly connectorContractVersion: 1;
  readonly target: PackageTarget;
  readonly configurations: readonly ConfigurationSelection[];
  readonly secrets: readonly SecretBindingManifest[];
  readonly bundles: readonly BundleManifestSummary[];
  readonly requiredCapabilities: readonly string[];
  readonly entries: readonly PackageEntryDigest[];
  readonly plan: RedactedPlan;
}

export interface PortablePackage {
  readonly manifest: PortableManifest;
  readonly entries: Readonly<Record<string, Uint8Array>>;
  readonly bytes: Uint8Array;
  readonly digest: string;
}

export interface PlanChange {
  readonly kind: "environment" | "bundle" | "file" | "state" | "image";
  readonly target: string;
  readonly action: "add" | "keep" | "replace";
  readonly before?: string;
  readonly after: string;
  readonly sensitive: boolean;
}

export interface RedactedPlan {
  readonly version: 1;
  readonly targetKind: TargetKind;
  readonly targetId: string;
  readonly changes: readonly PlanChange[];
  readonly conflicts: readonly string[];
  readonly restartRequired: boolean;
  readonly restart: readonly string[];
  readonly verification: readonly string[];
}

export interface DecodedPackage {
  readonly manifest: PortableManifest;
  readonly entries: Readonly<Record<string, Uint8Array>>;
  readonly digest: string;
}
