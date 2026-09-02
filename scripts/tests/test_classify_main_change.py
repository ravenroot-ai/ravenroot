import unittest

from scripts.classify_main_change import (
    ClassificationError,
    classify,
    documentation_only,
    parse_labels,
)


class DocumentationOnlyTest(unittest.TestCase):
    def test_accepts_documentation_and_public_content(self):
        self.assertTrue(
            documentation_only(
                ["README.md", "docs/assets/editor.png", "adr/0001-example.md", ".github/SECURITY.md"]
            )
        )

    def test_accepts_only_documentation_change_fragments(self):
        self.assertTrue(documentation_only([".changes/123.docs.md"]))
        self.assertFalse(documentation_only([".changes/123.fix.md"]))

    def test_rejects_empty_and_product_or_automation_changes(self):
        self.assertFalse(documentation_only([]))
        self.assertFalse(documentation_only(["ravenroot/pom.xml"]))
        self.assertFalse(documentation_only([".github/workflows/ci.yml"]))


class ParseLabelsTest(unittest.TestCase):
    def test_parses_github_objects_strings_and_null(self):
        self.assertEqual(parse_labels('[{"name":"release:none"}]'), {"release:none"})
        self.assertEqual(parse_labels('["release:patch"]'), {"release:patch"})
        self.assertEqual(parse_labels("null"), set())

    def test_rejects_invalid_payloads(self):
        with self.assertRaises(ClassificationError):
            parse_labels("not-json")
        with self.assertRaises(ClassificationError):
            parse_labels('{}')


class ClassifyTest(unittest.TestCase):
    def test_pull_request_to_dev_is_fast(self):
        self.assertEqual(
            classify(event_name="pull_request", base_ref="dev", ref_name="feature/x", labels=set(), paths=[])[
                "tier"
            ],
            "fast",
        )

    def test_main_requires_exactly_one_release_label(self):
        for labels in (set(), {"release:none", "release:patch"}):
            with self.assertRaises(ClassificationError):
                classify(
                    event_name="pull_request",
                    base_ref="main",
                    ref_name="dev",
                    labels=labels,
                    paths=["README.md"],
                )

    def test_main_content_promotion_uses_docs_tier(self):
        self.assertEqual(
            classify(
                event_name="pull_request",
                base_ref="main",
                ref_name="dev",
                labels={"release:none"},
                paths=["README.md", "docs/index.md"],
            ),
            {"tier": "docs", "release_intent": "none", "docs_only": "true"},
        )

    def test_release_none_rejects_product_or_workflow_changes(self):
        for path in ("ravenroot/pom.xml", ".github/workflows/ci.yml"):
            with self.assertRaises(ClassificationError):
                classify(
                    event_name="pull_request",
                    base_ref="main",
                    ref_name="dev",
                    labels={"release:none"},
                    paths=[path],
                )

    def test_release_labels_require_a_product_change(self):
        with self.assertRaises(ClassificationError):
            classify(
                event_name="pull_request",
                base_ref="main",
                ref_name="dev",
                labels={"release:patch"},
                paths=["docs/index.md"],
            )

    def test_release_change_uses_full_tier(self):
        result = classify(
            event_name="pull_request",
            base_ref="main",
            ref_name="dev",
            labels={"release:minor"},
            paths=["ravenroot/pom.xml", "docs/index.md"],
        )
        self.assertEqual(result["tier"], "full")
        self.assertEqual(result["release_intent"], "minor")

    def test_push_to_main_infers_docs_tier_from_paths(self):
        result = classify(
            event_name="push",
            base_ref="",
            ref_name="main",
            labels=set(),
            paths=["docs/index.md"],
        )
        self.assertEqual(result["tier"], "docs")
        self.assertEqual(result["release_intent"], "none")

    def test_push_to_dev_and_manual_dispatch_are_full(self):
        for event_name, ref_name in (("push", "dev"), ("workflow_dispatch", "main")):
            result = classify(
                event_name=event_name,
                base_ref="",
                ref_name=ref_name,
                labels=set(),
                paths=["docs/index.md"],
            )
            self.assertEqual(result["tier"], "full")


if __name__ == "__main__":
    unittest.main()
