"""Render maintained documentation overrides through Docker Compose."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]
OPENAPI_KEY = (
    "RAVENROOT_NODE_PACKAGE_SERVICES_"
    "61692E726176656E726F6F742E657874656E73696F6E732E6F70656E6170692E636C69656E74"
)


@unittest.skipUnless(shutil.which("docker"), "Docker Compose is required to render documentation examples")
class DocumentedComposeOverridesTest(unittest.TestCase):
    def render(self, override: str, additions: dict[str, str]) -> dict:
        environment = os.environ.copy()
        environment.update(additions)
        completed = subprocess.run(
            ["docker", "compose", "-f", "compose.yaml", "-f", override,
             "config", "--format", "json"],
            cwd=ROOT, env=environment, capture_output=True, text=True,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)
        return json.loads(completed.stdout)["services"]["ravenroot"]["environment"]

    def test_assistant_override_injects_a_ready_container_profile_and_private_name_exception(self) -> None:
        rendered = self.render("docs/examples/assistant/compose.override.yaml", {
            "RAVENROOT_ASSISTANT_PROVIDER": "openai-compatible",
            "RAVENROOT_ASSISTANT_ENDPOINT": "http://host.docker.internal:11434/v1/chat/completions",
            "RAVENROOT_ASSISTANT_MODEL": "local-model",
            "RAVENROOT_ASSISTANT_ALLOWED_HOSTS": "host.docker.internal",
            "RAVENROOT_ASSISTANT_ALLOWED_PORTS": "11434",
            "RAVENROOT_ASSISTANT_ALLOW_LOCAL_HTTP": "true",
            "RAVENROOT_EGRESS_RESERVED_EXCEPTIONS": "host.docker.internal:PRIVATE",
        })
        self.assertEqual("openai-compatible", rendered["RAVENROOT_ASSISTANT_PROVIDER"])
        self.assertEqual("host.docker.internal", rendered["RAVENROOT_ASSISTANT_ALLOWED_HOSTS"])
        self.assertEqual("host.docker.internal:PRIVATE", rendered["RAVENROOT_EGRESS_RESERVED_EXCEPTIONS"])
        self.assertEqual("true", rendered["RAVENROOT_ASSISTANT_ALLOW_LOCAL_HTTP"])

    def test_package_grant_override_injects_canonical_base64_under_the_exact_hex_key(self) -> None:
        grant = {"capabilities": ["outbound-http"], "origins": [
            {"scheme": "https", "host": "api.example.com", "port": 443}]}
        encoded = base64.b64encode(json.dumps(grant, separators=(",", ":")).encode()).decode()
        rendered = self.render("docs/examples/plugins/compose.openapi-client.override.yaml", {
            "RAVENROOT_OPENAPI_CLIENT_GRANT": encoded,
        })
        self.assertEqual(encoded, rendered[OPENAPI_KEY])
        self.assertEqual(grant, json.loads(base64.b64decode(rendered[OPENAPI_KEY], validate=True)))


if __name__ == "__main__":
    unittest.main()
