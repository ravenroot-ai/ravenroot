"""Safety/positive-result contracts for the opt-in host-backed writable fixture."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

PATH = Path(__file__).resolve().parents[1] / "fixtures/runner_quota_acceptance.py"
SPEC = importlib.util.spec_from_file_location("runner_quota_acceptance", PATH)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


class RunnerQuotaAcceptanceTest(unittest.TestCase):
    def test_local_and_self_hosted_environments_are_refused_before_commands(self):
        with patch.object(fixture.platform, "system", return_value="Linux"), patch.object(fixture.platform, "machine", return_value="x86_64"):
            for environment in ({}, {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted"}):
                with self.assertRaisesRegex(RuntimeError, "ephemeral GitHub-hosted"):
                    fixture.prerequisites(environment)

    def test_missing_tool_fails_without_installing_or_skipping(self):
        with patch.object(fixture.platform, "system", return_value="Linux"), patch.object(fixture.platform, "machine", return_value="x86_64"), patch.object(fixture.shutil, "which", return_value=None):
            with self.assertRaisesRegex(RuntimeError, "prerequisite missing"):
                fixture.prerequisites({"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted"})

    def test_daemon_isolated_from_default_storage_socket_network_and_snapshotter(self):
        args = fixture.daemon_arguments(Path("/tmp/ravenroot-runner-quota-fixture"))
        for argument in ("--storage-driver=overlay2", "--feature=containerd-snapshotter=false", "--bridge=none", "--iptables=false", "--ip6tables=false", "--ip-masq=false"):
            self.assertIn(argument, args)
        for prefix in ("--data-root=", "--exec-root=", "--pidfile=", "--host=", "--config-file="):
            value = next(value for value in args if value.startswith(prefix))
            self.assertIn("/tmp/ravenroot-runner-quota-fixture/", value)
        self.assertRegex(fixture.BASE_IMAGE, r"^python@sha256:[0-9a-f]{64}$")

    def test_only_one_executed_green_nine_job_case_is_acceptance(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            green = '<testcase name="' + fixture.TEST + '"/>'
            report.write_text('<testsuite>' + green + '</testsuite>')
            fixture.verify_report(report)
            for cases in ("", green + green, '<testcase name="other"/>', *('<testcase name="' + fixture.TEST + '"><' + tag + '/></testcase>' for tag in ("skipped", "error", "failure"))):
                report.write_text('<testsuite>' + cases + '</testsuite>')
                with self.assertRaisesRegex(RuntimeError, "did not execute"):
                    fixture.verify_report(report)


if __name__ == "__main__":
    unittest.main()
