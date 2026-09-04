import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from scripts.check_extension_pack import (
    ExtensionPackError,
    PACK_ARTIFACT,
    check_dependency_tree,
    check_pack,
    check_sbom,
    dependency_artifacts,
)


NS = "http://maven.apache.org/POM/4.0.0"


def dependency(artifact: str, *, version: str = "${project.version}") -> ET.Element:
    return ET.fromstring(
        f"""<dependency xmlns="{NS}">
          <groupId>ai.ravenroot</groupId>
          <artifactId>{artifact}</artifactId>
          <version>{version}</version>
        </dependency>"""
    )


class ExtensionPackContractTest(unittest.TestCase):
    def test_repository_pack_matches_every_production_node_package(self):
        expected = check_pack()
        self.assertEqual(len(expected), 16)
        self.assertIn("ravenroot-discord", expected)
        self.assertIn("ravenroot-github", expected)
        self.assertIn("ravenroot-object-storage", expected)
        self.assertNotIn("ravenroot-server", expected)

    def test_rejects_a_drifting_or_optional_dependency(self):
        project = ET.Element(f"{{{NS}}}project")
        dependencies = ET.SubElement(project, f"{{{NS}}}dependencies")
        dependencies.append(dependency("ravenroot-mail", version="[0.1,1.0)"))
        with self.assertRaisesRegex(ExtensionPackError, "exact project version"):
            dependency_artifacts(project)

        project = ET.Element(f"{{{NS}}}project")
        dependencies = ET.SubElement(project, f"{{{NS}}}dependencies")
        optional = dependency("ravenroot-mail")
        ET.SubElement(optional, f"{{{NS}}}optional").text = "true"
        dependencies.append(optional)
        with self.assertRaisesRegex(ExtensionPackError, "optional"):
            dependency_artifacts(project)

    def test_sbom_requires_the_exact_pack_relationships(self):
        expected = {"ravenroot-mail", "ravenroot-websocket"}
        refs = {name: f"pkg:maven/ai.ravenroot/{name}@1" for name in expected | {PACK_ARTIFACT}}
        document = {
            "components": [
                {"group": "ai.ravenroot", "name": name, "bom-ref": ref}
                for name, ref in refs.items()
            ],
            "dependencies": [
                {"ref": refs[PACK_ARTIFACT], "dependsOn": [refs[name] for name in sorted(expected)]}
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sbom.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            check_sbom(path, expected)
            document["dependencies"][0]["dependsOn"].pop()
            path.write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(ExtensionPackError, "relationships differ"):
                check_sbom(path, expected)

    def test_clean_consumer_tree_rejects_out_of_scope_ravenroot_artifacts(self):
        expected = {"ravenroot-mail"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tree.txt"
            path.write_text(
                "\n".join(
                    f"ai.ravenroot:{artifact}:jar:1:compile"
                    for artifact in (
                        PACK_ARTIFACT,
                        "ravenroot-application-api",
                        "ravenroot-mail",
                    )
                ),
                encoding="utf-8",
            )
            check_dependency_tree(path, expected)
            path.write_text(path.read_text() + "\nai.ravenroot:ravenroot-server:jar:1:compile\n")
            with self.assertRaisesRegex(ExtensionPackError, "unexpected=.*ravenroot-server"):
                check_dependency_tree(path, expected)


if __name__ == "__main__":
    unittest.main()
