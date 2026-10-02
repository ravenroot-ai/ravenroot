package ai.ravenroot.server;

import ai.ravenroot.api.security.egress.ReservedNetworkPolicy;
import ai.ravenroot.core.security.AllowlistToolPolicy;
import ai.ravenroot.core.security.OutboundHttpPolicy;
import ai.ravenroot.programming.graalvm.GraalVmRuntimeConfiguration;
import ai.ravenroot.server.humantaskinteraction.HumanTaskInteractionConfiguration;
import ai.ravenroot.server.plugin.EnvironmentNodePackageServiceGrants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Runtime probe invoked only by the standalone configurator workflow with generated fixtures. */
final class ConfiguratorCoreContractCompatibilityTest {
    @TempDir Path directory;

    @Test void exactConfiguratorValuesCrossEveryCoreRuntimeLoader() throws Exception {
        Properties fixtures = fixtures();
        org.junit.jupiter.api.Assumptions.assumeTrue(fixtures != null,
                "standalone configurator workflow did not provide generated fixtures");

        Map<String, String> grant = environment(fixtures, "bundle.service-grant");
        assertFalse(EnvironmentNodePackageServiceGrants.fromEnvironment(grant,
                (packageId, tenantId, reference) -> Optional.empty())
                .capabilitiesFor("default").isEmpty());
        assertThrows(RuntimeException.class, () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                invalidEnvironment(fixtures, "bundle.service-grant"), (packageId, tenantId, reference) -> Optional.empty()));

        Map<String, String> http = environment(fixtures, "core.http");
        assertNotNull(OutboundHttpPolicy.fromCommaSeparated(http.get("RAVENROOT_HTTP_ALLOWED_HOSTS"),
                http.get("RAVENROOT_HTTP_ALLOWED_PORTS"), Long.parseLong(http.get("RAVENROOT_HTTP_MAX_RESPONSE_BYTES")),
                Long.parseLong(http.get("RAVENROOT_HTTP_MAX_REQUEST_BYTES"))));
        assertNotNull(ReservedNetworkPolicy.fromEnvironment(http));
        assertNotNull(AllowlistToolPolicy.fromCommaSeparated(http.get("RAVENROOT_ALLOWED_TOOLS")));
        assertThrows(IllegalArgumentException.class, () -> OutboundHttpPolicy.fromCommaSeparated(
                http.get("RAVENROOT_HTTP_ALLOWED_HOSTS"), invalidEnvironment(fixtures, "core.http").get("RAVENROOT_HTTP_ALLOWED_PORTS"),
                1024, 1024));

        Map<String, String> program = environment(fixtures, "core.program");
        assertEquals(ProgramRuntimeConfiguration.Runtime.GRAALVM,
                ProgramRuntimeConfiguration.resolve(new Properties(), program).runtime());
        assertNotNull(GraalVmRuntimeConfiguration.resolve(new Properties(), program));
        assertNotNull(AllowlistToolPolicy.fromCommaSeparated(program.get("RAVENROOT_ALLOWED_TOOLS")));
        assertThrows(IllegalArgumentException.class, () -> ProgramRuntimeConfiguration.resolve(
                new Properties(), invalidEnvironment(fixtures, "core.program")));

        Path human = document(fixtures, "core.human-task", false);
        Map<String, String> humanEnvironment = new LinkedHashMap<>(environment(fixtures, "core.human-task"));
        humanEnvironment.put(HumanTaskInteractionConfiguration.CONFIG_VARIABLE, human.toString());
        assertFalse(HumanTaskInteractionConfiguration.fromEnvironment(humanEnvironment).profiles().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> HumanTaskInteractionConfiguration.fromEnvironment(
                Map.of(HumanTaskInteractionConfiguration.CONFIG_VARIABLE, document(fixtures, "core.human-task", true).toString())));

        Path publication = document(fixtures, "core.publication-policies", false);
        assertNotNull(PublicationPolicyConfiguration.fromEnvironment(Map.of(
                PublicationPolicyConfiguration.CONFIG_VARIABLE, publication.toString())));
        assertThrows(IllegalArgumentException.class, () -> PublicationPolicyConfiguration.fromEnvironment(Map.of(
                PublicationPolicyConfiguration.CONFIG_VARIABLE, document(fixtures, "core.publication-policies", true).toString())));

        Path runner = document(fixtures, "core.runner", false);
        assertNotNull(RunnerPlaneConfiguration.fromEnvironment(Map.of("RAVENROOT_RUNNER_CONFIG", runner.toString())));
        assertThrows(IllegalArgumentException.class, () -> RunnerPlaneConfiguration.fromEnvironment(Map.of(
                "RAVENROOT_RUNNER_CONFIG", document(fixtures, "core.runner", true).toString())));
    }

    private Properties fixtures() throws Exception {
        String configured = System.getProperty("ravenroot.configurator.fixtures", "");
        if (configured.isBlank()) return null;
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(configured))) { result.load(input); }
        return result;
    }

    private static Map<String, String> environment(Properties fixtures, String id) {
        Map<String, String> result = new LinkedHashMap<>();
        String keys = fixtures.getProperty("core." + id + ".environmentKeys", "");
        if (!keys.isBlank()) for (String key : keys.split(",")) result.put(key, fixtures.getProperty("core." + id + ".env." + key));
        return result;
    }

    private static Map<String, String> invalidEnvironment(Properties fixtures, String id) {
        return Map.of(fixtures.getProperty("core." + id + ".invalidEnvironmentKey"),
                fixtures.getProperty("core." + id + ".invalidEnvironmentValue"));
    }

    private Path document(Properties fixtures, String id, boolean invalid) throws Exception {
        String suffix = invalid ? "invalidDocument" : "document";
        byte[] bytes = Base64.getDecoder().decode(fixtures.getProperty("core." + id + "." + suffix));
        Path path = directory.resolve(id.replace('.', '-') + (invalid ? "-invalid.json" : ".json"));
        Files.write(path, bytes);
        return path;
    }
}
