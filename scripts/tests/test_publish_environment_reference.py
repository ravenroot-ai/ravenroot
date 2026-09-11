import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import publish_environment_reference as publisher

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

    def test_postgresql_bindings_have_exact_published_boundaries(self):
        expected = {
            "RAVENROOT_POSTGRES_EXECUTION_RESULT_RETENTION_SECONDS": (
                "PostgreSQL durable-result retention; default `604800` seconds"
            ),
            "RAVENROOT_POSTGRES_GRAPH_DEFINITION_UPSERT_ATTEMPTS": (
                "PostgreSQL definition insert/removal race attempts; default `3`"
            ),
            "RAVENROOT_POSTGRES_JOURNAL_RETENTION_SECONDS": (
                "PostgreSQL journal retention; default `86400` seconds"
            ),
            "RAVENROOT_POSTGRES_LOCK_TIMEOUT_MS": (
                "PostgreSQL lock wait in positive whole milliseconds; default `5000`"
            ),
            "RAVENROOT_POSTGRES_MAX_CLOCK_SKEW_SECONDS": (
                "PostgreSQL lease clock-skew budget; default `5` seconds, zero allowed"
            ),
            "RAVENROOT_POSTGRES_MAX_INVENTORY_PAGE_SIZE": (
                "PostgreSQL inventory page ceiling; default `100` rows"
            ),
            "RAVENROOT_POSTGRES_MAX_LEASE_TTL_SECONDS": (
                "PostgreSQL execution-store lease ceiling; default `300` seconds"
            ),
            "RAVENROOT_POSTGRES_MAX_PAYLOAD_BYTES": (
                "PostgreSQL generic payload capacity; default `1048576` bytes and pinned for new "
                "managed executions"
            ),
            "RAVENROOT_POSTGRES_SERIALIZATION_RETRIES": (
                "PostgreSQL serialization/deadlock retry count; default `3`, zero allowed"
            ),
            "RAVENROOT_POSTGRES_STATEMENT_TIMEOUT_MS": (
                "PostgreSQL statement bound in positive whole milliseconds; default `30000` and no "
                "shorter than lock timeout"
            ),
            "RAVENROOT_POSTGRES_TERMINAL_RETENTION_SECONDS": (
                "PostgreSQL terminal-process retention; default `604800` seconds"
            ),
        }
        names = variables()
        self.assertEqual(set(expected), {name for name in names if name.startswith("RAVENROOT_POSTGRES_")})
        published = render()
        for name, value in expected.items():
            with self.subTest(name=name):
                self.assertEqual("persistence", group(name))
                self.assertEqual(value, boundary(name))
                self.assertIn(f"| `{name}` | {value} |", published)
        for name in ("RAVENROOT_POSTGRES_", "RAVENROOT_POSTGRES_TYPO", "RAVENROOT_UNCLASSIFIED_SETTING"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, name):
                group(name)

    def test_namespace_guard_exclusion_requires_unchanged_reviewed_source(self):
        relative = Path("ravenroot-server/src/main/java/ai/ravenroot/server/persistence/PostgresStoreConfiguration.java")
        source = (publisher.SOURCE / relative).read_text(encoding="utf-8")
        self.assertEqual(1, source.count('"RAVENROOT_POSTGRES_"'))
        self.assertIn('entry.getKey().startsWith("RAVENROOT_POSTGRES_") && nonblank(entry.getValue())', source)
        self.assertNotIn("RAVENROOT_POSTGRES_", variables())
        inventory = json.loads((publisher.ROOT / "scripts/operational-configuration-inventory.json").read_text())
        sentinel = next(row for row in inventory["entries"] if row["id"] == "oc-e42de1c69df1980fb6d3")
        self.assertEqual("RAVENROOT_POSTGRES_", sentinel["expression"])
        self.assertEqual("retained", sentinel["status"])
        self.assertEqual("protocol-or-format-invariant", sentinel["classification"])
        mutations = (
            (relative, source, False),
            (relative, source + '\nclass AddedUse { String value = System.getenv("RAVENROOT_POSTGRES_"); }\n', True),
            (relative, source.replace('startsWith("RAVENROOT_POSTGRES_")', 'equals("RAVENROOT_POSTGRES_")'), True),
            (Path("other/src/main/java/Other.java"), source, True),
        )
        for relative_path, content, rejected in mutations:
            with self.subTest(path=str(relative_path), rejected=rejected, content=content[-80:]):
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    target = root / relative_path
                    target.parent.mkdir(parents=True)
                    target.write_text(content, encoding="utf-8")
                    with patch.object(publisher, "SOURCE", root):
                        if rejected:
                            self.assertIn("RAVENROOT_POSTGRES_", variables())
                            with self.assertRaisesRegex(ValueError, "RAVENROOT_POSTGRES_"):
                                render()
                        else:
                            self.assertNotIn("RAVENROOT_POSTGRES_", variables())
                            self.assertIn("RAVENROOT_POSTGRES_LOCK_TIMEOUT_MS", render())

    def test_new_unknown_postgresql_binding_is_extracted_and_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "other/src/main/java/Other.java"
            target.parent.mkdir(parents=True)
            target.write_text('class Other { String value = System.getenv("RAVENROOT_POSTGRES_TYPO"); }', encoding="utf-8")
            with patch.object(publisher, "SOURCE", root):
                self.assertEqual({"RAVENROOT_POSTGRES_TYPO"}, set(variables()))
                with self.assertRaisesRegex(ValueError, "RAVENROOT_POSTGRES_TYPO"):
                    render()

    def test_check_requires_exact_generated_bytes(self):
        self.assertEqual(render(), publisher.OUTPUT.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "environment-variables.md"
            for content, expected in ((render(), 0), (render() + "\nDrift\n", 1)):
                output.write_text(content, encoding="utf-8")
                with patch.object(publisher, "OUTPUT", output), patch("sys.argv", ["publisher", "--check"]):
                    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(expected, publisher.main())
                self.assertEqual(content, output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
