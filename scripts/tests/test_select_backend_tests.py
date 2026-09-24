import unittest

from scripts.select_backend_tests import select


class BackendScopeSelectionTest(unittest.TestCase):
    def test_shared_and_maven_topology_changes_use_the_full_reactor(self):
        for path in (
            "ravenroot/pom.xml",
            "ravenroot/ravenroot-application-api/src/main/java/Api.java",
            "ravenroot/ravenroot-core/src/main/java/Core.java",
            "ravenroot/ravenroot-server/src/main/java/Server.java",
        ):
            with self.subTest(path=path):
                self.assertEqual(select("pull_request", [path]).mode, "all")

    def test_a_leaf_module_uses_itself_and_upstream_dependencies_only(self):
        scope = select("pull_request", ["ravenroot/ravenroot-node-starter/src/test/java/NodeTest.java"])
        self.assertEqual(scope.mode, "selected")
        self.assertEqual(scope.projects, ("ravenroot-node-starter",))

    def test_unknown_and_non_backend_paths_fail_closed_to_the_full_reactor(self):
        for path in (".github/workflows/ci.yml", "ravenroot/unknown-module/src/Main.java", "README.md"):
            with self.subTest(path=path):
                self.assertEqual(select("pull_request", [path]).mode, "all")

    def test_sqlite_embed_registration_paths_select_sqlite_and_server_without_object_storage(self):
        scope = select(
            "pull_request",
            ["ravenroot/ravenroot-persistence-sqlite/src/main/java/ai/ravenroot/persistence/sqlite/SqliteEmbedRegistrationStore.java"],
        )
        self.assertEqual(scope.mode, "selected")
        self.assertEqual(scope.projects, ("ravenroot-persistence-sqlite", "ravenroot-server"))
        self.assertNotIn("ravenroot-extensions/ravenroot-object-storage", scope.projects)

    def test_pr_dispatch_and_merge_group_are_range_scoped_but_schedule_is_deliberately_all(self):
        paths = ["ravenroot/ravenroot-persistence-postgresql/src/test/java/PostgresTest.java"]
        for event in ("pull_request", "workflow_dispatch", "merge_group"):
            with self.subTest(event=event):
                self.assertEqual(select(event, paths).mode, "selected")
        self.assertEqual(select("schedule", paths).mode, "all")


if __name__ == "__main__":
    unittest.main()
