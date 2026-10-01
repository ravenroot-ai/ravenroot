import { argon2id } from "@noble/hashes/argon2";
import { base64, fromBase64, utf8 } from "./codec.js";
import type { EmbeddedSecretEnvelope } from "./types.js";

const MEMORY_KIB = 19456;
const ITERATIONS = 2;
const PARALLELISM = 1;

function random(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

async function key(password: string, salt: Uint8Array, parameters = {
  memoryKiB: MEMORY_KIB,
  iterations: ITERATIONS,
  parallelism: PARALLELISM
}): Promise<CryptoKey> {
  if (password.length < 12) throw new Error("Package password must contain at least 12 characters");
  if (parameters.memoryKiB < MEMORY_KIB || parameters.iterations < ITERATIONS || parameters.parallelism < 1) {
    throw new Error("Encrypted secret uses unsupported weak Argon2id parameters");
  }
  const material = argon2id(utf8(password), salt, {
    m: parameters.memoryKiB,
    t: parameters.iterations,
    p: parameters.parallelism,
    dkLen: 32
  });
  try {
    return await crypto.subtle.importKey("raw", material as BufferSource, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
  } finally {
    material.fill(0);
  }
}

export async function encryptSecret(bindingId: string, value: string, password: string): Promise<EmbeddedSecretEnvelope> {
  const salt = random(16);
  const nonce = random(12);
  const encryptionKey = await key(password, salt);
  const clear = utf8(value);
  try {
    const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce as BufferSource, additionalData: utf8(bindingId) as BufferSource }, encryptionKey, clear as BufferSource);
    return {
      format: "ravenroot.encrypted-secret.v1",
      kdf: { name: "argon2id", salt: base64(salt), memoryKiB: MEMORY_KIB, iterations: ITERATIONS, parallelism: PARALLELISM },
      cipher: { name: "aes-256-gcm", nonce: base64(nonce), ciphertext: base64(new Uint8Array(ciphertext)) }
    };
  } finally {
    clear.fill(0);
  }
}

export async function decryptSecret(bindingId: string, envelope: EmbeddedSecretEnvelope, password: string): Promise<string> {
  if (envelope.format !== "ravenroot.encrypted-secret.v1" || envelope.kdf.name !== "argon2id" || envelope.cipher.name !== "aes-256-gcm") {
    throw new Error("Unsupported encrypted secret format");
  }
  const decryptionKey = await key(password, fromBase64(envelope.kdf.salt), envelope.kdf);
  try {
    const clear = await crypto.subtle.decrypt({
      name: "AES-GCM",
      iv: fromBase64(envelope.cipher.nonce) as BufferSource,
      additionalData: utf8(bindingId) as BufferSource
    }, decryptionKey, fromBase64(envelope.cipher.ciphertext) as BufferSource);
    return new TextDecoder("utf-8", { fatal: true }).decode(clear);
  } catch {
    throw new Error(`Unable to unlock embedded secret ${bindingId}`);
  }
}
