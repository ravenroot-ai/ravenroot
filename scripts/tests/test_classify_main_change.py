import unittest

from scripts.classify_main_change import (
    ROUTED_INPUTS,
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


REPOSITORY = "ravenroot-ai/ravenroot"
PROMOTION_HEAD = {"head_ref": "dev", "head_repository": REPOSITORY, "repository": REPOSITORY}


class ClassifyTest(unittest.TestCase):
    def test_pull_request_to_dev_is_full(self):
        """`ci.yml` no longer triggers this way, so it can only be the routed Dependabot replay —
        and it must earn the complete suite, not the retired `admission` diagnostic tier."""
        self.assertEqual(
            classify(event_name="pull_request", base_ref="dev", ref_name="feature/x", labels=set(), paths=[])[
                "tier"
            ],
            "full",
        )

    def test_main_requires_exactly_one_release_label(self):
        for labels in (set(), {"release:none", "release:patch"}):
            with self.assertRaises(ClassificationError):
                classify(
                    event_name="pull_request",
                    base_ref="main",
                    ref_name="dev",
                    **PROMOTION_HEAD,
                    labels=labels,
                    paths=["README.md"],
                )

    def test_main_content_promotion_keeps_its_intent_without_a_functional_tier(self):
        self.assertEqual(
            classify(
                event_name="pull_request",
                base_ref="main",
                ref_name="dev",
                **PROMOTION_HEAD,
                labels={"release:none"},
                paths=["README.md", "docs/index.md"],
            ),
            {"tier": "promotion", "release_intent": "none", "docs_only": "true"},
        )

    def test_release_none_rejects_product_or_workflow_changes(self):
        for path in ("ravenroot/pom.xml", ".github/workflows/ci.yml"):
            with self.assertRaises(ClassificationError):
                classify(
                    event_name="pull_request",
                    base_ref="main",
                    ref_name="dev",
                    **PROMOTION_HEAD,
                    labels={"release:none"},
                    paths=[path],
                )

    def test_release_labels_require_a_product_change(self):
        with self.assertRaises(ClassificationError):
            classify(
                event_name="pull_request",
                base_ref="main",
                ref_name="dev",
                **PROMOTION_HEAD,
                labels={"release:patch"},
                paths=["docs/index.md"],
            )

    def test_every_promotion_to_main_uses_the_promotion_tier(self):
        """The promotion re-verifies nothing; the release intent still has to survive it."""
        for label, intent in (("release:patch", "patch"), ("release:minor", "minor"), ("release:major", "major")):
            with self.subTest(label=label):
                result = classify(
                    event_name="pull_request",
                    base_ref="main",
                    ref_name="dev",
                    **PROMOTION_HEAD,
                    labels={label},
                    paths=["ravenroot/pom.xml", "docs/index.md"],
                )
                self.assertEqual(result["tier"], "promotion")
                self.assertEqual(result["release_intent"], intent)

    def test_only_this_repositorys_dev_is_a_promotion(self):
        """Antares's finding on #313: the promotion tier went to any head into main."""
        def into_main(head_ref, head_repository):
            return classify(event_name="pull_request", base_ref="main", ref_name="x", labels={"release:patch"},
                            paths=["ravenroot/pom.xml"], head_ref=head_ref, head_repository=head_repository,
                            repository=REPOSITORY)
        self.assertEqual(into_main("dev", REPOSITORY)["tier"], "promotion")
        self.assertEqual(into_main("hotfix/cve", REPOSITORY)["tier"], "full",
                         "a hotfix never passed through dev, so it is verified in full")
        for head_ref, head_repository in (("feature/x", REPOSITORY), ("dev", "fork/ravenroot"), ("", "")):
            with self.subTest(head=f"{head_repository}:{head_ref}"):
                with self.assertRaises(ClassificationError):
                    into_main(head_ref, head_repository)

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

    def test_a_routed_dependabot_run_is_classified_as_its_pull_request_into_dev(self):
        """The routing workflow replays the event as `pull_request` into `dev`. A later merge relies on
        this result, so it earns the complete suite, conservatively, rather than a lighter one."""
        self.assertEqual(
            classify(
                event_name="pull_request",
                base_ref="dev",
                ref_name="dependabot/npm_and_yarn/example",
                labels=set(),
                paths=["ravenroot/ravenroot-ui/package-lock.json"],
            )["tier"],
            "full",
        )

    def test_a_merge_group_commit_is_full(self):
        self.assertEqual(
            classify(event_name="merge_group", base_ref="", ref_name="gh-readonly-queue/dev/pr-1",
                     labels=set(), paths=["README.md"])["tier"],
            "full",
        )

    def test_a_dispatch_runs_the_full_tier_and_nothing_lighter(self):
        """Verifies a review candidate on its exact commit before the pull request opens; it cannot
        substitute for the pull request's own event, which now publishes ci-required independently."""
        for requested in ("", "full"):
            with self.subTest(requested=requested):
                self.assertEqual(
                    classify(event_name="workflow_dispatch", base_ref="", ref_name="feature/x",
                             labels=set(), paths=[], dispatch_tier=requested)["tier"],
                    "full",
                )
        for requested in ("docs", "promotion", "fast", "FULL"):
            with self.subTest(requested=requested):
                with self.assertRaises(ClassificationError):
                    classify(event_name="workflow_dispatch", base_ref="", ref_name="feature/x",
                             labels=set(), paths=[], dispatch_tier=requested)

    def test_a_dispatch_without_routing_may_not_redirect_the_checkout(self):
        """Every job checks out `merge_sha` when set; the result would land on a commit it never tested."""
        unrouted = {name: "" for name in ROUTED_INPUTS}
        self.assertEqual(
            classify(event_name="workflow_dispatch", base_ref="", ref_name="dev", labels=set(), paths=[],
                     routed_inputs=unrouted)["tier"],
            "full",
        )
        for name in ROUTED_INPUTS:
            with self.subTest(input=name):
                with self.assertRaises(ClassificationError):
                    classify(event_name="workflow_dispatch", base_ref="", ref_name="dev", labels=set(), paths=[],
                             routed_inputs={**unrouted, name: "9e75c71c061bdc7390dace58be761d21db4b4ad3"})

    def test_push_to_dev_is_postmerge(self):
        result = classify(event_name="push", base_ref="", ref_name="dev", labels=set(), paths=[])
        self.assertEqual(result["tier"], "postmerge")

    def test_manual_dispatch_is_full(self):
        result = classify(
            event_name="workflow_dispatch", base_ref="", ref_name="main", labels=set(), paths=[]
        )
        self.assertEqual(result["tier"], "full")

if __name__ == "__main__":
    unittest.main()
