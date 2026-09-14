"""Safety/positive-result contracts for the opt-in host-backed writable fixture."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
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
