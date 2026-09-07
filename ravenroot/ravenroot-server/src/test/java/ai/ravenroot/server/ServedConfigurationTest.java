package ai.ravenroot.server;

import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.core.graph.GraphMlLimits;
import com.nimbusds.jose.shaded.gson.JsonObject;
import com.nimbusds.jose.shaded.gson.JsonParser;
import com.nimbusds.jose.shaded.gson.Strictness;
import com.nimbusds.jose.shaded.gson.stream.JsonReader;
import com.nimbusds.jose.shaded.gson.stream.JsonToken;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServedConfigurationTest {
    private static final String ESCAPED_TENANT = "tenant-\"\\\n\u0001";

    @Test
    void emitsThePlainVariantAsExactlyOneCompleteJsonObject() throws IOException {
        assertConfiguration(configuration().json(), null, null);
    }

    @Test
    void emitsTheTenantVariantAsExactlyOneCompleteJsonObject() throws IOException {
        assertConfiguration(configuration().json(ESCAPED_TENANT), ESCAPED_TENANT, null);
    }

    @Test
    void emitsTheHumanTaskPolicyVariantAsExactlyOneCompleteJsonObject() throws IOException {
        var policy = HumanTaskPolicy.DEFAULTS;
        assertConfiguration(configuration().json(policy), null, policy);
    }

    @Test
    void emitsTheHumanTaskPolicyAndTenantVariantAsExactlyOneCompleteJsonObject() throws IOException {
        var policy = HumanTaskPolicy.DEFAULTS;
        assertConfiguration(configuration().json(policy, ESCAPED_TENANT), ESCAPED_TENANT, policy);
    }

    private static ServedConfiguration configuration() {
        var defaults = GraphMlLimits.DEFAULTS;
        var graphMl = new GraphMlLimits(32 * 1024 * 1024, defaults.maxNodes(), defaults.maxEdges(),
                defaults.maxProperties(), defaults.maxDepth(), defaults.maxStringLength(), defaults.maxKeys(),
                defaults.maxElements(), defaults.maxAttributes(), defaults.maxNamespaceDeclarations());
        return ServedConfiguration.from(graphMl);
    }

    @Test
    void refusesUnknownSchemasAndBoundsOutsideTheSharedSafetyContract() {
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ServedConfiguration(
                1, GraphDefinitionStore.HARD_MAX_DEFINITION_BYTES + 1));
    }

    private static void assertConfiguration(String encoded, String tenant, HumanTaskPolicy policy)
            throws IOException {
        JsonObject configuration = parseExactlyOneObject(encoded);
        var expectedKeys = new HashSet<>(Set.of("schemaVersion", "graphDocumentMaxBytes"));
        if (tenant != null) expectedKeys.add("workspace");
        if (policy != null) expectedKeys.add("humanTasks");
        assertEquals(expectedKeys, configuration.keySet());
        assertEquals(1, configuration.get("schemaVersion").getAsInt());
        assertEquals(32 * 1024 * 1024, configuration.get("graphDocumentMaxBytes").getAsInt());

        if (tenant == null) {
            assertFalse(configuration.has("workspace"));
        } else {
            JsonObject workspace = configuration.getAsJsonObject("workspace");
            assertEquals(Set.of("tenantId"), workspace.keySet());
            assertEquals(tenant, workspace.get("tenantId").getAsString());
        }

        if (policy == null) {
            assertFalse(configuration.has("humanTasks"));
        } else {
            JsonObject humanTasks = configuration.getAsJsonObject("humanTasks");
            assertEquals(Set.of("schemaVersion", "confirmationPresentationVersions",
                    "confirmationPromptMaxUtf8Bytes", "confirmationActionLabelMaxUtf8Bytes",
                    "commentMaxUtf8Bytes", "attentionPollMillis", "attentionBackoffMaxMillis",
                    "attentionPageSize", "attentionPageSizeMax"), humanTasks.keySet());
            assertEquals(1, humanTasks.get("schemaVersion").getAsInt());
            assertEquals(1, humanTasks.getAsJsonArray("confirmationPresentationVersions").size());
            assertEquals(1, humanTasks.getAsJsonArray("confirmationPresentationVersions").get(0).getAsInt());
            var confirmation = policy.confirmation();
            assertEquals(confirmation.maxPromptUtf8Bytes(),
                    humanTasks.get("confirmationPromptMaxUtf8Bytes").getAsInt());
            assertEquals(confirmation.maxActionLabelUtf8Bytes(),
                    humanTasks.get("confirmationActionLabelMaxUtf8Bytes").getAsInt());
            assertEquals(confirmation.maxCommentUtf8Bytes(), humanTasks.get("commentMaxUtf8Bytes").getAsInt());
            assertEquals(confirmation.pollAfterMillis(), humanTasks.get("attentionPollMillis").getAsInt());
            assertEquals(confirmation.pollBackoffMaxMillis(),
                    humanTasks.get("attentionBackoffMaxMillis").getAsInt());
            assertEquals(confirmation.attentionDefaultPageSize(),
                    humanTasks.get("attentionPageSize").getAsInt());
            assertEquals(confirmation.attentionMaxPageSize(),
                    humanTasks.get("attentionPageSizeMax").getAsInt());
        }
    }

    private static JsonObject parseExactlyOneObject(String encoded) throws IOException {
        try (var reader = new JsonReader(new StringReader(encoded))) {
            reader.setStrictness(Strictness.STRICT);
            var value = JsonParser.parseReader(reader);
            assertEquals(JsonToken.END_DOCUMENT, reader.peek(), "configuration has trailing JSON content");
            assertTrue(value.isJsonObject(), "configuration must be one JSON object");
            return value.getAsJsonObject();
        }
    }
}
