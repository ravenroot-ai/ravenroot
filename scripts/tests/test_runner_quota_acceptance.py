"""Safety/positive-result contracts for the opt-in host-backed writable fixture."""
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stderr, redirect_stdout
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


class FixtureCleanupTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.parent = Path(temporary.name).resolve()
        self.directory = self.parent / "ravenroot-runner-quota-fixture"
        self.directory.mkdir()
        self.daemon = Mock()
        self.daemon.poll.return_value = 0
        self.command = Mock()
        self.read_mounts = fixture.mounted_paths
        self.mounts = self.enterContext(patch.object(fixture, "mounted_paths", return_value=set()))

    def cleanup(self):
        fixture.cleanup(self.parent, self.directory, self.daemon, self.command)

    def test_mountinfo_decodes_escaped_paths(self):
        content = "1 2 0:1 / /var/tmp/a\\040b/exec/netns/default rw - nsfs nsfs rw\n"
        with patch.object(Path, "read_text", return_value=content):
            self.assertEqual(self.read_mounts(), {Path("/var/tmp/a b/exec/netns/default")})

    def test_unmounts_only_owned_children_deepest_first_then_backing_filesystem(self):
        root = self.directory
        self.mounts.side_effect = [{Path("/"), Path("/var/lib/docker"),
                                   root / "xfs", root / "xfs/docker/overlay2/layer/merged",
                                   root / "exec/netns/default"}, {Path("/"), Path("/var/lib/docker")}]
        self.cleanup()
        commands = [call.args[0] for call in self.command.call_args_list]
        self.assertEqual(commands, [
            ["sudo", "-n", "umount", "--", str(root / "xfs/docker/overlay2/layer/merged")],
            ["sudo", "-n", "umount", "--", str(root / "exec/netns/default")],
            ["sudo", "-n", "umount", "--", str(root / "xfs")],
            ["sudo", "-n", "rm", "-rf", "--one-file-system", "--", str(root)]])
        self.daemon.wait.assert_called_once_with(timeout=30)

    def test_unmount_failure_or_remaining_mount_prevents_deletion(self):
        self.mounts.return_value = {self.directory / "exec/netns/default"}
        self.command.side_effect = fixture.subprocess.CalledProcessError(1, "umount")
        with self.assertRaises(fixture.subprocess.CalledProcessError):
            self.cleanup()
        self.assertEqual(self.command.call_count, 1)
        self.command.reset_mock(side_effect=True)
        with self.assertRaisesRegex(RuntimeError, "mounts remain"):
            self.cleanup()
        self.assertEqual(self.command.call_count, 1)
        self.assertEqual(self.command.call_args.args[0][2], "umount")

    def test_unexpected_mount_or_wrong_directory_prevents_all_effects(self):
        self.mounts.return_value = {self.directory / "unexpected"}
        with self.assertRaisesRegex(RuntimeError, "unexpected mount"):
            self.cleanup()
        with self.assertRaisesRegex(RuntimeError, "exact fixture directory"):
            fixture.cleanup(self.parent, self.parent, self.daemon, self.command)
        self.command.assert_not_called()

    def test_live_daemon_without_identity_retains_fixture_without_unmount(self):
        self.daemon.poll.return_value = None
        with self.assertRaisesRegex(RuntimeError, "identity is unavailable"):
            self.cleanup()
        self.command.assert_not_called()
        self.mounts.assert_not_called()

    def test_foreign_pid_cannot_be_signalled_or_trigger_deletion(self):
        (self.directory / "daemon.pid").write_text("123456")
        with patch.object(Path, "read_bytes", return_value=b"dockerd\0--data-root=/var/lib/docker\0"):
            with self.assertRaisesRegex(RuntimeError, "outside this fixture"):
                self.cleanup()
        self.command.assert_not_called()
        self.mounts.assert_not_called()

    def test_verified_daemon_stops_before_mounts_and_unconfirmed_stop_retains_state(self):
        (self.directory / "daemon.pid").write_text("123456")
        arguments = fixture.daemon_arguments(self.directory)[2:]
        real_exists = Path.exists
        for alive in (True, False):
            with self.subTest(alive=alive):
                self.command.reset_mock()
                self.daemon.wait.reset_mock()
                self.mounts.reset_mock()
                def exists(path):
                    return alive if str(path) == "/proc/123456/cmdline" else real_exists(path)
                def mounts():
                    self.assertEqual(self.command.call_args_list[0].args[0], ["sudo", "-n", "kill", "-TERM", "123456"])
                    self.daemon.wait.assert_called_with(timeout=30)
                    return set()
                self.mounts.side_effect = mounts
                with patch.object(Path, "read_bytes", return_value=b"\0".join(value.encode() for value in arguments)), \
                        patch.object(Path, "exists", exists):
                    if alive:
                        with self.assertRaisesRegex(RuntimeError, "shutdown is unconfirmed"):
                            self.cleanup()
                        self.mounts.assert_not_called()
                        self.assertEqual(self.command.call_count, 1)
                    else:
                        self.cleanup()
                        self.assertEqual(self.command.call_count, 2)


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
                    patch.object(fixture, "mounted_paths", side_effect=[{directory / "xfs", directory / "exec/netns/default"}, set()]), \
                    redirect_stderr(stderr):
                with self.assertRaisesRegex(RuntimeError, "exited before readiness"):
                    fixture.run()
            self.assertEqual(calls[-3:], [
                ["sudo", "-n", "umount", "--", str(directory / "exec/netns/default")],
                ["sudo", "-n", "umount", "--", str(directory / "xfs")],
                ["sudo", "-n", "rm", "-rf", "--one-file-system", "--", str(directory)]])
            self.assertFalse(any(command[0] in ("mvn", "docker") for command in calls))
            daemon.terminate.assert_not_called()

    def test_cleanup_failure_preserves_primary_native_or_startup_exception(self):
        with tempfile.TemporaryDirectory() as parent:
            parent = Path(parent).resolve()
            directory = parent / "ravenroot-runner-quota-fixture"
            directory.mkdir()
            original = RuntimeError("original native acceptance failure")
            stderr = io.StringIO()
            with patch.object(fixture, "prerequisites", return_value=parent), \
                    patch.object(fixture.tempfile, "mkdtemp", return_value=str(directory)), \
                    patch.object(fixture.subprocess, "run", return_value=fixture.subprocess.CompletedProcess([], 0,
                        json.dumps({"filesystems": [{"fstype": "xfs", "options": "prjquota"}]}))), \
                    patch.object(fixture.subprocess, "Popen"), \
                    patch.object(fixture, "wait_for_daemon", side_effect=original), \
                    patch.object(fixture, "cleanup", side_effect=RuntimeError("namespace busy")), \
                    redirect_stderr(stderr):
                with self.assertRaises(RuntimeError) as caught:
                    fixture.run()
            self.assertIs(caught.exception, original)
            self.assertIn('"error": "namespace busy"', stderr.getvalue())
            self.assertIn('"primary": "RuntimeError"', stderr.getvalue())

    def test_native_failure_remains_primary_and_cleanup_alone_fails_a_green_body(self):
        for original in (fixture.subprocess.CalledProcessError(1, "mvn"), None):
            with self.subTest(primary=original), tempfile.TemporaryDirectory() as parent:
                parent = Path(parent).resolve()
                directory = parent / "ravenroot-runner-quota-fixture"
                directory.mkdir()
                cleanup_error = RuntimeError("namespace remains mounted")
                stderr = io.StringIO()
                model = Mock(requests=1)
                model.configuration.return_value = {}
                endpoint = Mock()
                endpoint.__enter__ = Mock(return_value=model)
                endpoint.__exit__ = Mock(return_value=False)

                def command(arguments, **kwargs):
                    output = ""
                    if arguments[0] == "findmnt":
                        output = json.dumps({"filesystems": [{"fstype": "xfs", "options": "prjquota"}]})
                    elif arguments[:3] == ["docker", "image", "inspect"]:
                        output = "sha256:" + "a" * 64
                    elif arguments[:2] == ["docker", "run"]:
                        output = "RUNNER_NATIVE_READ_ONLY_CONFINEMENT=passed"
                    elif arguments[0] == "mvn" and original:
                        raise original
                    return fixture.subprocess.CompletedProcess(arguments, 0, output)

                with patch.object(fixture, "prerequisites", return_value=parent), \
                        patch.object(fixture.tempfile, "mkdtemp", return_value=str(directory)), \
                        patch.object(fixture.subprocess, "run", side_effect=command), \
                        patch.object(fixture.subprocess, "Popen"), \
                        patch.object(fixture, "wait_for_daemon", return_value={"Driver": "overlay2", "ServerVersion": "28"}), \
                        patch.object(fixture, "ROOT", directory), \
                        patch.object(fixture.shutil, "which", return_value="/usr/bin/true"), \
                        patch.object(fixture, "ModelProtocolEndpoint", return_value=endpoint), \
                        patch.object(fixture, "verify_report") as verify, \
                        patch.object(fixture, "cleanup", side_effect=cleanup_error), \
                        redirect_stderr(stderr), redirect_stdout(io.StringIO()):
                    with self.assertRaises(Exception) as caught:
                        fixture.run()
                self.assertIs(caught.exception, original if original else cleanup_error)
                self.assertEqual(verify.call_count, 0 if original else 1)
                self.assertIn("RUNNER_QUOTA_CLEANUP_FAILURE=", stderr.getvalue())

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

    def test_secretless_configuration_is_mandatory_and_ambient_live_profile_is_refused(self):
        with patch.object(fixture.platform, "system", return_value="Linux"), \
                patch.object(fixture.platform, "machine", return_value="x86_64"), \
                patch.object(fixture.shutil, "which", return_value="/operator/tool"), \
                patch.object(fixture.subprocess, "run") as command, tempfile.TemporaryDirectory() as directory:
            environment = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "RUNNER_TEMP": directory}
            self.assertEqual(fixture.prerequisites(environment), Path(directory).resolve())
            with self.assertRaisesRegex(RuntimeError, "external model configuration is forbidden"):
                fixture.prerequisites(dict(environment, RAVENROOT_RUNNER_ACCEPTANCE_CONFIG="/unused/live.json"))
            command.assert_not_called()

    def test_only_one_executed_green_model_backed_case_is_acceptance(self):
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
