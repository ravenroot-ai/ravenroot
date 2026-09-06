import unittest

from scripts.check_operational_docs import assistant_variables, cli_tokens, core_nodes, script_contract_tokens


class CheckOperationalDocsTest(unittest.TestCase):
    def test_contract_sources_expose_expected_high_risk_tokens(self):
        scripts = script_contract_tokens()
        self.assertTrue({"--all", "--driver-jar", "check-published"} <= scripts["plugin.sh"])
        self.assertTrue({"--skipimage", "RAVENROOT_IMAGE_DIGEST"} <= scripts["service.sh"])
        self.assertTrue({"--with-tests", "bench"} <= scripts["dev.sh"])
        self.assertTrue(
            {"RAVENROOT_RUN_DIR", "RAVENROOT_ARTIFACT_DUAL_CONTROL"}
            <= scripts["ravenroot/scripts/server.sh"]
        )
        self.assertTrue(
            {"deployments", "credentials", "embed-registration", "--gate-eea", "--graphml"}
            <= cli_tokens()
        )

    def test_assistant_inventory_includes_provider_and_device_flow(self):
        variables = assistant_variables()
        self.assertIn("RAVENROOT_ASSISTANT_PROVIDER", variables)
        self.assertIn("RAVENROOT_ASSISTANT_DEVICE_AUTHORIZATION_ENDPOINT", variables)
        self.assertIn("RAVENROOT_ASSISTANT_CONSENT_DIR", variables)

    def test_standard_core_catalog_is_derived_from_composition(self):
        self.assertEqual(11, len(core_nodes()))
        self.assertTrue({"human-task", "http-request", "boundary-guard"} <= core_nodes())


if __name__ == "__main__":
    unittest.main()
