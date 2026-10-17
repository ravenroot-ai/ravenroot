import { lstat, readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { validateBundleFiles } from "./bundles.js";
import type { BundlePayload } from "./types.js";

/** Reads one closed prebuilt bundle directory without following links. */
export async function readBundleDirectory(path: string): Promise<BundlePayload> {
  const files: Record<string, Uint8Array> = {};
  for (const name of await readdir(path)) {
    const full = join(path, name);
    const stat = await lstat(full);
    if (stat.isSymbolicLink() || !stat.isFile()) throw new Error(`Bundle entry is not an ordinary file: ${name}`);
    files[name] = new Uint8Array(await readFile(full));
  }
  return validateBundleFiles(files);
}
