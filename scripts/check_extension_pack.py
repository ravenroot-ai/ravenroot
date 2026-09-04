#!/usr/bin/env python3
"""Verify the first-party NodePackage dependency pack and its release evidence."""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = ROOT / "ravenroot/ravenroot-extensions"
PACK_ARTIFACT = "ravenroot-extensions-all"
PACK = EXTENSIONS / PACK_ARTIFACT
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
NODE_PACKAGE_IMPLEMENTATION = re.compile(r"\bimplements\s+NodePackage\b")
RAVENROOT_COORDINATE = re.compile(r"ai\.ravenroot:([^:]+):")


class ExtensionPackError(ValueError):
    """Raised when the reviewed extension-pack boundary drifts."""


def project(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def value(element: ET.Element, path: str) -> str | None:
    found = element.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def artifact_id(pom: Path) -> str:
    artifact = value(project(pom), "m:artifactId")
    if not artifact:
        raise ExtensionPackError(f"Maven module has no artifactId: {pom.relative_to(ROOT)}")
    return artifact


def implements_node_package(module: Path) -> bool:
    source_root = module / "src/main/java"
    return any(
        NODE_PACKAGE_IMPLEMENTATION.search(source.read_text(encoding="utf-8"))
        for source in sorted(source_root.rglob("*.java"))
    ) if source_root.is_dir() else False


def production_node_package_modules() -> dict[str, Path]:
    modules: dict[str, Path] = {}
    for pom in sorted(EXTENSIONS.glob("*/pom.xml")):
        module = pom.parent
        if module == PACK or not implements_node_package(module):
            continue
        artifact = artifact_id(pom)
        if artifact in modules:
            raise ExtensionPackError(f"duplicate extension artifactId: {artifact}")
        modules[artifact] = module
    if not modules:
        raise ExtensionPackError("no production first-party NodePackage modules were discovered")
    return modules


def parent_modules() -> set[str]:
    parent = project(EXTENSIONS / "pom.xml")
    if value(parent, "m:packaging") != "pom":
        raise ExtensionPackError("ravenroot-extensions must remain a packaging=pom reactor parent")
    declared = {
        module.text.strip()
        for module in parent.findall("m:modules/m:module", NS)
        if module.text and module.text.strip()
    }
    if PACK_ARTIFACT not in declared:
        raise ExtensionPackError("ravenroot-extensions does not declare ravenroot-extensions-all")
    return declared


def dependency_artifacts(pack: ET.Element) -> set[str]:
    artifacts: set[str] = set()
    for dependency in pack.findall("m:dependencies/m:dependency", NS):
        group = value(dependency, "m:groupId")
        artifact = value(dependency, "m:artifactId")
        version = value(dependency, "m:version")
        scope = value(dependency, "m:scope")
        dependency_type = value(dependency, "m:type")
        classifier = value(dependency, "m:classifier")
        optional = value(dependency, "m:optional")
        if group != "ai.ravenroot" or not artifact:
            raise ExtensionPackError("the extension pack may depend only on reviewed ai.ravenroot artifacts")
        if artifact in artifacts:
            raise ExtensionPackError(f"duplicate extension-pack dependency: {artifact}")
        if version != "${project.version}":
            raise ExtensionPackError(
                f"{artifact} must use the exact project version, found {version!r}"
            )
        if scope not in (None, "compile") or dependency_type not in (None, "jar"):
            raise ExtensionPackError(f"{artifact} must be an ordinary compile-scope JAR dependency")
        if classifier is not None or optional not in (None, "false"):
            raise ExtensionPackError(f"{artifact} must not use a classifier or optional resolution")
        if dependency.find("m:exclusions", NS) is not None:
            raise ExtensionPackError(f"{artifact} must not hide transitive dependencies with exclusions")
        artifacts.add(artifact)
    return artifacts


def check_no_ambient_activation(modules: dict[str, Path]) -> None:
    provider = Path("src/main/resources/META-INF/services/ai.ravenroot.api.node.NodePackage")
    offenders = [
        path.relative_to(ROOT)
        for path in (*modules.values(), PACK)
        if (path / provider).exists()
    ]
    if offenders:
        raise ExtensionPackError(f"NodePackage ServiceLoader providers are forbidden: {offenders}")


def check_distribution_boundary(expected: set[str]) -> None:
    distribution = project(ROOT / "ravenroot/ravenroot-distribution/pom.xml")
    artifacts = {
        value(dependency, "m:artifactId")
        for dependency in distribution.findall("m:dependencies/m:dependency", NS)
        if value(dependency, "m:groupId") == "ai.ravenroot"
    }
    forbidden = expected | {PACK_ARTIFACT}
    included = sorted(artifact for artifact in artifacts if artifact in forbidden)
    if included:
        raise ExtensionPackError(
            f"the default distribution must not depend on the optional extension pack: {included}"
        )


def check_pack() -> set[str]:
    expected_modules = production_node_package_modules()
    expected = set(expected_modules)
    declared_modules = parent_modules()
    missing_from_parent = sorted(
        module.name for module in expected_modules.values() if module.name not in declared_modules
    )
    if missing_from_parent:
        raise ExtensionPackError(
            f"production NodePackage modules are missing from the extensions reactor: {missing_from_parent}"
        )

    pack = project(PACK / "pom.xml")
    if value(pack, "m:artifactId") != PACK_ARTIFACT:
        raise ExtensionPackError("the extension pack has the wrong artifactId")
    if value(pack, "m:packaging") not in (None, "jar"):
        raise ExtensionPackError("ravenroot-extensions-all must be an ordinary JAR dependency")
    actual = dependency_artifacts(pack)
    if actual != expected:
        raise ExtensionPackError(
            "extension-pack membership differs from production NodePackage modules; "
            f"missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}"
        )

    check_no_ambient_activation(expected_modules)
    check_distribution_boundary(expected)
    return expected


def component_identity(component: dict[str, object]) -> tuple[str | None, str | None, str | None]:
    return (
        component.get("group") if isinstance(component.get("group"), str) else None,
        component.get("name") if isinstance(component.get("name"), str) else None,
        component.get("bom-ref") if isinstance(component.get("bom-ref"), str) else None,
    )


def check_sbom(path: Path, expected: set[str]) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    components = list(document.get("components", []))
    metadata_component = document.get("metadata", {}).get("component")
    if isinstance(metadata_component, dict):
        components.append(metadata_component)
    identities = [component_identity(component) for component in components if isinstance(component, dict)]
    refs = {
        ref: name
        for group, name, ref in identities
        if group == "ai.ravenroot" and name and ref
    }
    pack_refs = [ref for ref, name in refs.items() if name == PACK_ARTIFACT]
    if len(pack_refs) != 1:
        raise ExtensionPackError(
            f"aggregate CycloneDX SBOM must contain one {PACK_ARTIFACT} component"
        )
    dependency = next(
        (
            entry
            for entry in document.get("dependencies", [])
            if isinstance(entry, dict) and entry.get("ref") == pack_refs[0]
        ),
        None,
    )
    if dependency is None:
        raise ExtensionPackError("aggregate CycloneDX SBOM has no dependency relationship for the pack")
    related = {refs[ref] for ref in dependency.get("dependsOn", []) if ref in refs}
    if related != expected:
        raise ExtensionPackError(
            "aggregate CycloneDX pack relationships differ; "
            f"missing={sorted(expected - related)}, unexpected={sorted(related - expected)}"
        )


def check_dependency_tree(path: Path, expected: set[str]) -> None:
    artifacts = set(RAVENROOT_COORDINATE.findall(path.read_text(encoding="utf-8")))
    allowed = expected | {PACK_ARTIFACT, "ravenroot-application-api"}
    if artifacts != allowed:
        raise ExtensionPackError(
            "clean consumer Ravenroot dependency tree differs; "
            f"missing={sorted(allowed - artifacts)}, unexpected={sorted(artifacts - allowed)}"
        )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser()
    result.add_argument("--sbom", type=Path)
    result.add_argument("--dependency-tree", type=Path)
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        expected = check_pack()
        if arguments.sbom:
            check_sbom(arguments.sbom, expected)
        if arguments.dependency_tree:
            check_dependency_tree(arguments.dependency_tree, expected)
    except (ExtensionPackError, ET.ParseError, OSError, json.JSONDecodeError) as exc:
        print(f"Extension pack contract failed: {exc}", file=sys.stderr)
        return 1
    print(
        f"Validated {PACK_ARTIFACT} with {len(expected)} explicit first-party NodePackage dependencies."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
