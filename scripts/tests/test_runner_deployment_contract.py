"""Render deployment topology; this is not proof of runtime quota or model enforcement."""
import json
import configparser
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RunnerDeploymentContract(unittest.TestCase):
    def test_systemd_templates_supervise_independent_instances_and_stop_their_process_groups(self):
        units = {}
        for name, main in (("ravenroot-runner@.service", "RunnerWorkerMain"),
                           ("ravenroot-runner-coordinator@.service", "RunnerCoordinatorMain")):
            unit = configparser.ConfigParser(interpolation=None)
            unit.read(ROOT / "deploy/systemd" / name)
            units[name] = unit
            self.assertEqual("simple", unit["Service"]["Type"])
            self.assertEqual("on-failure", unit["Service"]["Restart"])
            self.assertEqual("control-group", unit["Service"]["KillMode"])
            self.assertEqual("true", unit["Service"]["NoNewPrivileges"])
            self.assertEqual("strict", unit["Service"]["ProtectSystem"])
            self.assertIn("ai.ravenroot.server." + main, unit["Service"]["ExecStart"])
            self.assertIn("%i.environment", unit["Service"]["EnvironmentFile"])
            self.assertEqual("multi-user.target", unit["Install"]["WantedBy"])
            self.assertNotIn("ravenroot.service", unit["Unit"].get("Requires", ""))
        worker = units["ravenroot-runner@.service"]
        self.assertEqual("docker.service", worker["Unit"]["Requires"])
        self.assertEqual("RAVENROOT_RUNNER_INSTANCE=%i", worker["Service"]["Environment"])
        self.assertIn("/etc/ravenroot/workers/%i.json", worker["Service"]["ExecStart"])
        self.assertEqual("ravenroot-runner-%i", worker["Service"]["StateDirectory"])
        coordinator = units["ravenroot-runner-coordinator@.service"]
        self.assertNotIn("docker.service", coordinator["Unit"].get("Requires", ""))
        self.assertNotIn("SupplementaryGroups", coordinator["Service"])

    @unittest.skipUnless(shutil.which("helm"), "Helm is required")
    def test_coordinators_and_two_worker_pools_scale_independently_without_replicating_graph_authority(self):
        pools = [{"name": name, "replicas": replicas, "image": "registry.example.test/worker@sha256:" + "a" * 64,
                  "configMap": name + "-config", "identitySecret": name + "-identities", "modelSecret": "model",
                  "socketHostPath": "/var/run/docker.sock", "nodeSelector": {"ravenroot.ai/quota-host": "true"},
                  "stateSize": "1Gi", "storageClass": "", "resources": {
                      "requests": {"cpu": "100m", "memory": "256Mi"}, "limits": {"cpu": "1", "memory": "1Gi"}}}
                 for name, replicas in (("development", 2), ("analysis", 7))]
        values = {"auth": {"issuer": "https://idp.example.test/", "audience": "fixture", "jwksUri": "https://idp.example.test/jwks"},
                  "runnerPlane": {"enabled": True, "configMap": "runner-catalog", "sharedEnvironmentSecret": "shared-authority",
                                  "artifactClaim": "artifacts-rwx", "coordinatorReplicas": 3, "workerPools": pools,
                                  "coordinatorPort": 8188, "coordinatorHttpThreads": 3, "coordinatorHttpQueue": 11,
                                  "coordinatorResources": {"limits": {"memory": "512Mi"}}}}
        with tempfile.TemporaryDirectory(prefix="ravenroot-runner-render-") as temporary:
            path = Path(temporary) / "values.json"
            path.write_text(json.dumps(values))
            def render(*extra):
                return subprocess.run(["helm", "template", "fixture", str(ROOT / "deploy/helm/ravenroot"), "-f", str(path), *extra],
                                      check=False, capture_output=True, text=True)
            rendered = render()
            self.assertEqual(0, rendered.returncode, rendered.stderr)
            documents = rendered.stdout.split("---")
            coordinator = next(doc for doc in documents if "kind: Deployment" in doc and "RunnerCoordinatorMain" in doc)
            self.assertRegex(coordinator, r"replicas: 3\b")
            self.assertIn("RAVENROOT_RUNNER_SHARED_ARTIFACTS", coordinator)
            self.assertIn("containerPort: 8188", coordinator)
            self.assertIn('value: "3"', coordinator)
            self.assertIn('value: "11"', coordinator)
            self.assertIn("memory: 512Mi", coordinator)
            self.assertNotIn("docker.sock", coordinator)
            graph = next(doc for doc in documents if "kind: Deployment" in doc and "RunnerCoordinatorMain" not in doc)
            self.assertRegex(graph, r"replicas: 1\b")
            self.assertIn("shared-authority", graph)
            self.assertNotIn("RAVENROOT_EXECUTION_STORE_DIR", graph)
            workers = [doc for doc in documents if "kind: StatefulSet" in doc]
            self.assertEqual(2, len(workers))
            for pool in pools:
                worker = next(doc for doc in workers if "worker-" + pool["name"] in doc)
                self.assertRegex(worker, "replicas: " + str(pool["replicas"]) + r"\b")
                for required in ("volumeClaimTemplates:", "metadata.name", "automountServiceAccountToken: false",
                                 "readOnlyRootFilesystem: true", "capabilities: { drop: [ALL] }", "defaultMode: 256"):
                    self.assertIn(required, worker)
            self.assertNotEqual(0, render("--set", "replicaCount=2").returncode)
            self.assertNotEqual(0, render("--set", "runnerPlane.coordinatorReplicas=0").returncode)
            self.assertNotEqual(0, render("--set", "runnerPlane.workerPools[1].name=development").returncode)

    @unittest.skipUnless(shutil.which("docker"), "Docker Compose is required")
    def test_compose_supervises_workers_with_separate_identity_and_state(self):
        environment = dict(os.environ)
        temporary = tempfile.TemporaryDirectory(prefix="ravenroot-compose-fixture-")
        self.addCleanup(temporary.cleanup)
        credentials = Path(temporary.name) / "model.env"
        credentials.write_text("# Empty conformance fixture; no credential required for manifest rendering.\n")
        environment.update({"RAVENROOT_WORKER_IMAGE": "worker@sha256:" + "a" * 64,
                            "RAVENROOT_AUTH_ISSUER": "https://idp.example.test/", "RAVENROOT_AUTH_AUDIENCE": "fixture",
                            "RAVENROOT_RUNNER_CONFIG_FILE": "/operator/control-plane.json",
                            "RAVENROOT_WORKER_A_CONFIG": "/operator/worker-a.json",
                            "RAVENROOT_WORKER_B_CONFIG": "/operator/worker-b.json",
                            "RAVENROOT_WORKER_A_TOKEN": "/operator/worker-a.jwt",
                            "RAVENROOT_WORKER_B_TOKEN": "/operator/worker-b.jwt",
                            "RAVENROOT_WORKER_MODEL_ENV_FILE": str(credentials),
                            "RAVENROOT_DOCKER_SOCKET": "/var/run/dedicated-docker.sock",
                            "RAVENROOT_DOCKER_SOCKET_GID": "999"})
        result = subprocess.run(["docker", "compose", "-f", "compose.yaml", "-f", "docs/examples/governed-runner/compose.override.yaml",
                                 "config", "--format", "json"], cwd=ROOT, env=environment, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        services = json.loads(result.stdout)["services"]
        for name in ("worker-a", "worker-b"):
            worker = services[name]
            self.assertEqual("unless-stopped", worker["restart"])
            self.assertTrue(worker["read_only"])
            self.assertEqual(["ALL"], worker["cap_drop"])
        self.assertNotIn("/var/run/docker.sock", json.dumps(services["ravenroot"]))


if __name__ == "__main__":
    unittest.main()
