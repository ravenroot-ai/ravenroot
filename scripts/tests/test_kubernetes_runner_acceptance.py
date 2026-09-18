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
        self.assertEqual("v1.35.4", fixture.KUBERNETES)
        source = Path(fixture.__file__).read_text()
        for required in ('"skipped": "0"', 'FELIX_IPTABLESBACKEND=NFT', 'native acceptance left'):
            self.assertIn(required, source)
        self.assertNotIn("continue-on-error", source)


if __name__ == "__main__":
    unittest.main()
