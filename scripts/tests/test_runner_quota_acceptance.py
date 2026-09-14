"""Safety/positive-result contracts for the opt-in host-backed writable fixture."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stderr
from unittest.mock import Mock, patch

PATH = Path(__file__).resolve().parents[1] / "fixtures/runner_quota_acceptance.py"
SPEC = importlib.util.spec_from_file_location("runner_quota_acceptance", PATH)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)


class DaemonReadinessTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        (self.directory / "daemon.log").write_text("private daemon startup diagnostic\n")
        self.environment = {"DOCKER_HOST": "unix://" + str(self.directory / "docker.sock")}
        self.daemon = Mock()
        self.daemon.poll.return_value = None
        self.now = 0.0
        self.run = self.enterContext(patch.object(fixture.subprocess, "run"))
        self.enterContext(patch.object(fixture.time, "monotonic", side_effect=lambda: self.now))
        self.sleep = self.enterContext(patch.object(fixture.time, "sleep", side_effect=self.advance))
        self.stderr = io.StringIO()
        self.enterContext(redirect_stderr(self.stderr))
        self.green = {"Driver": "overlay2", "DockerRootDir": str(self.directory / "xfs/docker"),
                      "ServerVersion": "28.0.4"}

    def advance(self, seconds):
        self.now += seconds

    def response(self, value, code=0, stderr=""):
        return fixture.subprocess.CompletedProcess([], code, json.dumps(value), stderr)

    def wait(self):
        return fixture.wait_for_daemon(self.daemon, self.directory, self.environment)

    def diagnostic(self):
        lines = self.stderr.getvalue().splitlines()
        self.assertEqual(len(lines), 1)
        prefix = "RUNNER_QUOTA_STARTUP_FAILURE="
        self.assertTrue(lines[0].startswith(prefix))
        return json.loads(lines[0][len(prefix):])

    def test_docker_28_zero_exit_unavailable_server_is_retried_until_ready(self):
        # Docker CLI v28.0.4 runInfo returns formatInfo's success even after Info
        # fails: ServerErrors and empty server fields coexist with client info.
        unavailable = {"Driver": "", "DockerRootDir": "", "ServerVersion": "",
                       "ClientInfo": {"Version": "28.0.4"},
                       "ServerErrors": ["Cannot connect to the Docker daemon at unix:///fixture/docker.sock. Is the docker daemon running?"]}
        self.run.side_effect = [self.response(unavailable), self.response(self.green)]
        self.assertEqual(self.wait(), self.green)
        self.assertEqual(self.now, 1)
        self.assertEqual(self.run.call_count, 2)
        self.assertEqual(self.stderr.getvalue(), "")
        for call in self.run.call_args_list:
            self.assertEqual(call.args[0], ["docker", "info", "--format", "{{json .}}"])
            self.assertIs(call.kwargs["env"], self.environment)
            self.assertLessEqual(call.kwargs["timeout"], 5)
            self.assertTrue(call.kwargs["capture_output"])

    def test_transport_timeout_invalid_json_and_incomplete_server_are_not_readiness(self):
        self.run.side_effect = [
            self.response({}, code=1, stderr="socket unavailable"),
            fixture.subprocess.TimeoutExpired("docker", 5),
            fixture.subprocess.CompletedProcess([], 0, "{", ""),
            self.response([]), self.response({}),
            self.response(dict(self.green, ServerVersion=None)),
            self.response(dict(self.green, ServerErrors=["server failed"])),
            self.response(self.green),
        ]
        self.assertEqual(self.wait(), self.green)
        self.assertEqual(self.run.call_count, 8)

    def test_ready_wrong_driver_or_root_fails_immediately_without_retry(self):
        for changes in ({"Driver": "overlayfs"}, {"DockerRootDir": "/var/lib/docker"}):
            with self.subTest(changes=changes):
                self.stderr.seek(0)
                self.stderr.truncate()
                self.run.return_value = self.response(dict(self.green, **changes))
                with self.assertRaisesRegex(RuntimeError, "classic overlay2 quota filesystem"):
                    self.wait()
                self.assertEqual(self.diagnostic()["server"], dict(self.green, **changes))
                self.sleep.assert_not_called()

    def test_absent_socket_expires_at_monotonic_deadline_and_preserves_log_evidence(self):
        self.run.return_value = self.response({"ServerErrors": ["socket unavailable"]})
        with self.assertRaisesRegex(RuntimeError, "within 60 seconds"):
            self.wait()
        self.assertEqual(self.now, 60)
        self.assertEqual(self.run.call_count, 60)
        diagnostic = self.diagnostic()
        self.assertIn("socket unavailable", diagnostic["lastProbe"])
        self.assertIn("private daemon startup diagnostic", diagnostic["daemonLogTail"])

    def test_slow_probes_share_deadline_instead_of_multiplying_it(self):
        def slow(*args, **kwargs):
            self.advance(kwargs["timeout"])
            raise fixture.subprocess.TimeoutExpired("docker", kwargs["timeout"])
        self.run.side_effect = slow
        with self.assertRaisesRegex(RuntimeError, "within 60 seconds"):
            self.wait()
        self.assertEqual(self.now, 60)
        self.assertEqual(self.run.call_count, 10)
        self.assertIn("timed out", self.diagnostic()["lastProbe"])

    def test_final_probe_uses_only_remaining_budget_and_late_green_cannot_pass(self):
        def late(*args, **kwargs):
            if self.now == 0:
                self.advance(58)
                return self.response({})
            self.assertEqual(kwargs["timeout"], 1)
            self.advance(1)
            return self.response(self.green)
        self.run.side_effect = late
        with self.assertRaisesRegex(RuntimeError, "within 60 seconds"):
            self.wait()
        self.assertEqual(self.now, 60)

    def test_daemon_exit_before_or_during_probe_is_not_retried(self):
        self.daemon.poll.return_value = 17
        with self.assertRaisesRegex(RuntimeError, "exited before readiness"):
            self.wait()
        self.run.assert_not_called()
        self.assertEqual(self.diagnostic()["daemonExitCode"], 17)
        self.stderr.seek(0)
        self.stderr.truncate()
        self.daemon.poll.side_effect = [None, 18, 18]
        self.run.return_value = self.response(self.green)
        with self.assertRaisesRegex(RuntimeError, "exited during readiness probe"):
            self.wait()
        self.assertEqual(self.diagnostic()["daemonExitCode"], 18)

    def test_probe_cannot_execute_fails_with_diagnostic(self):
        self.run.side_effect = FileNotFoundError("docker")
        with self.assertRaisesRegex(RuntimeError, "probe could not execute"):
            self.wait()
        self.assertIn("FileNotFoundError", self.diagnostic()["lastProbe"])
        self.sleep.assert_not_called()

    def test_diagnostics_are_bounded_and_missing_log_does_not_hide_failure(self):
        log = self.directory / "daemon.log"
        log.write_text("x" * (fixture.DIAGNOSTIC_BYTES * 2) + "final evidence\n")
        self.run.return_value = self.response({}, code=1, stderr="y" * (fixture.DIAGNOSTIC_BYTES * 2))
        with self.assertRaisesRegex(RuntimeError, "within 60 seconds"):
            self.wait()
        evidence = self.diagnostic()
        self.assertLessEqual(len(evidence["lastProbe"]), fixture.DIAGNOSTIC_BYTES)
        self.assertEqual(len(evidence["daemonLogTail"]), fixture.DIAGNOSTIC_BYTES)
        self.assertTrue(evidence["daemonLogTail"].endswith("final evidence\n"))
        log.unlink()
        self.stderr.seek(0)
        self.stderr.truncate()
        self.daemon.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, "exited before readiness"):
            self.wait()
        self.assertEqual(self.diagnostic()["daemonLogTail"], "daemon log unavailable: FileNotFoundError")


class RunnerQuotaAcceptanceTest(unittest.TestCase):
    def test_startup_failure_is_diagnosed_before_cleanup_without_image_or_test_execution(self):
        with tempfile.TemporaryDirectory() as parent:
            parent = Path(parent).resolve()
            directory = parent / "ravenroot-runner-quota-fixture"
            directory.mkdir()
            daemon = Mock()
            daemon.poll.return_value = 23
            calls = []
            stderr = io.StringIO()

            def command(arguments, **kwargs):
                calls.append(arguments)
                if "umount" in arguments or "rm" in arguments:
                    self.assertIn("RUNNER_QUOTA_STARTUP_FAILURE=", stderr.getvalue())
                output = json.dumps({"filesystems": [{"fstype": "xfs", "options": "rw,prjquota"}]})
                return fixture.subprocess.CompletedProcess(arguments, 0, output)

            with patch.object(fixture, "prerequisites", return_value=Path(parent)), \
                    patch.object(fixture.tempfile, "mkdtemp", return_value=str(directory)), \
                    patch.object(fixture.subprocess, "run", side_effect=command), \
                    patch.object(fixture.subprocess, "Popen", return_value=daemon), \
                    redirect_stderr(stderr):
                with self.assertRaisesRegex(RuntimeError, "exited before readiness"):
                    fixture.run()
            self.assertIn(["sudo", "-n", "umount", str(directory / "xfs")], calls)
            self.assertEqual(calls[-1], ["sudo", "-n", "rm", "-rf", "--", str(directory)])
            self.assertFalse(any(command[0] in ("mvn", "docker") for command in calls))
            daemon.terminate.assert_not_called()

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
        # Surefire 3.5.6 / JUnit Jupiter XML shape observed for the @TempDir Path
        # method; omit only environment properties and captured application logs.
        green = '''<?xml version="1.0" encoding="UTF-8"?>
<testsuite xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
 xsi:noNamespaceSchemaLocation="https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd"
 version="3.0.2" name="ai.ravenroot.core.runtime.WorkspaceAgentRuntimeTest"
 time="12.46" tests="1" errors="0" skipped="0" failures="0" flakes="0">
 <properties/>
 <testcase name="writableContainerDevelopmentCycleUsesRealWorkspaceAcrossEveryRestart(Path)"
 classname="ai.ravenroot.core.runtime.WorkspaceAgentRuntimeTest" time="12.46">
  <system-out>Captured runner output</system-out><system-err/>
 </testcase>
</testsuite>'''
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.xml"
            report.write_text(green)
            fixture.verify_report(report)
            mutations = {
                "missing case": lambda root: root.remove(root.find("testcase")),
                "duplicate case": lambda root: root.append(ET.fromstring(ET.tostring(root.find("testcase")))),
                "unrelated additional case": lambda root: ET.SubElement(root, "testcase", name="other"),
                "wrong suite": lambda root: root.set("name", "other"),
                "wrong class": lambda root: root.find("testcase").set("classname", "other"),
                "bare selector": lambda root: root.find("testcase").set("name", fixture.TEST),
                "wrong parameter": lambda root: root.find("testcase").set("name", fixture.TEST + "(String)"),
                "prefix collision": lambda root: root.find("testcase").set("name", fixture.TEST + "Other(Path)"),
                "repeated invocation": lambda root: root.find("testcase").set("name", fixture.TEST + "(Path)[1]"),
                "missing counts": lambda root: root.attrib.pop("tests"),
                "wrong root": lambda root: setattr(root, "tag", "testsuites"),
            }
            for key in ("tests", "errors", "skipped", "failures", "flakes"):
                mutations["inconsistent " + key] = lambda root, key=key: root.set(key, "2")
            for tag in ("skipped", "error", "failure", "flakyFailure", "rerunFailure"):
                mutations[tag] = lambda root, tag=tag: ET.SubElement(root.find("testcase"), tag)
            for label, mutate in mutations.items():
                with self.subTest(label=label):
                    root = ET.fromstring(green)
                    mutate(root)
                    report.write_bytes(ET.tostring(root))
                    with self.assertRaisesRegex(RuntimeError, "did not execute"):
                        fixture.verify_report(report)
            report.write_text("<testsuite>")
            with self.assertRaises(ET.ParseError):
                fixture.verify_report(report)
            with self.assertRaises(FileNotFoundError):
                fixture.verify_report(Path(directory) / "missing.xml")


if __name__ == "__main__":
    unittest.main()
