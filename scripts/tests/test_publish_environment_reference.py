import unittest

from scripts.publish_environment_reference import (
    INTERACTION_WEBSOCKET_VARIABLES,
    boundary,
    group,
    render,
    undocumented_variables,
    variables,
)


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

    def test_interaction_listener_and_outbound_profile_have_distinct_references(self):
        names = variables()
        listener_names = {
            name
            for name, paths in names.items()
            if any(path.name == "InteractionWebSocketConfiguration.java" for path in paths)
        }
        self.assertEqual(INTERACTION_WEBSOCKET_VARIABLES, listener_names)
        for name in INTERACTION_WEBSOCKET_VARIABLES:
            self.assertEqual("interaction-websocket", group(name))
        self.assertEqual("bundle", group("RAVENROOT_WEBSOCKET_PROFILE_"))

        published = render()
        listener_section = published.split("## Interaction WebSocket", 1)[1].split("## ", 1)[0]
        bundle_section = published.split("## Bundle profile", 1)[1].split("## ", 1)[0]
        self.assertIn("interactions-websocket.md#configuration", listener_section)
        self.assertIn("`RAVENROOT_WEBSOCKET_ENABLED`", listener_section)
        self.assertNotIn("`RAVENROOT_WEBSOCKET_PROFILE_`", listener_section)
        self.assertIn("`RAVENROOT_WEBSOCKET_PROFILE_`", bundle_section)
        self.assertNotIn("`RAVENROOT_WEBSOCKET_ENABLED`", bundle_section)

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
