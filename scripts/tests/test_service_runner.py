"""Tokenless service mode's rendered boundary and supervision selection, without Docker effects."""
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ServiceRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = self.enterContext(tempfile.TemporaryDirectory())
        self.root = Path(self.temp)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.log = self.root / "calls"
        self.config = self.root / "runner"
        self.config.mkdir()
        (self.config / "worker.json").write_text(json.dumps({"tenantId": "local"}))
        (self.config / "control-plane.json").write_text("{}")
        self.socket = self.enterContext(socket.socket(socket.AF_UNIX))
        self.socket.bind(str(self.root / "docker.sock"))
        docker = self.bin / "docker"
        docker.write_text('''#!/bin/sh
printf '%s\\n' "$*" >> "$TEST_CALLS"
case "$*" in *"config --format json") printf '%s\\n' "$TEST_COMPOSE_JSON" ;; esac
''')
        docker.chmod(0o755)
        self.render = {"services": {"ravenroot": {"environment": {
            "RAVENROOT_AUTH_MODE": "disabled", "RAVENROOT_BIND_ADDRESS": "0.0.0.0",
            "RAVENROOT_CONTAINER_LOOPBACK_ONLY": "true", "RAVENROOT_LOCAL_HOST_BIND_ADDRESS": "127.0.0.1",
            "RAVENROOT_LOCAL_RUNNER_CONFIG": "/etc/ravenroot/local-runner/worker.json",
            "RAVENROOT_RUNNER_CONFIG": "/etc/ravenroot/local-runner/control-plane.json"},
            "ports": [{"mode": "ingress", "host_ip": "127.0.0.1", "target": 8080, "published": "8080", "protocol": "tcp"}]}}}
        self.environment = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"], TEST_CALLS=str(self.log),
            RAVENROOT_COMPOSE_OVERRIDE_FILE="", RAVENROOT_LOCAL_RUNNER_DIR=str(self.config),
            RAVENROOT_DOCKER_CLI_IMAGE="docker@sha256:" + "0" * 64,
            RAVENROOT_LOCAL_DOCKER_SOCKET=str(self.root / "docker.sock"), RAVENROOT_HOST_BIND_ADDRESS="127.0.0.1")

    def run_service(self, command="start", **environment):
        return subprocess.run([str(ROOT / "service.sh"), command, "--runner", "--skipimage"],
            env=dict(self.environment, TEST_COMPOSE_JSON=json.dumps(self.render), **environment),
            capture_output=True, text=True, timeout=10)

    def test_start_restart_and_stop_choose_supervised_tokenless_overlay_without_worker_process_or_token(self):
        for command in ("start", "restart", "stop"):
            result = self.run_service(command)
            self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.log.read_text()
        self.assertIn("deploy/dev/compose.runner.yaml", calls)
        self.assertIn("up --detach --no-build --force-recreate", calls)
        self.assertIn("down --remove-orphans", calls)
        self.assertNotIn("token", calls.lower())
        self.assertNotIn("RunnerWorkerMain", calls)

    def test_off_loopback_missing_native_socket_and_credential_shaped_config_fail_before_start(self):
        result = self.run_service(RAVENROOT_HOST_BIND_ADDRESS="0.0.0.0")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.log.exists())
        result = self.run_service(RAVENROOT_LOCAL_DOCKER_SOCKET=str(self.root / "missing"))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.log.exists())
        for field in ("endpoint", "tokenFile"):
            (self.config / "worker.json").write_text(json.dumps({"tenantId": "local", field: "forbidden"}))
            self.assertNotEqual(self.run_service().returncode, 0)
            self.assertFalse(self.log.exists())

    def test_additional_or_wildcard_publication_is_refused_before_lifecycle(self):
        self.render["services"]["ravenroot"]["ports"][0]["host_ip"] = "0.0.0.0"
        self.assertNotEqual(self.run_service().returncode, 0)
        self.assertNotIn(" up ", self.log.read_text())


if __name__ == "__main__":
    unittest.main()
