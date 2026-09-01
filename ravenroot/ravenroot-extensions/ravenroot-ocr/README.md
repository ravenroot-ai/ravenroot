# Ravenroot OCR extension

`ai.ravenroot.extensions.ocr.OcrNodePackage` contributes the deterministic `ocr.extract` behavior.
It invokes one local Tesseract process under an operator-owned profile; it does not expose a generic
command runner and does not add URL, filesystem-path, PDF, vendor, or credential authority to a graph.

Build and validate the plugin bundle from the repository root:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./plugin.sh build ocr
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-ocr/target/plugin-bundle
```

Enable bundle id `ai.ravenroot.extensions.ocr`. The default resolver reads a tenant-scoped profile
from `RAVENROOT_OCR_PROFILE_<TENANT_HEX>_<PROFILE_HEX>`, where each identifier is encoded as uppercase
two-digit UTF-8 bytes. The value has exactly nine semicolon-separated fields:

```text
absoluteExecutable;absoluteTessdata;allowedLanguages;absoluteTempRoot;deadlineMs;maxInputBytes;maxOutputBytes;concurrency;shutdownMs
```

For tenant `tenant-a` and profile `local`, a profile for the optional image is:

```sh
RAVENROOT_OCR_PROFILE_74656E616E742D61_6C6F63616C='/usr/bin/tesseract;/usr/share/tesseract-ocr/5/tessdata;eng;/opt/ravenroot/ocr-tmp;10000;8388608;1048576;2;1000'
```

All three paths and every limit come from this trusted deployment profile. The graph may select the
opaque profile and one allow-listed language, then optionally lower `deadlineMs`, `maxInputBytes`,
`maxOutputBytes`, and `maxConcurrency`. It cannot supply a command, flag, path, language-data
directory, temporary root, or higher ceiling.

The `ocr.extract.v1` input is a closed object containing only `version` and one canonical, padded,
strict-Base64 `imageBase64`. PNG, JPEG, and TIFF are accepted after bounded decoding and structural
metadata checks, including an absolute 40-million-pixel ceiling. TIFF checks every directory in its
bounded chain, requires complete non-zero dimensions on every page, and charges cumulative pixel work
before any workspace or process can exist; every other format, including PDF, is rejected before process start. A successful
result has this shape:

```json
{
  "version": "ocr.extract.v1",
  "status": "EXTRACTED",
  "reason": "NONE",
  "language": "eng",
  "format": "PNG",
  "imageBytes": 2734,
  "width": 400,
  "height": 100,
  "pages": 1,
  "elapsedBucket": "100-499ms",
  "text": "RAVENROOT 42"
}
```

Failures return only stable statuses/reasons and safe image metadata. Child stderr, exception text,
temporary paths, executable paths, and process identifiers are never copied to the result or logged.
Admission is fail-fast and happens before Base64 decoding, directory creation, or process start. Each
accepted invocation uses an owner-only direct child of the explicit temporary root. Input size is
checked again on disk immediately before spawn. Every completion path deletes that directory.
Cancellation is cooperative at the Java future boundary and authoritative at the process boundary:
the extension closes stdin, terminates descendants before the root, waits only for the configured
shutdown bound, then forcibly kills and reaps survivors. Output and stderr are drained concurrently;
stdout is retained only through the configured byte ceiling and stderr is always discarded.

## Optional OCR image

The module-scoped [`container/Dockerfile`](container/Dockerfile) extends an already published
Ravenroot image and adds only the validated OCR bundle plus version-pinned Ubuntu Noble Tesseract
packages. It has no mutable base default: `RAVENROOT_IMAGE` must be an OCI index digest. This keeps
the standard distribution unchanged and makes the OCR/native dependency an explicit opt-in.

`container/build_attested.sh` is the build path, not a documentation-only command. It writes a
local OCI layout, Buildx metadata and `attestation-verification.json` to a **new** artifact
directory. The verifier re-hashes and size-checks every descriptor in the wrapper-index → image-index
→ platform-manifest → attestation-manifest → in-toto-statement chain. It rejects the artifact unless
every platform image manifest has both an SBOM and provenance attestation whose OCI reference digest
equals that exact manifest digest, and whose in-toto statement has a supported exact predicate and a
non-empty, format-specific SPDX or SLSA structured predicate. Opaque image layers are hash-checked
streamingly. Wrapper metadata, JSON descriptors, configs, and in-toto statements are each opened
once and read in bounded chunks: at most the declared size or the 8 MiB ceiling is retained, followed
by one detection byte, so descriptor sizes and filesystem metadata are never trusted as allocation
authority. The reviewed Buildx 0.33.0/BuildKit 0.29.0 arm64 artifact emits SPDX `SPDX-2.3` through
Syft 1.51.0; that is the exact supported SPDX version, together with exact `CC0-1.0` and
`SPDXRef-DOCUMENT` document constants. Unknown or future SPDX versions fail closed until reviewed.
BuildKit's SLSA v1 local export may use an empty builder id only with its exact BuildKit build type,
non-empty external parameters and resolved dependencies, and complete invocation id/start/finish
metadata. An empty in-toto
`subject` array is specific to Buildx's unnamed local OCI export used by this command: the immutable
`vnd.docker.reference.digest` annotation, which the verifier matches to the platform manifest, is the
authoritative binding. A named statement must instead declare that same platform digest. The evidence
file records the output OCI index digest and verified subject digests.

```sh
./plugin.sh build ocr
RAVENROOT_IMAGE='ghcr.io/example/ravenroot@sha256:<reviewed-index-digest>' \
RAVENROOT_OCR_ARTIFACT_DIR="$PWD/target/ocr-oci-artifact" \
ravenroot/ravenroot-extensions/ravenroot-ocr/container/build_attested.sh
cat target/ocr-oci-artifact/attestation-verification.json
python3 ravenroot/ravenroot-extensions/ravenroot-ocr/container/test_verify_attestations.py
```

The artifact contains the content-addressed image and its verification evidence without requiring
publication. A release pipeline may publish the same artifact, but it must retain its immutable
digest and attestations; do not replace it with a mutable tag in deployment. Multi-architecture
publication is valid only when the supplied Ravenroot index contains both requested platforms. The
image runs as UID/GID 10001, inherits Ravenroot's healthcheck and entrypoint, and adds no port publication. Run it with a read-only root,
all capabilities dropped, `no-new-privileges`, CPU/RAM/PID ceilings, the normal Ravenroot data mount,
and only a size-limited writable `/opt/ravenroot/ocr-tmp` for OCR. Bundled English data remains on the
immutable root; additional language data, if approved, is one read-only
`/opt/ravenroot/ocr-tessdata` mount selected by the operator profile.

After publication, the opt-in smoke verifies non-root/read-only hardening, liveness and readiness,
native OCR against the fixed checksum fixture, and bounded SIGTERM shutdown without publishing a
host port:

```sh
RAVENROOT_OCR_IMAGE='ghcr.io/example/ravenroot-ocr@sha256:<published-digest>' \
  ravenroot/ravenroot-extensions/ravenroot-ocr/container/smoke.sh
```

The live JUnit fixture is also capability-gated and downloads nothing. Set
`RAVENROOT_OCR_TEST_TESSERACT` and `RAVENROOT_OCR_TEST_TESSDATA` to absolute paths when Tesseract is
installed outside the common package-manager locations.
