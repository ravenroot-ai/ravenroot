#!/usr/bin/env python3
"""Compare two Docker image filesystems for PLAT-12's empty-directory parity proof.

Exports each image's merged filesystem (`docker export` of a created-but-not-started container --
the union of every layer, exactly what would run) and builds a normalized manifest per entry: path,
type, mode, uid, gid, and a content digest for regular files (or the link target for symlinks).
Deliberately excludes mtime: two separate `docker build` invocations stamp different wall-clock times
into JAR entries and layer history even when source content is byte-identical, so comparing mtimes
would report "different" on every run regardless of whether anything actually changed -- that is a
broken instrument, not a finding.

A residual difference limited entirely to build-timestamp-sensitive bytes inside an unrelated,
pre-existing artifact (every one of ravenroot.jar's zip
entries had identical name+CRC32 between two builds of the same commit before PLAT-12, so the jar's
overall hash differed for a reason with nothing to do with plugins) is a known limitation of this
tool, not something it silently tolerates -- it is reported like any other difference, and a reader
is expected to verify it the same way: pull both jars and diff `unzip -v` entry name+CRC listings.
This script does not do that inspection automatically, on purpose, so a real content divergence
cannot hide behind an assumption that "it's probably just timestamps".

Before re-verifying reproducibility by any means OTHER than this script's own driver
(verify-empty-plugins-parity.sh, which always builds from a fresh Docker layer): `mvn clean` both
sides first. Running `mvn package` twice on the same working tree without `clean` can produce
reference.conf/version.conf differences -- 1538 lines
becoming 3077, 2 becoming 5. That is the shade plugin's AppendingTransformer appending onto the
already-shaded jar from the first run, not a reproducibility defect; the exact doubling is what
revealed it. It nearly shipped as a false finding. A fresh `docker build` never hits this (each build
starts from an unbuilt checkout), which is exactly why comparing anything other than two independent
`docker build` runs needs the same discipline restated here.

This is an adjudication tool, not a CI green gate: a non-zero exit means "a difference exists, go
read what it is," not "something is broken." Wiring it into CI as a pass/fail check without a human
reading the DIFFERENT output first will misreport the known, characterised gap this repository
currently has (see the docstring above) as a build failure on every run.

Usage: verify-empty-plugins-parity.py <image-a> <image-b>
Exit 0 and prints "IDENTICAL" if the manifests match exactly. Exit 1 and prints every path-level
difference otherwise -- for inspection, not as a CI pass/fail signal on its own.
"""
import hashlib
import io
import subprocess
import sys
import tarfile


def export_manifest(image: str) -> dict[str, tuple]:
    container = subprocess.run(
        ["docker", "create", image], check=True, capture_output=True, text=True
    ).stdout.strip()
    try:
        proc = subprocess.run(["docker", "export", container], check=True, capture_output=True)
        manifest: dict[str, tuple] = {}
        with tarfile.open(fileobj=io.BytesIO(proc.stdout)) as tar:
            for member in tar.getmembers():
                path = member.name.rstrip("/")
                if not path:
                    continue
                if member.isdir():
                    kind, content_key = "dir", None
                elif member.issym() or member.islnk():
                    kind = "symlink" if member.issym() else "hardlink"
                    content_key = member.linkname
                elif member.isfile():
                    kind = "file"
                    fobj = tar.extractfile(member)
                    digest = hashlib.sha256()
                    while True:
                        chunk = fobj.read(1024 * 1024)
                        if not chunk:
                            break
                        digest.update(chunk)
                    content_key = digest.hexdigest()
                else:
                    kind, content_key = f"other:{member.type}", None
                manifest[path] = (kind, oct(member.mode), member.uid, member.gid, content_key)
        return manifest
    finally:
        subprocess.run(["docker", "rm", container], check=True, capture_output=True)


def main() -> int:
    if len(sys.argv) != 3:
        print("Usage: verify-empty-plugins-parity.py <image-a> <image-b>", file=sys.stderr)
        return 2
    image_a, image_b = sys.argv[1], sys.argv[2]
    manifest_a = export_manifest(image_a)
    manifest_b = export_manifest(image_b)

    only_a = sorted(set(manifest_a) - set(manifest_b))
    only_b = sorted(set(manifest_b) - set(manifest_a))
    differing = sorted(
        path for path in (set(manifest_a) & set(manifest_b)) if manifest_a[path] != manifest_b[path]
    )

    if not only_a and not only_b and not differing:
        print(f"IDENTICAL: {len(manifest_a)} filesystem entries in both {image_a} and {image_b}")
        print("(path, type, mode, uid, gid, content-digest-or-link-target -- mtime deliberately excluded)")
        return 0

    print(f"DIFFERENT: {image_a} vs {image_b}")
    if only_a:
        print(f"-- {len(only_a)} entries only in {image_a}:")
        for path in only_a[:50]:
            print(f"   only-in-a: {path} {manifest_a[path]}")
    if only_b:
        print(f"-- {len(only_b)} entries only in {image_b}:")
        for path in only_b[:50]:
            print(f"   only-in-b: {path} {manifest_b[path]}")
    if differing:
        print(f"-- {len(differing)} entries present in both but different:")
        for path in differing[:50]:
            print(f"   a: {path} {manifest_a[path]}")
            print(f"   b: {path} {manifest_b[path]}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
