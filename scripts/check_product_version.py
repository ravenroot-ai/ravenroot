#!/usr/bin/env python3
"""Keep public product-version surfaces aligned with the root Maven project."""

from __future__ import annotations

import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?(?:\+[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?$")


def text(root: ET.Element, path: str) -> str | None:
    element = root.find(path, NS)
    return element.text.strip() if element is not None and element.text else None


def authoritative_version() -> str:
    root = ET.parse(ROOT / "ravenroot/pom.xml").getroot()
    version = text(root, "m:version")
    if version is None:
        raise ValueError("ravenroot/pom.xml has no direct project version")
    return version


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True
    )
    return [ROOT / item for item in result.stdout.decode("utf-8").split("\0") if item]


def errors(version: str) -> list[str]:
    findings: list[str] = []
    if not SEMVER.fullmatch(version):
        findings.append(f"ravenroot/pom.xml: {version!r} is not Semantic Versioning")
    files = tracked_files()
    for pom in sorted(path for path in files if path.name == "pom.xml"):
        project = ET.parse(pom).getroot()
        group = text(project, "m:groupId") or text(project, "m:parent/m:groupId")
        if group is None or not group.startswith("ai.ravenroot"):
            continue
        candidate = text(project, "m:version") or text(project, "m:parent/m:version")
        if candidate != version:
            findings.append(f"{pom.relative_to(ROOT)}: version {candidate!r} differs from {version!r}")
    for relative in ("ravenroot/ravenroot-ui/package.json", "ravenroot/ravenroot-ui/package-lock.json"):
        document = json.loads((ROOT / relative).read_text(encoding="utf-8"))
        if document.get("version") != version:
            findings.append(f"{relative}: root version differs from {version!r}")
        packages = document.get("packages")
        if isinstance(packages, dict) and packages.get("", {}).get("version") != version:
            findings.append(f"{relative}: packages[''] version differs from {version!r}")
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    coordinates = set(re.findall(r"<version>([^<]+)</version>", readme))
    if coordinates and coordinates != {version}:
        findings.append(f"README.md: Maven coordinates {sorted(coordinates)!r} differ from {version!r}")
    for tracked in files:
        if not tracked.is_file() or tracked.suffix in {".png", ".jpg", ".jpeg"}:
            continue
        if tracked.resolve() == Path(__file__).resolve():
            continue
        try:
            contents = tracked.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if "1.0.0-SNAPSHOT" in contents:
            findings.append(f"{tracked.relative_to(ROOT)}: legacy product version reintroduced")
    return findings


def main() -> int:
    version = authoritative_version()
    findings = errors(version)
    if findings:
        for finding in findings:
            print(finding, file=sys.stderr)
        return 1
    if "--print-version" in sys.argv:
        print(version)
    else:
        print(f"Validated product version {version}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
