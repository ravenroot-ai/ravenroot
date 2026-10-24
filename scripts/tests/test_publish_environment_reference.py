import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import re
from unittest.mock import patch

from scripts import publish_environment_reference as publisher

from scripts.publish_environment_reference import (
    ACTIVITY_CAPTURE_VARIABLES,
    GRAPH_AUTHORING_VARIABLES,
    INTERACTION_WEBSOCKET_VARIABLES,
    SYSTEM_ONE_PROFILE_VARIABLES,
    boundary,
    group,
    render,
    undocumented_variables,
    variables,
)


class PublishEnvironmentReferenceTest(unittest.TestCase):
    def test_history_bindings_are_concrete_exact_and_have_independent_semantics(self):
        expected = {
            "RAVENROOT_HISTORY_MAX_HISTORIES_PER_SCOPE", "RAVENROOT_HISTORY_MAX_OPEN_JOINS",
            "RAVENROOT_HISTORY_MAX_RETAINED_JOINS", "RAVENROOT_HISTORY_MAX_CONTRIBUTIONS",
            "RAVENROOT_HISTORY_MAX_JOIN_PAYLOAD_BYTES", "RAVENROOT_HISTORY_MAX_PENDING_ENTRIES",
            "RAVENROOT_HISTORY_MAX_PENDING_ENTRY_BYTES", "RAVENROOT_HISTORY_MAX_PAGE_SIZE",
            "RAVENROOT_HISTORY_RETENTION_SECONDS", "RAVENROOT_HISTORY_JOIN_DEADLINE_SECONDS",
            "RAVENROOT_HISTORY_MAX_ID_CHARACTERS", "RAVENROOT_HISTORY_MAX_SELECTOR_CHARACTERS",
            "RAVENROOT_HISTORY_MAX_SELECTOR_SEGMENTS", "RAVENROOT_HISTORY_MAX_MANIFEST_ITEMS",
            "RAVENROOT_HISTORY_MAX_CAS_ATTEMPTS", "RAVENROOT_HISTORY_READ_TIMEOUT_MS",
            "RAVENROOT_HISTORY_RESPONSE_MAX_BYTES", "RAVENROOT_HISTORY_RESPONSE_MAX_VALUES",
        }
        actual = {name: paths for name, paths in variables().items() if name.startswith("RAVENROOT_HISTORY_")}
        self.assertEqual(expected, set(actual))
        self.assertEqual(expected, publisher.HISTORY_VARIABLES)
        section = render().split("## Business history and correlated joins", 1)[1].split("## ", 1)[0]
        self.assertIn("configuration.md#business-history-and-correlated-join-limits", section)
        for name, paths in actual.items():
            with self.subTest(name=name):
                self.assertEqual((publisher.SOURCE / publisher.HISTORY_BINDING_SOURCE[0],), paths)
                self.assertEqual("history", group(name))
                self.assertIn(f"| `{name}` |", section)
                self.assertIn("startup positive integer", boundary(name))
                self.assertIn("takes precedence, blank delegates to environment", boundary(name))
                self.assertIn("default `", boundary(name))
                self.assertIn("ravenroot.history.", boundary(name))
        self.assertIn("maximum `1000`", boundary("RAVENROOT_HISTORY_MAX_PAGE_SIZE"))
        self.assertIn("oversize responses are refused", boundary("RAVENROOT_HISTORY_RESPONSE_MAX_BYTES"))
        self.assertNotIn("| `RAVENROOT_HISTORY_` |", render())
        contract = (publisher.ROOT / "docs/reference/configuration.md").read_text(encoding="utf-8")
        documented = dict(re.findall(r'\| `ravenroot.history.[^`]+` \| `(RAVENROOT_HISTORY_[A-Z_]+)` \| `(\d+)`', contract))
        self.assertEqual(expected, set(documented))
        for name, default in documented.items():
            self.assertIn(f"default `{default}`", boundary(name))

    def test_history_extraction_requires_the_exact_reviewed_loader(self):
        relative, _ = publisher.HISTORY_BINDING_SOURCE
        original = (publisher.SOURCE / relative).read_text(encoding="utf-8")
        mutations = (
            original.replace('"max-open-joins"', '"unknown-history-name"'),
            original.replace("name.toUpperCase(Locale.ROOT).replace('-', '_')", "name"),
            original + '\nclass AddedUse { String value = System.getenv("RAVENROOT_HISTORY_"); }\n',
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / relative
            target.parent.mkdir(parents=True)
            with patch.object(publisher, "SOURCE", root):
                target.write_text(original, encoding="utf-8")
                self.assertEqual(publisher.HISTORY_VARIABLES, set(variables()))
                for content in mutations:
                    with self.subTest(mutation=content[-100:]):
                        target.write_text(content, encoding="utf-8")
                        with self.assertRaisesRegex(ValueError, "history environment binding source changed"):
                            render()
                unknown = mutations[0]
                target.write_text(unknown, encoding="utf-8")
                # Reviewing a new fingerprint alone cannot bless a new setting.
                with patch.object(publisher, "HISTORY_BINDING_SOURCE", (relative, hashlib.sha256(unknown.encode()).hexdigest())):
                    with self.assertRaisesRegex(ValueError, "suffixes differ"):
                        render()

    def test_unknown_history_bindings_and_unreviewed_prefix_uses_are_refused(self):
        for name in ("RAVENROOT_HISTORY_", "RAVENROOT_HISTORY_UNKNOWN_HISTORY_NAME"):
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, name):
                    group(name)
                with tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    target = root / "other/src/main/java/Other.java"
                    target.parent.mkdir(parents=True)
                    target.write_text(f'class Other {{ String value = System.getenv("{name}"); }}', encoding="utf-8")
                    with patch.object(publisher, "SOURCE", root):
                        self.assertEqual({name}, set(variables()))
                        with self.assertRaisesRegex(ValueError, name):
                            render()

    def test_graph_authoring_bindings_are_exact_and_link_their_operator_contract(self):
        names = variables()
        actual = {
            name for name in names
            if name.startswith("RAVENROOT_GRAPH_AUTHORING_")
            or name.startswith("RAVENROOT_GRAPH_ARTIFACT_")
        }
        self.assertEqual(GRAPH_AUTHORING_VARIABLES, actual)
        for name in GRAPH_AUTHORING_VARIABLES:
            with self.subTest(name=name):
                self.assertEqual("graph-authoring", group(name))
                self.assertNotEqual(
                    "See the linked contract for exact type, default, and applicability.",
                    boundary(name),
                )

        section = render().split(
            "## Graph source control and published artifacts", 1
        )[1].split("## ", 1)[0]
        self.assertIn(
            "../operator-guide/graph-source-control.md#configure-the-source-archive",
            section,
        )
        self.assertIn("defaults to `LOCAL`", section)
        self.assertIn("defaults to `10485760`", section)
        self.assertIn("maximum `120000` milliseconds", section)
        self.assertIn("eligible GitHub `GET` and GitLab `GET`/`HEAD` read failures", section)
        self.assertIn("mutations are never retried blindly", section)

        webhook_names = {
            "RAVENROOT_GRAPH_AUTHORING_WEBHOOK_AUTH",
            "RAVENROOT_GRAPH_AUTHORING_WEBHOOK_CREDENTIAL_REF",
        }
        self.assertEqual(webhook_names, {
            name for name in GRAPH_AUTHORING_VARIABLES
            if name.startswith("RAVENROOT_GRAPH_AUTHORING_WEBHOOK_")
        })
        for name in webhook_names:
            with self.subTest(webhook=name):
                self.assertIn(f"`{name}`", section)
                self.assertIn(
                    "../operator-guide/graph-source-control.md#provider-webhook-delivery",
                    boundary(name),
                )
        credential = boundary("RAVENROOT_GRAPH_AUTHORING_WEBHOOK_CREDENTIAL_REF")
        self.assertIn("separate repository-specific webhook secret", credential)
        self.assertIn("unset disables the receiver", credential)
        self.assertIn("resolved fresh", credential)
        self.assertIn("including duplicates", credential)
        authentication = boundary("RAVENROOT_GRAPH_AUTHORING_WEBHOOK_AUTH")
        self.assertIn("defaults to `SIGNATURE`", authentication)
        self.assertIn("300-second timestamp window", authentication)
        self.assertIn("`TOKEN` selects GitLab legacy token authentication only", authentication)
        self.assertIn("no automatic fallback", authentication)
        self.assertIn("bearer and cookie credentials confer no authority", authentication)

    def test_independent_ui_mapping_tracks_runtime_and_shared_java_setting(self):
        names = publisher.ui_settings()
        self.assertEqual({"RAVENROOT_UI_BACKEND_URL", "RAVENROOT_UI_BACKEND_TOKEN", "RAVENROOT_UI_FRAME_ANCESTORS", "RAVENROOT_UI_PREFIX",
                          "RAVENROOT_UI_PORT", "RAVENROOT_UI_ROOT"}, set(names))
        self.assertEqual(
            {"RAVENROOT_UI_FRAME_ANCESTORS"},
            set(names).intersection(variables()),
        )
        section = render().split("## Independent UI web server", 1)[1]
        for name in names:
            self.assertIn(f"`{name}`", section)
        self.assertIn("not the Java backend", section)
        self.assertIn("Node.js itself", section)
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "server.mjs"
            source.write_text(publisher.UI_SERVER_SOURCE.read_text() + "\nenv.RAVENROOT_UI_TYPO;\n")
            with patch.object(publisher, "UI_SERVER_SOURCE", source):
                with self.assertRaisesRegex(ValueError, "RAVENROOT_UI_TYPO"):
                    render()

    def test_activity_capture_bindings_are_exact_and_link_their_dedicated_contract(self):
        names = variables()
        activity_names = {
            name for name in names if name.startswith("RAVENROOT_ACTIVITY_CAPTURE_")
        }
        self.assertEqual(ACTIVITY_CAPTURE_VARIABLES, activity_names)
        for name in ACTIVITY_CAPTURE_VARIABLES:
            with self.subTest(name=name):
                self.assertEqual("activity", group(name))
                self.assertNotEqual(
                    "See the linked contract for exact type, default, and applicability.",
                    boundary(name),
                )

        published = render()
        section = published.split("## Activity content archive", 1)[1].split("## ", 1)[0]
        self.assertIn("../operator-guide/activity-capture.md#service-configuration", section)
        self.assertIn("default `INPUT_PAYLOAD,OUTPUT_PAYLOAD`", section)
        self.assertIn("unset defaults to `false`", section)
        for name in ACTIVITY_CAPTURE_VARIABLES:
            self.assertIn(f"`{name}`", section)

        with self.assertRaisesRegex(ValueError, "RAVENROOT_ACTIVITY_CAPTURE_TYPO"):
            group("RAVENROOT_ACTIVITY_CAPTURE_TYPO")

    def test_runner_coordinator_bindings_are_exact_and_have_independent_semantics(self):
        expected = {"RAVENROOT_RUNNER_COORDINATOR_HTTP_THREADS", "RAVENROOT_RUNNER_COORDINATOR_HTTP_QUEUE",
                    "RAVENROOT_RUNNER_SHARED_ARTIFACTS", "RAVENROOT_RUNNER_INSTANCE", "RAVENROOT_LOCAL_RUNNER_CONFIG",
                    "RAVENROOT_RUNNER_READINESS_FILE"}
        self.assertEqual(expected, {name for name in variables() if group(name) == "runner"})
        self.assertIn("`16`", boundary("RAVENROOT_RUNNER_COORDINATOR_HTTP_THREADS"))
        self.assertIn("`64`", boundary("RAVENROOT_RUNNER_COORDINATOR_HTTP_QUEUE"))
        self.assertIn("POSIX", boundary("RAVENROOT_RUNNER_SHARED_ARTIFACTS"))
        self.assertIn("{instance}", boundary("RAVENROOT_RUNNER_INSTANCE"))
        self.assertIn("no endpoint/token fields", boundary("RAVENROOT_LOCAL_RUNNER_CONFIG"))
        self.assertIn("127.0.0.1", boundary("RAVENROOT_LOCAL_RUNNER_CONFIG"))
        self.assertIn("positive preflight", boundary("RAVENROOT_RUNNER_READINESS_FILE"))
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

    def test_trusted_network_policy_is_scoped_egress_configuration(self):
        name = "RAVENROOT_EGRESS_TRUSTED_NETWORK_POLICY"
        self.assertIn(name, variables())
        self.assertEqual("credential", group(name))

        description = boundary(name)
        self.assertIn("administrator-owned canonical padded Base64", description)
        self.assertIn("exact finite protocol, port, profile and destination scopes", description)
        self.assertIn("legacy exceptions remain admission-only", description)
        self.assertIn(
            "../operator-guide/credentials-egress.md#trusted-networks-and-plaintext-transports",
            description,
        )

        published = render()
        section = published.split("## Credentials and egress", 1)[1].split("## ", 1)[0]
        self.assertIn(f"`{name}`", section)
        self.assertIn("schema and precedence contract", section)

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

    def test_system_one_profile_is_an_exact_dynamic_bundle_binding(self):
        names = variables()
        actual = {
            name for name in names if name.startswith("RAVENROOT_SYSTEM_ONE_")
        }
        self.assertEqual(SYSTEM_ONE_PROFILE_VARIABLES, actual)
        name = "RAVENROOT_SYSTEM_ONE_PROFILE_"
        self.assertEqual("bundle", group(name))
        self.assertEqual(
            {"EnvironmentSystemOneProfileResolver.java"},
            {path.name for path in names[name]},
        )

        description = boundary(name)
        self.assertIn("upper-case hexadecimal UTF-8", description)
        self.assertIn("canonical padded Base64 of strict JSON", description)
        self.assertIn(
            "endpoint, model, credential binding, deadlines, limits, and capabilities",
            description,
        )
        self.assertIn("`PROFILE_UNKNOWN` before network I/O", description)

        section = render().split("## Bundle profile", 1)[1].split("## ", 1)[0]
        self.assertIn("Detailed contract: [Bundle profile](bundles/)", section)
        self.assertIn(f"| `{name}` | {description} |", section)
        with self.assertRaisesRegex(ValueError, "RAVENROOT_SYSTEM_ONE_PROFILE_TYPO"):
            group("RAVENROOT_SYSTEM_ONE_PROFILE_TYPO")

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
