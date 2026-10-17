import { describe, expect, test } from "vitest";
import { utf8 } from "../src/codec.js";
import { decryptSecret, encryptSecret } from "../src/crypto.js";
import { validateBundleFiles } from "../src/bundles.js";
import { fakeBundle } from "./helpers.js";

describe("secret envelope and bundles", () => {
  test("binds ciphertext to its secret identity and rejects wrong passwords", async () => {
    const envelope = await encryptSecret("binding-a", "secret", "package-password");
    expect(await decryptSecret("binding-a", envelope, "package-password")).toBe("secret");
    await expect(decryptSecret("binding-b", envelope, "package-password")).rejects.toThrow("Unable to unlock");
    await expect(decryptSecret("binding-a", envelope, "wrong-password")).rejects.toThrow("Unable to unlock");
  });

  test("checks manifest compatibility, declared files, sizes, and digests", async () => {
    expect((await fakeBundle()).manifest.sdkContract).toBe("ravenroot.node-sdk/2");
    await expect(validateBundleFiles({ "ravenroot-plugin.json": utf8("{}") })).rejects.toThrow("schemaVersion");
    const valid = await fakeBundle();
    await expect(validateBundleFiles({ ...valid.files, "extra.jar": utf8("extra") })).rejects.toThrow("undeclared files");
  });
});
