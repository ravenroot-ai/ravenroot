# `ravenroot-plugins/`

Drop-in plugin bundle convention directory. This file and `.gitkeep` are the only
things this directory contains by default; an empty directory here must produce, and is proven to
produce, exactly today's distribution — see `scripts/verify-empty-plugins-parity.sh`, referenced from
the Dockerfile. A non-zero exit from that script means "a difference was found, go read it," not
"the proof failed" — see its own docstring and the Reproducibility section below before treating it
as a pass/fail gate.

## What goes here

One subdirectory per bundle, named after the bundle's own `id` from its manifest is a reasonable
convention but not enforced; what the build actually looks for is any subdirectory that directly
contains a `ravenroot-plugin.json` manifest:

```
ravenroot-plugins/
  <plugin-id>/
    ravenroot-plugin.json   # required: the bundle manifest
    <main-artifact>.jar     # required: declared as mainArtifact in the manifest
    <dependency>.jar        # optional: declared under dependencyArtifacts
    LICENSE / NOTICE        # optional: tolerated without being declared
```

A top-level file that is not inside such a subdirectory (this README, `.gitkeep`, a stray file a
developer left here) is never treated as a bundle and is never copied into the image. A subdirectory
*without* a manifest is likewise not a bundle candidate and is left alone. A subdirectory *with* a
manifest that fails validation — a bad checksum, a path escape, a class in a reserved package, an
unrecognised field, anything `ravenroot-plugin.json`'s validator rejects — fails the build outright
rather than being silently skipped: presence of an invalid bundle here is a build error, not a
build warning.

## What happens at build time

The OCI build (see `Dockerfile`) copies only the subdirectories that pass validation into
`/opt/ravenroot/plugins/<plugin-id>/` inside the image, read-only, owned by the non-root runtime
user. Presence there does not make a bundle run: it still has to be named in the runtime allowlist
(`RAVENROOT_ENABLED_PLUGINS` or an equivalent `plugins.lock.yaml`) before the server will load it.

**This directory, and everything above, is the LOCAL/Compose path only.** It is what a developer or
integrator running `docker build .` (or `docker compose build`) against their own checkout gets, and
`scripts/verify-empty-plugins-parity.sh` / `scripts/verify-plugins-dir-confinement.sh` prove properties
of *that* build specifically. It is a separate path from the published image on GHCR — see below.

## Artifact-based image builds are a separate, opt-in-only path

`Dockerfile.ci` assembles an image from pre-built artifacts under `ci-artifacts/`; it never reads this
source convention directory. A publication pipeline must explicitly build, validate, and install any
selected bundle into `ci-artifacts/backend/plugins/` before invoking that Dockerfile. Leaving that
staging directory empty produces an image with no bundled plugins, as verified by
`scripts/verify-empty-plugins-parity-ci.sh`.

The runtime allowlist, `RAVENROOT_ENABLED_PLUGINS`, still governs activation on top of either path
unchanged: presence in the published image, exactly like presence in this directory, is not activation.

## Building and installing a bundle

`./plugin.sh`, at the repository root, is the developer-facing toolchain for everything up to that
point -- no Maven knowledge required beyond having it installed:

```sh
./plugin.sh build mail       # compiles ravenroot-extensions/ravenroot-mail, produces a copyable
                              # bundle under its own target/plugin-bundle/, without touching the
                              # module's source
./plugin.sh validate ravenroot/ravenroot-extensions/ravenroot-mail/target/plugin-bundle
./plugin.sh install ravenroot/ravenroot-extensions/ravenroot-mail/target/plugin-bundle
                              # validates again, then copies into ravenroot-plugins/<manifest-id>/ --
                              # never touches the runtime allowlist; installed is not enabled
./plugin.sh install --all --skip-tests
                              # builds every known extension, prevalidates the whole lot, and installs
                              # it here; jdbc is SKIPPED unless both driver options are supplied
./plugin.sh list             # shows every bundle here and whether it currently validates
./plugin.sh remove <id>      # removes an installed bundle by its convention-directory name
```

Every trust-sensitive step (parsing the manifest, verifying a checksum, deciding whether a bundle is
valid) runs through the same Java validator this directory's own contract is built on -- `plugin.sh`
itself never parses JSON or computes a checksum. See `./plugin.sh --help` for the full command
reference. Re-running `install --all` leaves byte-identical destinations `UNCHANGED`; a different
destination rejects the complete lot and must be removed explicitly before replacement. Installation
still does not activate a bundle or rebuild a local Compose image: configure the allowlist separately
and run `./service.sh restart -sb` before recreating the container. Batch installation also requires
each manifest id to be one losslessly represented ASCII directory component and rejects case-folded
target aliases before copying; this is an install-path rule, not a change to manifest identity.

## An alternative path

An integrator embedding Ravenroot in their own service can point the build at a different directory
via the `RAVENROOT_PLUGINS_DIR` build argument. **This is not confined by Docker itself the way an
earlier version of this file claimed.** A `..` or absolute path is not refused by Docker's build
context handling — it is *clamped* to the context root, silently, and the build proceeds with the
entire repository staged as "plugin source" (verified directly with a throwaway probe Dockerfile,
kept at `scripts/verify-plugins-dir-confinement.sh`). Combined with a directory that isn't a bundle
candidate simply being skipped rather than reported, that clamping could otherwise turn a typo or a
malicious value into a silent, empty plugins directory with nothing in the build log to explain why.
The Dockerfile therefore validates `RAVENROOT_PLUGINS_DIR` itself — rejecting anything empty,
absolute, or containing `..` — before any expensive work runs, and that check, not Docker's own
context handling, is what actually protects this build.

## Reproducibility: what a checksum proves today, and what it does not yet

Every artifact in a bundle is SHA-256 checksummed, and that checksum is verified before the artifact
is ever copied or activated. **That is tamper detection**: if a jar changes after the manifest that
describes it was written — in transit, at rest, or by a compromised build step — validation catches
it, every time.

**That is a different property from build reproducibility** — the ability for an independent party to
rebuild a bundle from the same source and get byte-identical output, so a published digest can be
verified against source rather than merely trusted. Ravenroot does not have that property yet. A
plugin's jar is compiled by the same Maven build as every other module in this repository, and that
build is not currently configured for reproducible output: two builds of the identical source commit
produce jars whose *content* is identical — every class and resource file matches by name and CRC-32
— but whose *overall file hash differs*, because each build stamps its own wall-clock build time into
every zip entry. Two independent builds produced
`ravenroot.jar` files where all 31,970 zip entries matched on name and CRC-32, and the only
difference was per-entry timestamps.

This is a repository-wide gap, not specific to plugin bundles, and closing it is tracked separately
as a repository-wide build concern — pinning a build timestamp in the shared Maven configuration,
not something this module can fix on its own. Until that is configured, a checksum mismatch
here always means real tampering or corruption, never build noise, because the check compares a
manifest against *this specific installed jar*, not against a second independently rebuilt one — but
two honest, independent builds of the same plugin source are not currently guaranteed to produce the
same checksum. Anyone asserting provenance from a bundle's digest (an SBOM entry, a signed manifest,
a "this was built from commit X" claim) should read it as "this exact artifact has not been altered
since it was checksummed," not yet as "an independent rebuild from source would match this digest."
