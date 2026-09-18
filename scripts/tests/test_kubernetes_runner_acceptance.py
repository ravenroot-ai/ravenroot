"""The required native fixture must stay isolated, secretless, quota-backed and zero-skip."""
import json
import importlib.util
import itertools
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from scripts.fixtures import kubernetes_runner_acceptance as fixture


class KubernetesAcceptanceFixtureTest(unittest.TestCase):
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
        self.assertEqual("v1.35.1", fixture.KUBERNETES)
        self.assertEqual("v1.38.1", fixture.MINIKUBE)
        workflow = (fixture.ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("releases/download/" + fixture.MINIKUBE + "/minikube-linux-amd64", workflow)
        self.assertIn("release/" + fixture.KUBERNETES + "/bin/linux/amd64/kubectl", workflow)
        self.assertIn("36e2f4ac66259232341dd7866952d64a958846470f6a9a6a813b9117bd965207", workflow)
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
