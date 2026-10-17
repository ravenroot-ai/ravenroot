import { sha256, utf8 } from "../src/codec.js";
import { validateBundleFiles } from "../src/bundles.js";
import type { BundlePayload, ConfigurationSelection, PackageTarget } from "../src/types.js";

export const coreHttp: ConfigurationSelection = {
  contractId: "core.http",
  identity: {},
  values: {
    RAVENROOT_HTTP_ALLOWED_HOSTS: ["api.example.test"],
    RAVENROOT_HTTP_ALLOWED_PORTS: ["443"],
    RAVENROOT_EGRESS_RESERVED_EXCEPTIONS: [],
    RAVENROOT_HTTP_MAX_REQUEST_BYTES: 65536,
    RAVENROOT_HTTP_MAX_RESPONSE_BYTES: 1048576,
    RAVENROOT_ALLOWED_TOOLS: ["lookup"]
  }
};

export function serviceGrant(bundleId: string, capabilities: readonly string[]): ConfigurationSelection {
  return {
    contractId: "bundle.service-grant",
    identity: { profile: bundleId },
    values: { document: {
      capabilities, origins: [{ scheme: "https", host: "service.example.test", port: 443 }],
      httpMethods: ["GET", "POST"], requestHeaders: ["content-type"], responseHeaders: ["content-type"],
      webSocketSubprotocols: [], credentialBindings: [], awsSigV4Bindings: [], credentialReferences: ["credential"],
      limits: { maxRequestBytes: 1048576, maxResponseBytes: 4194304, maxDeadlineMs: 15000 }
    } }
  };
}

export function target(kind: PackageTarget["kind"], options: PackageTarget["options"] = {}): PackageTarget {
  return { kind, id: "test-target", tenantIds: ["tenant-a"], options };
}

export async function fakeBundle(id = "ai.ravenroot.extensions.ai"): Promise<BundlePayload> {
  const jar = utf8("closed prebuilt bundle bytes");
  const manifest = {
    schemaVersion: "1", id, version: "1.0.0", sdkContract: "ravenroot.node-sdk/2",
    nodePackageClasses: ["example.Package"], behaviors: ["llm-prompt"],
    mainArtifact: { fileName: "bundle.jar", sha256: await sha256(jar), sizeBytes: jar.byteLength },
    dependencyArtifacts: []
  };
  return validateBundleFiles({ "ravenroot-plugin.json": utf8(JSON.stringify(manifest)), "bundle.jar": jar });
}
