package ai.ravenroot.server.deployment;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentCapConfigurationTest {

    @Test
    void anEmptyEnvironmentProducesTheDocumentedDefault() {
        var configuration = DeploymentCapConfiguration.fromEnvironment(Map.of());

        assertEquals(8, configuration.maxActiveDeployments());
        assertEquals(configuration, DeploymentCapConfiguration.defaults());
    }

    @Test
    void theVariableOverridesTheDefault() {
        var configuration = DeploymentCapConfiguration.fromEnvironment(
                Map.of(DeploymentCapConfiguration.MAX_ACTIVE_DEPLOYMENTS_VARIABLE, "3"));

        assertEquals(3, configuration.maxActiveDeployments());
    }

    @Test
    void theVariableNameIsExactlyTheConfirmedSeamIdentifier() {
        // RAVENROOT_MAX_ACTIVE_DEPLOYMENTS, not ...PER_POD or any other spelling. A silent rename would desync
        // from what ravenroot-core's activation admission actually reads.
        assertEquals("RAVENROOT_MAX_ACTIVE_DEPLOYMENTS", DeploymentCapConfiguration.MAX_ACTIVE_DEPLOYMENTS_VARIABLE);
    }

    @Test
    void zeroIsALegitimateChoiceThatRejectsEveryActivation() {
        // A pod reserved for one-shot/playground submissions only (ADR 0021 D2) sets this to 0.
        var configuration = DeploymentCapConfiguration.fromEnvironment(
                Map.of(DeploymentCapConfiguration.MAX_ACTIVE_DEPLOYMENTS_VARIABLE, "0"));

        assertEquals(0, configuration.maxActiveDeployments());
    }

    @Test
    void negativeIsNotACapAndIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DeploymentCapConfiguration(-1));
        assertThrows(IllegalArgumentException.class, () -> DeploymentCapConfiguration.fromEnvironment(
                Map.of(DeploymentCapConfiguration.MAX_ACTIVE_DEPLOYMENTS_VARIABLE, "-1")));
    }

    @Test
    void aNonNumericValueNamesTheOffendingVariableInTheFailure() {
        var thrown = assertThrows(IllegalArgumentException.class, () -> DeploymentCapConfiguration.fromEnvironment(
                Map.of(DeploymentCapConfiguration.MAX_ACTIVE_DEPLOYMENTS_VARIABLE, "not-a-number")));

        assertEquals(true, thrown.getMessage().contains(DeploymentCapConfiguration.MAX_ACTIVE_DEPLOYMENTS_VARIABLE),
                () -> "variable name must be named in: " + thrown.getMessage());
    }
}
