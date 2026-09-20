package ai.ravenroot.server.humantaskinteraction;

import ai.ravenroot.api.persistence.HumanTaskPresentationKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HumanTaskInteractionConfigurationTest {
    @TempDir Path directory;

    @Test
    void absenceDisablesTheRegistryAndValidFilePinsSafeProfiles() throws IOException {
        assertEquals(null, HumanTaskInteractionConfiguration.fromEnvironment(Map.of()));
        var configuration = read(config("https://forms.example/presenter", "https://forms.example",
                "CUSTOM", ""));

        assertEquals(Duration.ofMinutes(5), configuration.capabilityTtl());
        assertEquals(65_536, configuration.maxCompletionBytes());
        var profile = configuration.requireProfile("expense", 3, HumanTaskPresentationKind.CUSTOM);
        assertEquals("https://forms.example/presenter", profile.launchUri().toString());
        assertEquals(0, profile.completionSecret().length);
    }

    @Test
    void externalProviderRequiresAnIndependentSigningSecret() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> read(config("https://provider.example/task", "https://provider.example",
                        "EXTERNAL", "")));
        var configuration = read(config("https://provider.example/task", "https://provider.example",
                "EXTERNAL", secret()));
        assertEquals(32, configuration.requireProfile("expense", 3,
                HumanTaskPresentationKind.EXTERNAL).completionSecret().length);
    }

    @Test
    void registryRejectsArbitraryOrMismatchedOriginsAndUnknownFields() throws IOException {
        assertThrows(IllegalArgumentException.class,
                () -> read(config("http://forms.example/presenter", "http://forms.example",
                        "CUSTOM", "")));
        assertThrows(IllegalArgumentException.class,
                () -> read(config("https://forms.example/presenter", "https://other.example",
                        "CUSTOM", "")));
        assertThrows(IllegalArgumentException.class,
                () -> read(config("https://forms.example/presenter", "https://forms.example",
                        "CUSTOM", "").replace("\"profiles\":", "\"unexpected\":true,\"profiles\":")));
    }

    private HumanTaskInteractionConfiguration read(String json) throws IOException {
        Path file = directory.resolve("interactions-" + Math.abs(json.hashCode()) + ".json");
        Files.writeString(file, json);
        return HumanTaskInteractionConfiguration.fromEnvironment(
                Map.of(HumanTaskInteractionConfiguration.CONFIG_VARIABLE, file.toString()));
    }

    private static String config(String launchUri, String origin, String kind, String providerSecret) {
        String provider = providerSecret.isEmpty() ? ""
                : ",\"completionSecretBase64\":\"" + providerSecret + "\"";
        return "{\"schemaVersion\":1,\"capabilityTtlSeconds\":300,"
                + "\"maxCompletionBytes\":65536,\"capabilitySecretBase64\":\"" + secret() + "\","
                + "\"profiles\":[{\"id\":\"expense\",\"version\":3,\"kind\":\"" + kind
                + "\",\"launchUri\":\"" + launchUri + "\",\"origin\":\"" + origin + "\""
                + provider + "}]}";
    }

    private static String secret() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }
}
