# Ravenroot filesystem extension

This optional node package contributes `filesystem.read` and `filesystem.write`. It is not part of
core or the standard image. An operator must build, install and explicitly enable the bundle:

```sh
./plugin.sh build filesystem
./plugin.sh install ravenroot/ravenroot-extensions/ravenroot-filesystem/target/plugin-bundle
```

The graph names only an opaque `filesystemProfile`, a slash-separated relative `path`, and optional
tighter `maxBytes`, `deadlineMs`, encoding and write mode. It cannot provide a root, operating-system
path, permission, glob, credential or concurrency ceiling.

## Operator profile

The tenant-scoped environment key is
`RAVENROOT_FILESYSTEM_PROFILE_<TENANT_HEX>_<PROFILE_HEX>`. Components are the strict UTF-8,
upper-case hex encoding used by `EnvironmentKeyCodec`. Its value has seven semicolon-separated
fields:

```text
canonicalAbsoluteRoot;read;write;allowedRelativeGlobs;maxBytes;maxConcurrency;deadlineMs
```

`read` and `write` are exactly `true` or `false`. Globs are comma-separated and use `/`; at least one
is required. The hard implementation ceilings are 64 MiB, 1024 concurrent invocations, a five-minute
deadline, 4096 Unicode code points and 256 path components. Graph values may only tighten byte and
deadline ceilings. The root must already exist as an ordinary directory. Ravenroot canonicalizes it
when the profile resolves and refuses a root whose leaf is a symbolic link.

The root must be a dedicated mount owned by the Ravenroot process. It must not be concurrently
modified by an untrusted principal or a second writer process. OS ownership and mount policy are the
authorization boundary; this extension has no credential mechanism and never changes permissions.

The runtime requires the filesystem provider's `SecureDirectoryStream`. Every component and leaf is
opened relative to already-open directory descriptors with `NOFOLLOW_LINKS`; providers without that
primitive fail closed with `SECURITY_UNSUPPORTED`. Linux's standard Unix provider supports it. The
standard macOS provider does not, so this package deliberately refuses there rather than weakening
confinement. Paths reject POSIX/Windows absolute forms, drive/UNC syntax, backslashes, empty, `.` or
`..` components, NUL and all C0/C1 controls. Unicode code points are preserved, not normalized. The
versioned `.ravenroot-fs-private-v1-` component prefix is reserved for implementation-owned atomic
write artifacts and is refused even when a broad profile glob would otherwise match it. The former
`.ravenroot-fs-` prefix is not reserved; ordinary targets such as `.ravenroot-fs-report` remain valid.

## Protocols and atomicity

`filesystem.read.v1` input is exactly `{"version":"filesystem.read.v1"}`. Output uses the same
version and contains `encoding`, exactly one `text` or `base64`, `bytes` and lowercase SHA-256. UTF-8
decoding reports malformed input; Base64 is the canonical padded Basic alphabet with no whitespace.
Reads stop at the configured ceiling plus one excess byte and never return a partial body.

`filesystem.write.v1` input contains `version` and exactly one `text` or `base64`, matching the node's
encoding. `create-new` refuses an existing target; `replace` requires one. The result version is
`filesystem.write.result.v1` with `CREATED` or `REPLACED`, byte count and SHA-256. Append, delete,
chmod, glob expansion and directory enumeration are not supported.

Writes create an unpredictable `CREATE_NEW` temporary file in the target directory, write a bounded
body, call `FileChannel.force(true)`, recheck the target through the directory descriptor, then call
`SecureDirectoryStream.move`. There is no non-atomic fallback. A provider that will not atomically
replace reports `ATOMIC_REPLACE_UNSUPPORTED`. Timeout before the move leaves the target unchanged;
timeout or failure after move ownership begins reports `AMBIGUOUS_FINAL_MOVE`, and Ravenroot never
retries it automatically. The graph author must set the canonical recovery-repeatability property.
Process-wide target stripes make concurrent `create-new` deterministic and prevent this package's own
writes from racing, but do not replace the dedicated single-writer mount requirement.

Failed attempts remove only their own exact private temporary name. Each name carries a stable
profile-and-canonical-root fingerprint, a runtime-generation token, a target-name fingerprint and an
unpredictable nonce. On the first later access to a target directory, a descriptor-relative bounded
sweep removes regular files older than 24 hours only when the complete versioned grammar and the
active profile/root fingerprint match. This permits restart cleanup across generation tokens without
capturing another profile's artifacts. Unexpired files, malformed lookalikes, symbolic links,
directories and all graph-addressable names are preserved.

Failures expose only stable reason tokens: `NOT_FOUND`, `CONFLICT`, `OUTSIDE_ROOT`,
`SYMLINK_REFUSED`, `TOO_LARGE`, `INVALID_ENCODING`, `TIMEOUT`, `TEMPORARY_IO`,
`SECURITY_UNSUPPORTED`, `ATOMIC_REPLACE_UNSUPPORTED` and `AMBIGUOUS_FINAL_MOVE` (plus profile,
authority and admission refusals). Root and target paths and raw `IOException` text never reach the
result envelope.

## Linux/container smoke

The focused test suite detects `SecureDirectoryStream`. On Linux it runs the complete read/write,
root/parent/leaf symlink-swap, max/max+1, concurrent publication and restart-cleanup matrix; on an
unsupported provider only the explicit fail-closed test runs. After copying the checkout into the
build volume and making the two named volumes writable by UID/GID 10001, the reproducible container
gate runs Maven as that non-root identity, with a read-only container root, dedicated writable build
and Maven-cache volumes, and a dedicated writable `/tmp` test mount:

```sh
docker run --rm --read-only --user 10001:10001 \
  --tmpfs /tmp:rw,exec,nosuid,size=64m \
  -v ravenroot_build:/workspace \
  -v ravenroot_m2:/m2 \
  -w /workspace maven:3.9.11-eclipse-temurin-21 \
  mvn -Dmaven.repo.local=/m2 -f ravenroot/pom.xml \
    -pl ravenroot-extensions/ravenroot-filesystem -am \
    '-Dtest=Filesystem*Test,EnvironmentFilesystemProfileResolverTest' \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

The test selector compiles the complete upstream reactor slice but runs only this extension's Linux
smoke matrix. The ordinary host reactor verification remains the gate for every upstream test suite.

The package has no third-party runtime dependency. Bundle generation and validation therefore stage
only its own jar; `plugin.sh` discovers the module and `FilesystemNodePackage` without a hard-coded
table.
