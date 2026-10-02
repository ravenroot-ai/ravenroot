const COMPONENT = "[a-z0-9]+(?:[._-][a-z0-9]+)*";
const REGISTRY = "(?:[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[0-9]{1,5})?/)";
const NAME = `(?:${REGISTRY})?${COMPONENT}(?:/${COMPONENT})*`;
const TAG = "[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}";
const DIGEST = "sha256:[0-9a-f]{64}";
const REFERENCE = new RegExp(`^${NAME}(?::${TAG})?(?:@${DIGEST})?$`);

/** Parse the deliberately conservative OCI/Docker reference subset emitted into commands and Dockerfiles. */
export function ociImageReference(value: unknown, label: string, pinned = false): string {
  if (typeof value !== "string" || !value || value.length > 512 || /[\u0000-\u0020\u007f]/.test(value)
      || !REFERENCE.test(value)) {
    throw new Error(`${label} must be a single-line OCI image reference`);
  }
  if (pinned && !/@sha256:[0-9a-f]{64}$/.test(value)) {
    throw new Error(`${label} must be pinned by a sha256 digest`);
  }
  return value;
}
