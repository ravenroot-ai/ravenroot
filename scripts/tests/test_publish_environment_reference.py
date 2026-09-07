import unittest

from scripts.publish_environment_reference import boundary, group, render, undocumented_variables, variables


class PublishEnvironmentReferenceTest(unittest.TestCase):
    def test_inventory_covers_high_risk_and_dynamic_groups(self):
        names = variables()
        self.assertIn("RAVENROOT_ASSISTANT_PROVIDER", names)
        self.assertIn("RAVENROOT_RATELIMIT_GLOBAL_ACTIVE_EXECUTIONS", names)
        self.assertIn("RAVENROOT_JDBC_PROFILE_", names)
        self.assertIn("RAVENROOT_HUMAN_TASK_", names)
        for name in (
            "RAVENROOT_MATRIX_CONFIG",
            "RAVENROOT_MATTERMOST_CONFIG",
            "RAVENROOT_TEAMS_CONFIG",
        ):
            self.assertIn(name, names)
            self.assertEqual("bundle", group(name))
        self.assertEqual("assistant", group("RAVENROOT_ASSISTANT_PROVIDER"))
        self.assertEqual("bundle", group("RAVENROOT_JDBC_PROFILE_"))
        self.assertEqual("human-task", group("RAVENROOT_HUMAN_TASK_"))

    def test_execution_runtime_group_is_exact_and_links_its_dedicated_contract(self):
        names = (
            "RAVENROOT_ENGINE_MAX_STASHED_COMMANDS_PER_NODE",
            "RAVENROOT_ENGINE_LIFECYCLE_STEP_SECONDS",
            "RAVENROOT_ENGINE_TERMINAL_HISTORY_CAPACITY",
            "RAVENROOT_GRAPH_RUNNER_SHUTDOWN_STEP_SECONDS",
        )
        inventory = variables()
        for name in names:
            self.assertIn(name, inventory)
            self.assertEqual("execution-runtime", group(name))
        self.assertEqual("graph", group("RAVENROOT_GRAPHML_MAX_DEPTH"))

        published = render()
        self.assertIn("## Execution runtime", published)
        self.assertIn(
            "configuration.md#execution-runtime-and-engine-limits", published
        )

    def test_execution_store_policy_bindings_belong_to_persistence(self):
        names = (
            "RAVENROOT_EXECUTION_STORE_MAX_LEASE_TTL_SECONDS",
            "RAVENROOT_EXECUTION_STORE_MAX_PAYLOAD_BYTES",
            "RAVENROOT_EXECUTION_STORE_MAX_CLOCK_SKEW_MILLIS",
            "RAVENROOT_EXECUTION_STORE_JOURNAL_RETENTION_SECONDS",
            "RAVENROOT_EXECUTION_STORE_MAX_INVENTORY_PAGE_SIZE",
            "RAVENROOT_EXECUTION_STORE_TERMINAL_RETENTION_SECONDS",
            "RAVENROOT_EXECUTION_STORE_RESULT_RETENTION_SECONDS",
            "RAVENROOT_SQLITE_BUSY_TIMEOUT_MILLIS",
        )
        inventory = variables()
        for name in names:
            self.assertIn(name, inventory)
            self.assertEqual("persistence", group(name))

    def test_render_names_every_production_literal(self):
        published = render()
        for name in variables():
            self.assertIn(f"`{name}`", published)

    def test_every_name_has_a_semantic_reference(self):
        self.assertEqual([], undocumented_variables())

    def test_variable_specific_boundaries_do_not_inherit_false_group_defaults(self):
        self.assertIn("unset grants no managed services", boundary("RAVENROOT_NODE_PACKAGE_SERVICES_"))
        self.assertIn("whose behaviors require none can still load", boundary("RAVENROOT_NODE_PACKAGE_SERVICES_"))
        self.assertIn("/opt/ravenroot/plugins", boundary("RAVENROOT_PLUGINS_INSTALL_DIR"))
        self.assertIn("defaults to `1`", boundary("RAVENROOT_REPLICAS"))
        self.assertIn("comma-separated exact IP literals", boundary("RAVENROOT_TRUSTED_PROXY_ADDRESSES"))

    def test_chat_bundle_configuration_boundaries_match_activation_lifecycle(self):
        matrix = boundary("RAVENROOT_MATRIX_CONFIG")
        self.assertIn("canonical padded Base64 of strict JSON", matrix)
        self.assertIn("bounded `store` settings", matrix)
        self.assertIn("first Matrix send execution or Matrix sync source instantiation", matrix)
        self.assertIn("successfully resolved value is cached", matrix)

        for name, package in (
            ("RAVENROOT_MATTERMOST_CONFIG", "Mattermost"),
            ("RAVENROOT_TEAMS_CONFIG", "Teams"),
        ):
            value = boundary(name)
            self.assertIn("canonical padded Base64 of strict JSON", value)
            self.assertIn("ingress `authority`", value)
            self.assertIn("request `projection`", value)
            self.assertIn("nonempty tenant/profile map", value)
            self.assertIn(f"ignored when the {package} package is not selected", value)
            self.assertIn("unset or malformed configuration refuses server startup", value)


if __name__ == "__main__":
    unittest.main()
