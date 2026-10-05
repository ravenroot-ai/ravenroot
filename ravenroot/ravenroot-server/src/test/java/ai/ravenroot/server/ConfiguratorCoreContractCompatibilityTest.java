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
        for (int index = 0; index < negativeEnvironmentCount(fixtures, "bundle.service-grant"); index++) {
            int vector = index;
            assertThrows(RuntimeException.class, () -> EnvironmentNodePackageServiceGrants.fromEnvironment(
                    negativeEnvironment(fixtures, "bundle.service-grant", vector),
                    (packageId, tenantId, reference) -> Optional.empty()),
                    () -> negativeEnvironmentLabel(fixtures, "bundle.service-grant", vector));
        }

        Map<String, String> http = environment(fixtures, "core.http");
        assertNotNull(OutboundHttpPolicy.fromCommaSeparated(http.get("RAVENROOT_HTTP_ALLOWED_HOSTS"),
                http.get("RAVENROOT_HTTP_ALLOWED_PORTS"), Long.parseLong(http.get("RAVENROOT_HTTP_MAX_RESPONSE_BYTES")),
                Long.parseLong(http.get("RAVENROOT_HTTP_MAX_REQUEST_BYTES"))));
        assertNotNull(ReservedNetworkPolicy.fromEnvironment(http));
        assertNotNull(AllowlistToolPolicy.fromCommaSeparated(http.get("RAVENROOT_ALLOWED_TOOLS")));
        assertThrows(IllegalArgumentException.class, () -> OutboundHttpPolicy.fromCommaSeparated(
                http.get("RAVENROOT_HTTP_ALLOWED_HOSTS"), invalidEnvironment(fixtures, "core.http").get("RAVENROOT_HTTP_ALLOWED_PORTS"),
                1024, 1024));
        for (int index = 0; index < negativeEnvironmentCount(fixtures, "core.http"); index++) {
            int vector = index; Map<String, String> invalid = negativeEnvironment(fixtures, "core.http", vector);
            assertThrows(RuntimeException.class, () -> {
                OutboundHttpPolicy.fromCommaSeparated(invalid.get("RAVENROOT_HTTP_ALLOWED_HOSTS"),
                        invalid.get("RAVENROOT_HTTP_ALLOWED_PORTS"),
                        RavenrootServerMain.byteCeiling(invalid, "RAVENROOT_HTTP_MAX_RESPONSE_BYTES"),
                        RavenrootServerMain.byteCeiling(invalid, "RAVENROOT_HTTP_MAX_REQUEST_BYTES"));
                ReservedNetworkPolicy.fromEnvironment(invalid);
                AllowlistToolPolicy.fromCommaSeparated(invalid.get("RAVENROOT_ALLOWED_TOOLS"));
            }, () -> negativeEnvironmentLabel(fixtures, "core.http", vector));
        }

        Map<String, String> program = environment(fixtures, "core.program");
        assertEquals(ProgramRuntimeConfiguration.Runtime.GRAALVM,
                ProgramRuntimeConfiguration.resolve(new Properties(), program).runtime());
        assertNotNull(GraalVmRuntimeConfiguration.resolve(new Properties(), program));
        assertNotNull(AllowlistToolPolicy.fromCommaSeparated(program.get("RAVENROOT_ALLOWED_TOOLS")));
        assertThrows(IllegalArgumentException.class, () -> ProgramRuntimeConfiguration.resolve(
                new Properties(), invalidEnvironment(fixtures, "core.program")));
        for (int index = 0; index < negativeEnvironmentCount(fixtures, "core.program"); index++) {
            int vector = index; Map<String, String> invalid = negativeEnvironment(fixtures, "core.program", vector);
            assertThrows(RuntimeException.class, () -> {
                ProgramRuntimeConfiguration.resolve(new Properties(), invalid);
                GraalVmRuntimeConfiguration.resolve(new Properties(), invalid);
                AllowlistToolPolicy.fromCommaSeparated(invalid.get("RAVENROOT_ALLOWED_TOOLS"));
            }, () -> negativeEnvironmentLabel(fixtures, "core.program", vector));
        }

        Path human = document(fixtures, "core.human-task", "document", 0);
        Map<String, String> humanEnvironment = new LinkedHashMap<>(environment(fixtures, "core.human-task"));
        humanEnvironment.put(HumanTaskInteractionConfiguration.CONFIG_VARIABLE, human.toString());
        assertFalse(HumanTaskInteractionConfiguration.fromEnvironment(humanEnvironment).profiles().isEmpty());
        assertNotNull(HumanTaskInteractionConfiguration.fromEnvironment(Map.of(
                HumanTaskInteractionConfiguration.CONFIG_VARIABLE, document(fixtures, "core.human-task", "alternateDocument", 0).toString())));
        for (int index = 0; index < negativeCount(fixtures, "core.human-task"); index++) {
            int vector = index;
            assertThrows(RuntimeException.class, () -> HumanTaskInteractionConfiguration.fromEnvironment(
                    Map.of(HumanTaskInteractionConfiguration.CONFIG_VARIABLE,
                            document(fixtures, "core.human-task", "negativeDocument." + vector + ".value", vector).toString())),
                    () -> negativeLabel(fixtures, "core.human-task", vector));
        }

        Path publication = document(fixtures, "core.publication-policies", "document", 0);
        assertNotNull(PublicationPolicyConfiguration.fromEnvironment(Map.of(
                PublicationPolicyConfiguration.CONFIG_VARIABLE, publication.toString())));
        for (int index = 0; index < negativeCount(fixtures, "core.publication-policies"); index++) {
            int vector = index;
            assertThrows(RuntimeException.class, () -> PublicationPolicyConfiguration.fromEnvironment(Map.of(
                    PublicationPolicyConfiguration.CONFIG_VARIABLE,
                    document(fixtures, "core.publication-policies", "negativeDocument." + vector + ".value", vector).toString())),
                    () -> negativeLabel(fixtures, "core.publication-policies", vector));
        }

        Path runner = document(fixtures, "core.runner", "document", 0);
        assertNotNull(RunnerPlaneConfiguration.fromEnvironment(Map.of("RAVENROOT_RUNNER_CONFIG", runner.toString())));
        assertNotNull(RunnerPlaneConfiguration.fromEnvironment(Map.of("RAVENROOT_RUNNER_CONFIG",
                document(fixtures, "core.runner", "alternateDocument", 0).toString())));
        for (int index = 0; index < positiveCount(fixtures, "core.runner"); index++) {
            int vector = index;
            assertNotNull(RunnerPlaneConfiguration.fromEnvironment(Map.of(
                    "RAVENROOT_RUNNER_CONFIG",
                    document(fixtures, "core.runner", "positiveDocument." + vector + ".value", vector).toString())),
                    () -> positiveLabel(fixtures, "core.runner", vector));
        }
        for (int index = 0; index < negativeCount(fixtures, "core.runner"); index++) {
            int vector = index;
            assertThrows(RuntimeException.class, () -> RunnerPlaneConfiguration.fromEnvironment(Map.of(
                    "RAVENROOT_RUNNER_CONFIG",
                    document(fixtures, "core.runner", "negativeDocument." + vector + ".value", vector).toString())),
                    () -> negativeLabel(fixtures, "core.runner", vector));
        }
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

    private static Map<String, String> negativeEnvironment(Properties fixtures, String id, int index) {
        String prefix = "core." + id + ".negativeEnvironment." + index;
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : fixtures.getProperty(prefix + ".keys").split(",")) {
            result.put(key, fixtures.getProperty(prefix + ".env." + key));
        }
        return result;
    }

    private static int negativeEnvironmentCount(Properties fixtures, String id) {
        return Integer.parseInt(fixtures.getProperty("core." + id + ".negativeEnvironmentCount"));
    }

    private static String negativeEnvironmentLabel(Properties fixtures, String id, int index) {
        return fixtures.getProperty("core." + id + ".negativeEnvironment." + index + ".label");
    }

    private static int negativeCount(Properties fixtures, String id) {
        return Integer.parseInt(fixtures.getProperty("core." + id + ".negativeDocumentCount"));
    }

    private static int positiveCount(Properties fixtures, String id) {
        return Integer.parseInt(fixtures.getProperty("core." + id + ".positiveDocumentCount"));
    }

    private static String positiveLabel(Properties fixtures, String id, int index) {
        return fixtures.getProperty("core." + id + ".positiveDocument." + index + ".label");
    }

    private static String negativeLabel(Properties fixtures, String id, int index) {
        return fixtures.getProperty("core." + id + ".negativeDocument." + index + ".label");
    }

    private Path document(Properties fixtures, String id, String suffix, int index) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(fixtures.getProperty("core." + id + "." + suffix));
        Path path = directory.resolve(id.replace('.', '-') + "-" + suffix.replace('.', '-') + "-" + index + ".json");
        Files.write(path, bytes);
        return path;
    }
}
