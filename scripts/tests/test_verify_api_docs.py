"""Regression tests for generated public API documentation verification."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("verify_api_docs", ROOT / "scripts" / "verify_api_docs.py")
assert SPEC and SPEC.loader
VERIFY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFY)


class VerifyApiDocsTest(unittest.TestCase):
    def write_html(self, root: Path, name: str, content: str) -> Path:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def write_jar(self, path: Path, entries: dict[str, str]) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)

    def test_accepts_same_and_cross_page_fragments_and_public_jar(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_html(root, "index.html", '<a href="#local">local</a><a href="other.html#remote">remote</a><p id="local">x</p>')
            self.write_html(root, "other.html", '<h1 id="remote">remote</h1>')
            (root / "element-list").write_text("ai.ravenroot.api.payload\n", encoding="utf-8")
            jar = root / "sdk-javadoc.jar"
            self.write_jar(jar, {"element-list": "ai.ravenroot.api.payload\n", "ai/ravenroot/api/payload/PayloadValue.html": "ok"})
            VERIFY.verify_html_links(root)
            VERIFY.verify_packages(root)
            VERIFY.verify_javadoc_jar(jar)

    def test_rejects_broken_same_page_and_cross_page_fragments(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_html(root, "index.html", '<a href="#missing">missing</a>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>Performs the status operation at the public application boundary.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>Performs the write operation for the calling integration.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>Describes public contract exposed to an integration.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>the caller-supplied payload envelope used by this operation.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>Validates the state representation before returning it.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>the value produced when write completes.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>Applies the validation and normalization rules of the enclosing API value.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>Carries out the validate action at the application boundary.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>owner participating in this contract.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p>the admission result established by the request.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<a href="other.html#missing">missing</a>')
            self.write_html(root, "other.html", '<p id="present">present</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)

    def test_rejects_internal_namespace_in_javadoc_jar(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "sdk-javadoc.jar"
            self.write_jar(
                jar,
                {
                    "element-list": "ai.ravenroot.api.payload\nai.ravenroot.api.internal\n",
                    "ai/ravenroot/api/internal/Hidden.html": "hidden",
                },
            )
            with self.assertRaises(SystemExit):
                VERIFY.verify_javadoc_jar(jar)

    def test_rejects_generated_placeholder_forms_and_aggregate_internal_package(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.write_html(root, "index.html", '<p>Describes this public Ravenroot API declaration.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(
                root,
                "index.html",
                '<dd><code><span id="param-requestId">requestId</span></code> - '
                'the requestId value.</dd>',
            )
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)
            self.write_html(root, "index.html", '<p id="ok">ok</p>')
            (root / "element-list").write_text("ai.ravenroot.api.impl\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                VERIFY.verify_packages(root)

    def test_rejects_boolean_wording_for_void_completion_stages_in_source_and_html(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "ExecutionStore.java"
            source.write_text(
                """import java.util.concurrent.CompletionStage;
                interface ExecutionStore {
                    /** @return whether the supplied current lease was released. */
                    CompletionStage<Void> release(Object lease);
                }
                """,
                encoding="utf-8",
            )
            with self.assertRaises(SystemExit):
                VERIFY.verify_void_stage_return_contracts(source)
            self.write_html(root, "index.html", '<p>whether the claimed item was acknowledged with its current fence.</p>')
            with self.assertRaises(SystemExit):
                VERIFY.verify_html_links(root)


if __name__ == "__main__":
    unittest.main()
