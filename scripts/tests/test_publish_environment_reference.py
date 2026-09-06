import unittest

from scripts.publish_environment_reference import group, render, undocumented_variables, variables


class PublishEnvironmentReferenceTest(unittest.TestCase):
    def test_inventory_covers_high_risk_and_dynamic_groups(self):
        names = variables()
        self.assertIn("RAVENROOT_ASSISTANT_PROVIDER", names)
        self.assertIn("RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS", names)
        self.assertIn("RAVENROOT_JDBC_PROFILE_", names)
        self.assertIn("RAVENROOT_HUMAN_TASK_", names)
        self.assertEqual("assistant", group("RAVENROOT_ASSISTANT_PROVIDER"))
        self.assertEqual("bundle", group("RAVENROOT_JDBC_PROFILE_"))
        self.assertEqual("human-task", group("RAVENROOT_HUMAN_TASK_"))

    def test_render_names_every_production_literal(self):
        published = render()
        for name in variables():
            self.assertIn(f"`{name}`", published)

    def test_every_name_has_a_semantic_reference(self):
        self.assertEqual([], undocumented_variables())


if __name__ == "__main__":
    unittest.main()
