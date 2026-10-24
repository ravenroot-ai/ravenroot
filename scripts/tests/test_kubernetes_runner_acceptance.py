"""The required native fixture must stay isolated, secretless, quota-backed and zero-skip."""
import ast
import json
import importlib.util
import itertools
import io
from contextlib import ExitStack, redirect_stdout, redirect_stderr
from types import SimpleNamespace
from pathlib import Path
import re
import tempfile
import subprocess
import sys
import unittest
from unittest.mock import patch
from scripts.fixtures import kubernetes_runner_acceptance as fixture


class KubernetesAcceptanceFixtureTest(unittest.TestCase):
    def support_sources(self):
        return {name: (fixture.ROOT / path).read_text() for name, path in {
            "adr": "adr/0043-kubernetes-native-governed-runner.md",
            "guide": "docs/operator-guide/kubernetes-runners.md",
            "docker-guide": "docs/operator-guide/governed-runners.md",
            "workflow": ".github/workflows/ci.yml",
            "fixture": "scripts/fixtures/kubernetes_runner_acceptance.py",
        }.items()}

    def assert_support_contract(self, sources):
        # Reviewed immutable acceptance substrate, not an independently selectable target.
        # Updating it requires renewed native evidence and all public claims to move together.
        pins = {
            "MINIKUBE": "v1.38.1", "KUBERNETES": "v1.35.1",
            "CLUSTER_IMAGE": "gcr.io/k8s-minikube/kicbase:v0.0.50@sha256:eb4fec00e8ad70adf8e6436f195cc429825ffb85f95afcdb5d8d9deb576f3e93",
            "BASE_IMAGE": "python@sha256:e81548ac35b07a3bd4805f275107592ef458b1e893c0e04d45aedaa19416cca5",
        }
        tree = ast.parse(sources["fixture"])
        assignments = [(target.id, ast.literal_eval(node.value)) for node in tree.body
            if isinstance(node, ast.Assign) for target in node.targets
            if isinstance(target, ast.Name) and target.id in pins]
        self.assertCountEqual(list(pins.items()), assignments)
        version = pins["KUBERNETES"].removeprefix("v")
        supported = {"Kubernetes": version, "kubectl": version,
                     "Minikube": pins["MINIKUBE"].removeprefix("v"),
                     "kicbase": pins["CLUSTER_IMAGE"].split(":v", 1)[1].split("@", 1)[0]}
        for name in ("adr", "guide", "docker-guide"):
            # Check every relevant claim, including contradictory duplicates; finding one
            # correct sentence must not hide a stale support matrix or later evidence note.
            for tool, observed in re.findall(r"\b(Kubernetes|kubectl|Minikube|kicbase)\s+v?(\d+\.\d+\.\d+)\b", sources[name]):
                self.assertEqual(supported[tool], observed, name + ": " + tool)
        self.assertEqual([version], re.findall(r"Kubernetes\s+(\d+\.\d+\.\d+)\s+is the native CI target", sources["adr"]))
        self.assertEqual([version], re.findall(r"Native acceptance target\s+(\d+\.\d+\.\d+)", sources["guide"]))
        self.assertEqual([version], re.findall(r"Native CI pins kubectl\s+(\d+\.\d+\.\d+)", sources["guide"]))
        self.assertEqual([(supported["Minikube"], version, supported["kicbase"])], re.findall(
            r"fixture requires Minikube\s+(\d+\.\d+\.\d+), Kubernetes\s+(\d+\.\d+\.\d+)"
            r"\s+and the digest-pinned kicbase\s+(\d+\.\d+\.\d+)", sources["guide"]))

        jobs = re.findall(r"(?ms)^  full-backend-tests:\n(.*?)(?=^  [\w-]+:|\Z)", sources["workflow"])
        self.assertEqual(1, len(jobs))
        steps = re.findall(r"(?ms)^      - name: Install pinned native Kubernetes acceptance tools\n(.*?)(?=^      - name:|\Z)", jobs[0])
        self.assertEqual(1, len(steps))
        active = "\n".join(line for line in steps[0].splitlines() if not line.lstrip().startswith("#"))
        self.assertEqual([
            "https://github.com/kubernetes/minikube/releases/download/" + pins["MINIKUBE"] + "/minikube-linux-amd64",
            "https://dl.k8s.io/release/" + pins["KUBERNETES"] + "/bin/linux/amd64/kubectl",
        ], re.findall(r"(?m)^\s*curl\s+[^\n]*?(https://\S+)", active))
        self.assertEqual([
            ("099477eaf248bcb5bcea8ce78a2898e93ac01461c35189da1848c3de82ecd22e", "minikube"),
            ("36e2f4ac66259232341dd7866952d64a958846470f6a9a6a813b9117bd965207", "kubectl"),
        ], re.findall(r"([a-f0-9]{64})  %s/(minikube|kubectl)", active))
        self.assertIn("| sha256sum --check --strict", active)
        self.assertRegex(jobs[0], r"(?m)^        run: python3 scripts/fixtures/kubernetes_runner_acceptance\.py$")

        # The emitted evidence must use the same pin as the actual startup (exercised
        # below), not an unrelated literal that could falsely report a supported target.
        evidence = [node.args[0].right for node in ast.walk(tree)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "print"
            and node.args and isinstance(node.args[0], ast.BinOp) and isinstance(node.args[0].op, ast.Add)
            and isinstance(node.args[0].left, ast.Constant) and node.args[0].left.value == "KUBERNETES_RUNNER_ACCEPTANCE="]
        self.assertEqual(1, len(evidence))
        self.assertEqual("json.dumps", ast.unparse(evidence[0].func))
        payload = evidence[0].args[0]
        self.assertIsInstance(payload, ast.Dict)
        reported = [value for key, value in zip(payload.keys, payload.values)
                    if isinstance(key, ast.Constant) and key.value == "kubernetes"]
        self.assertEqual(["KUBERNETES"], [ast.unparse(value) for value in reported])

    def test_public_support_claims_ci_tools_images_and_reported_evidence_share_reviewed_pins(self):
        self.assert_support_contract(self.support_sources())

    def test_support_contract_rejects_independent_drift_and_contradictory_claims(self):
        sources = self.support_sources()
        mutations = [
            ("adr", "1.35.1", "1.35.4"),
            ("guide", "Native acceptance target 1.35.1", "Native acceptance target 1.35.4"),
            ("guide", "kubectl 1.35.1", "kubectl 1.35.4"),
            ("guide", "Minikube 1.38.1", "Minikube 1.39.0"),
            ("guide", "kicbase 0.0.50", "kicbase 0.0.51"),
            ("fixture", 'KUBERNETES = "v1.35.1"', 'KUBERNETES = "v1.35.4"'),
            ("fixture", 'MINIKUBE = "v1.38.1"', 'MINIKUBE = "v1.39.0"'),
            ("fixture", "eb4fec00", "ab4fec00"),
            ("fixture", "e81548ac", "a81548ac"),
            ("fixture", '"kubernetes": KUBERNETES', '"kubernetes": "v1.35.4"'),
            ("workflow", "releases/download/v1.38.1", "releases/download/v1.39.0"),
            ("workflow", "release/v1.35.1", "release/v1.35.4"),
            ("workflow", "099477ea", "199477ea"),
            ("workflow", "36e2f4ac", "46e2f4ac"),
            ("workflow", "| sha256sum --check --strict", "| cat"),
            ("workflow", "run: python3 scripts/fixtures/kubernetes_runner_acceptance.py", "run: true"),
        ]
        for name, before, after in mutations:
            with self.subTest(surface=name, mutation=before):
                self.assertIn(before, sources[name])
                changed = dict(sources, **{name: sources[name].replace(before, after)})
                with self.assertRaises(AssertionError):
                    self.assert_support_contract(changed)
        for name in ("adr", "guide", "docker-guide"):
            with self.subTest(contradictory_claim=name), self.assertRaises(AssertionError):
                self.assert_support_contract(dict(sources, **{name: sources[name] + "\nKubernetes 1.35.4 is supported.\n"}))

    def test_cpu_attestation_waits_for_positive_kernel_evidence_and_refuses_its_absence(self):
        spec = importlib.util.spec_from_file_location("native_attestation", fixture.ROOT / "docs/examples/governed-runner/kubernetes_attestation.py")
        attester = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(attester)
        for positive in (True, False):
            observations = itertools.count()
            ticks = itertools.count()
            def read(path):
                if path.name == "memory.max":
                    return "134217728"
                if path.name == "cpu.max":
                    return "50000 100000"
                return "nr_throttled " + str(int(positive and next(observations) >= 3))
            with patch.object(attester.pathlib.Path, "read_text", autospec=True, side_effect=read), \
                    patch.object(attester.time, "monotonic", side_effect=lambda: next(ticks) / 10), \
                    patch.dict(attester.os.environ, {"RAVENROOT_MEMORY_LIMIT_BYTES": "134217728", "RAVENROOT_CPU_MILLICORES": "500"}):
                if positive:
                    self.assertTrue(attester.cgroups()["cpuThrottlingObserved"])
                else:
                    with self.assertRaisesRegex(RuntimeError, "positive CPU throttling evidence is missing"):
                        attester.cgroups()

    @staticmethod
    def attester():
        spec = importlib.util.spec_from_file_location("diagnostic_attestation", fixture.ROOT / "docs/examples/governed-runner/kubernetes_attestation.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def attestation_environment(self, module):
        stack = ExitStack()
        stack.enter_context(patch.object(module, "network_isolated"))
        stack.enter_context(patch.object(module.os, "getuid", return_value=65532))
        stack.enter_context(patch.object(module.os, "getgid", return_value=65532))
        stack.enter_context(patch.object(module.pathlib.Path, "read_text", return_value="CapEff: 0\nNoNewPrivs: 1\n"))
        stack.enter_context(patch.object(module.pathlib.Path, "exists", return_value=False))
        stack.enter_context(patch.object(module.os, "statvfs", return_value=SimpleNamespace(f_flag=module.os.ST_RDONLY)))
        stack.enter_context(patch.object(module.ctypes, "CDLL", return_value=SimpleNamespace(syscall=lambda *args: 7)))
        stack.enter_context(patch.dict(module.os.environ, {"RAVENROOT_WORKSPACE_LIMIT_BYTES": "67108864", "RAVENROOT_PROCESS_LIMIT": "256"}))
        stack.enter_context(patch.object(module, "quota", return_value={"withinQuotaWrite": True}))
        stack.enter_context(patch.object(module, "processes", return_value={"processBoundaryRejected": True}))
        stack.enter_context(patch.object(module, "cgroups", return_value={"cpuThrottlingObserved": True}))
        stack.enter_context(patch.object(module, "initialize_workspace"))
        return stack

    def test_original_failure_markers_are_finite_single_attempt_and_never_echo_dynamic_data(self):
        module = self.attester()
        cases = [
            ("network_isolated", RuntimeError("secret-token /private/host/path"), "NETWORK:REFUSED"),
            ("quota", OSError("secret filesystem"), "STORAGE:OS_ERROR"),
            ("processes", module.subprocess.CalledProcessError(4, "secret command", output=b"secret stdout", stderr=b"secret stderr"), "PROCESS:PROCESS_EXIT"),
            ("processes", module.subprocess.TimeoutExpired("secret command", 30, output=b"secret stdout"), "PROCESS:PROCESS_TIMEOUT"),
            ("cgroups", RuntimeError("positive CPU throttling evidence is missing"), "CGROUP:CPU_THROTTLING_MISSING"),
            ("cgroups", ValueError("secret value"), "CGROUP:INVALID_VALUE"),
            ("initialize_workspace", RuntimeError("Workspace seed initialization is uncertain; refusing a fresh clone"), "WORKSPACE:SEED_UNCERTAIN"),
            ("initialize_workspace", RuntimeError("Workspace initialization identity changed"), "WORKSPACE:SEED_IDENTITY_CHANGED"),
            ("initialize_workspace", ZeroDivisionError("secret unknown"), "WORKSPACE:UNKNOWN"),
        ]
        for function, failure, marker in cases:
            with self.subTest(marker=marker), self.attestation_environment(module), \
                    patch.object(module, function, side_effect=failure) as called, \
                    redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()) as err:
                self.assertEqual(1, module.entrypoint([]))
                self.assertEqual("", out.getvalue())
                self.assertEqual("RR_ATTESTATION_FAILURE_V1:" + marker + "\n", err.getvalue())
                self.assertEqual(1, called.call_count, "diagnostics cannot retry the original probe")

    def test_original_fixed_entrypoint_preserves_success_proof_and_fail_closed_identity(self):
        module = self.attester()
        with self.attestation_environment(module), redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()) as err:
            self.assertEqual(0, module.entrypoint([]))
            self.assertEqual({"withinQuotaWrite": True, "processBoundaryRejected": True, "cpuThrottlingObserved": True,
                              "protocolVersion": 1, "nonRoot": True, "capabilitiesDropped": True, "noNewPrivileges": True,
                              "readOnlyRoot": True, "landlockAbi": 7, "serviceAccountAbsent": True, "networkBlocked": True}, json.loads(out.getvalue()))
            self.assertEqual("", err.getvalue())
        with self.attestation_environment(module), patch.object(module.os, "getuid", return_value=0), \
                redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()) as err:
            self.assertEqual(1, module.entrypoint([]))
            self.assertEqual("", out.getvalue())
            self.assertEqual("RR_ATTESTATION_FAILURE_V1:IDENTITY:REFUSED\n", err.getvalue())
            module.quota.assert_not_called()
        for args, phase in [(["--network-control"], "CONTROL"), (["secret-argument"], "ARGUMENTS")]:
            with patch.object(module, "network_isolated", side_effect=RuntimeError("secret")) as called, \
                    redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()) as err:
                self.assertEqual(1, module.entrypoint(args))
                self.assertEqual("", out.getvalue())
                reason = "REFUSED" if phase == "CONTROL" else "INVALID_VALUE"
                self.assertEqual("RR_ATTESTATION_FAILURE_V1:" + phase + ":" + reason + "\n", err.getvalue())
                self.assertEqual(int(phase == "CONTROL"), called.call_count)

    def test_actual_original_executable_missing_configuration_is_closed_and_nonzero(self):
        path = fixture.ROOT / "docs/examples/governed-runner/kubernetes_attestation.py"
        result = subprocess.run([sys.executable, str(path)], env={"UNRELATED_SECRET": "secret-token"},
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5)
        self.assertEqual(1, result.returncode)
        self.assertEqual(b"", result.stdout)
        self.assertEqual(b"RR_ATTESTATION_FAILURE_V1:NETWORK:INVALID_VALUE\n", result.stderr)

    def test_unexpected_or_forged_executable_failure_still_cannot_echo_exception_data(self):
        module = self.attester()
        for failure in (RuntimeError("secret outside a phase"), module.DiagnosticFailure("secret-phase", "secret-reason"), module.DiagnosticFailure(["secret"], {"secret": 1})):
            with patch.object(module, "run", side_effect=failure), redirect_stdout(io.StringIO()) as out, redirect_stderr(io.StringIO()) as err:
                self.assertEqual(1, module.entrypoint([]))
                self.assertEqual("", out.getvalue())
                self.assertEqual("RR_ATTESTATION_FAILURE_V1:UNKNOWN:UNKNOWN\n", err.getvalue())

    def test_retained_native_results_never_copy_properties_messages_or_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, count in fixture.CLASSES.items():
                cases = []
                for index, case in enumerate(fixture.NATIVE_CASES[name]):
                    failure = ("<error message='secret-token'>secret-stdout /private/path "
                        "Native Kubernetes case scope=PROCESS_INSTANCE lifecycle=PER_INVOCATION index=4 command=IMPLEMENT; effect remains unconfirmed "
                        "Kubernetes operation UNAVAILABLE (client exit 1); effect remains unconfirmed; command=ATTEST diagnostic=ATTESTER_MARKER phase=CGROUP reason=CPU_THROTTLING_MISSING"
                        "</error>") if index == 0 else ""
                    cases.append("<testcase classname='" + name + "' name='" + case + "'>" + failure + "<system-out>secret-out</system-out></testcase>")
                xml = "<testsuite name='" + name + "' tests='" + str(count) + "' failures='0' errors='1' skipped='0'><properties><property name='credential' value='secret-config'/></properties>" + "".join(cases) + "</testsuite>"
                (root / ("TEST-" + name + ".xml")).write_text(xml)
            fixture.retain_reports(root, root / "safe")
            self.assertEqual({"KubernetesPodRunnerNativeTest.json", "KubernetesWorkspaceGraphNativeTest.json"}, {path.name for path in (root / "safe").iterdir()})
            for path in (root / "safe").iterdir():
                raw = path.read_text()
                self.assertNotIn("secret", raw)
                self.assertNotIn("/private", raw)
                report = json.loads(raw)
                self.assertTrue(report["available"])
                self.assertEqual(1, report["counts"]["errors"])
                self.assertEqual({"scope": "PROCESS_INSTANCE", "lifecycle": "PER_INVOCATION", "index": 4, "command": "IMPLEMENT"}, report["cases"][0]["context"])
                self.assertEqual({"category": "UNAVAILABLE", "exit": 1, "command": "ATTEST", "origin": "ATTESTER_MARKER", "phase": "CGROUP", "reason": "CPU_THROTTLING_MISSING"}, report["cases"][0]["diagnostic"])

    def test_missing_malformed_oversized_or_foreign_native_reports_are_explicitly_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = next(iter(fixture.CLASSES))
            for raw, expected in [(None, "MISSING"), ("<truncated secret", "MALFORMED"), ("X" * 4_194_305, "OVERSIZED"),
                                  ("<!DOCTYPE secret><testsuite/>", "MALFORMED"),
                                  ("<!DOCTYPE testsuite [<!ENTITY x 'secret'>]><testsuite>&x;</testsuite>".encode("utf-16"), "MALFORMED"),
                                  (b"\xff\xfe\x00\x00secret", "MALFORMED"), ("<testsuite name='secret'/>", "UNEXPECTED_SUITE")]:
                with self.subTest(expected=expected):
                    if raw is not None:
                        (root / ("TEST-" + name + ".xml")).write_bytes(raw if isinstance(raw, bytes) else raw.encode())
                    fixture.retain_reports(root, root / "safe")
                    report = json.loads((root / "safe/KubernetesPodRunnerNativeTest.json").read_text())
                    self.assertEqual({"schemaVersion": 1, "suite": name, "available": False, "unavailable": expected}, report)

    def test_contradictory_foreign_and_duplicate_case_statuses_are_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = next(iter(fixture.CLASSES))
            case = fixture.NATIVE_CASES[name][0]
            base = "<testsuite name='" + name + "' tests='1' failures='0' errors='0' skipped='0'><testcase classname='" + name + "' name='" + case + "'>{}</testcase></testsuite>"
            values = [base.format("<error/>"), base.format("<failure/><skipped/>"), base.format("<error/><error/>").replace("errors='0'", "errors='1'"),
                      base.format("").replace("classname='" + name, "classname='foreign"),
                      base.format("").replace("<testcase", "<testcase name='duplicate'"), base.format("").replace("tests='1'", "tests='0'")]
            for xml in values:
                with self.subTest(xml=xml):
                    (root / ("TEST-" + name + ".xml")).write_text(xml)
                    fixture.retain_reports(root, root / "safe")
                    report = json.loads((root / "safe/KubernetesPodRunnerNativeTest.json").read_text())
                    self.assertFalse(report["available"])
                    self.assertNotIn("cases", report)

    def test_retained_diagnostics_do_not_partially_accept_malformed_reason_suffixes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = next(iter(fixture.CLASSES))
            case = fixture.NATIVE_CASES[name][0]
            for suffix in ("1", "SECRET", "secret", "_FORGED"):
                xml = ("<testsuite name='" + name + "' tests='1' failures='0' errors='1' skipped='0'><testcase classname='" + name + "' name='" + case + "'><error>"
                       "Kubernetes operation UNAVAILABLE (client exit 1); effect remains unconfirmed; command=ATTEST diagnostic=ATTESTER_MARKER phase=CGROUP reason=CPU_THROTTLING_MISSING" + suffix + "</error></testcase></testsuite>")
                (root / ("TEST-" + name + ".xml")).write_text(xml)
                fixture.retain_reports(root, root / "safe")
                report = json.loads((root / "safe/KubernetesPodRunnerNativeTest.json").read_text())
                self.assertTrue(report["available"])
                self.assertEqual("ERROR", report["cases"][0]["status"])
                self.assertNotIn("diagnostic", report["cases"][0])

    def test_native_failure_handling_never_reexecutes_attester_and_uploads_only_safe_fixed_reports(self):
        source = Path(fixture.__file__).read_text()
        failure_path = source[source.index("retain_reports()", source.index("def run():")):source.index("verify_reports()", source.index("def run():"))]
        self.assertNotIn("subprocess", failure_path)
        self.assertNotIn("exec", failure_path)
        self.assertIn('if result.returncode:', failure_path)
        workflow = self.support_sources()["workflow"]
        upload = workflow.split("- name: Retain sanitized native Kubernetes results", 1)[1].split("- name:", 1)[0]
        self.assertIn("if: always() && steps.backend-scope.outputs.mode == 'all'", upload)
        self.assertEqual(["ravenroot/ravenroot-core/target/native-kubernetes-reports/KubernetesPodRunnerNativeTest.json",
                          "ravenroot/ravenroot-core/target/native-kubernetes-reports/KubernetesWorkspaceGraphNativeTest.json"],
                         re.findall(r"(?m)^            (\S+)$", upload))
        self.assertNotIn("surefire-reports", upload)

    def test_inventory_uses_finite_retained_pvs_and_minimal_manager_authority(self):
        objects = fixture.resources("owned-node")["items"]
        volumes = [value for value in objects if value["kind"] == "PersistentVolume"]
        self.assertEqual(16, len(volumes))
        for volume in volumes:
            self.assertEqual("64Mi", volume["spec"]["capacity"]["storage"])
            self.assertEqual("Retain", volume["spec"]["persistentVolumeReclaimPolicy"])
            self.assertNotIn("hostPath", volume["spec"])
        role = next(value for value in objects if value["kind"] == "Role")
        self.assertEqual({"pods", "persistentvolumeclaims", "pods/exec"}, {resource for rule in role["rules"] for resource in rule["resources"]})
        self.assertNotIn("secrets", json.dumps(role))

    def test_ephemeral_manager_token_file_is_owner_only(self):
        cluster = {"clusters": [{"cluster": {"server": "https://127.0.0.1", "certificate-authority-data": "public-fixture"}}]}
        with tempfile.TemporaryDirectory() as root, patch.object(fixture, "command", side_effect=[json.dumps(cluster).encode(), b"public-fixture-token"]):
            path = fixture.manager_configuration(["kubectl", "--context=owned"], Path(root))
            self.assertEqual(0o600, path.stat().st_mode & 0o777)
            self.assertEqual("public-fixture-token", json.loads(path.read_text())["users"][0]["user"]["token"])

    def test_required_suite_counts_every_native_case_without_skips(self):
        self.assertEqual(5, sum(fixture.CLASSES.values()))
        source = Path(fixture.__file__).read_text()
        for required in ('"skipped": "0"', 'FELIX_IPTABLESBACKEND=NFT', 'native acceptance left'):
            self.assertIn(required, source)
        self.assertNotIn("continue-on-error", source)

    def test_wrong_tool_version_or_broken_host_bridge_prevents_cluster_creation(self):
        for version in (b"v1.37.0", fixture.MINIKUBE.encode()):
            def command(args, **kwargs):
                if args[:2] == ["docker", "info"]:
                    return b'{"OSType":"linux"}'
                if args[:2] == ["minikube", "version"]:
                    return version
                self.assertEqual(["docker", "run", "--rm", "--network=bridge", "--entrypoint=/bin/true", fixture.CLUSTER_IMAGE], args)
                raise RuntimeError("bridge attachment refused")
            with patch.object(fixture.shutil, "which", return_value="/tool"), \
                    patch.dict(fixture.os.environ, {}, clear=True), patch.object(fixture, "command", side_effect=command) as run:
                with self.assertRaisesRegex(RuntimeError, "repository-pinned|bridge attachment"):
                    fixture.run()
                self.assertEqual(2 if version != fixture.MINIKUBE.encode() else 3, run.call_count)

    def test_supported_version_and_digest_are_used_by_real_cluster_start_without_fallback(self):
        def command(args, **kwargs):
            if args[:2] == ["docker", "info"]:
                return b'{"OSType":"linux"}'
            if args[:2] == ["minikube", "version"]:
                return fixture.MINIKUBE.encode()
            if args[:2] == ["minikube", "start"]:
                for option in ("--kubernetes-version=" + fixture.KUBERNETES,
                               "--base-image=" + fixture.CLUSTER_IMAGE, "--driver=docker", "--container-runtime=containerd"):
                    self.assertIn(option, args)
                raise RuntimeError("cluster start refused")
            return b""
        with patch.object(fixture.shutil, "which", return_value="/tool"), \
                patch.dict(fixture.os.environ, {}, clear=True), patch.object(fixture, "command", side_effect=command) as run:
            with self.assertRaisesRegex(RuntimeError, "cluster start refused"):
                fixture.run()
            self.assertEqual(1, sum(call.args[0][:2] == ["minikube", "start"] for call in run.call_args_list))
            self.assertEqual(["minikube", "delete"], run.call_args.args[0][:2])


if __name__ == "__main__":
    unittest.main()
