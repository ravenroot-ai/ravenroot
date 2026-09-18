"""The required native fixture must stay isolated, secretless, quota-backed and zero-skip."""
import ast
import json
import importlib.util
import itertools
from pathlib import Path
import re
import tempfile
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
