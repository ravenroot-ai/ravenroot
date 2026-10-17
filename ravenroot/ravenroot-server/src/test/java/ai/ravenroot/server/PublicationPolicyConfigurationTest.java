package ai.ravenroot.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicationPolicyConfigurationTest {
    @TempDir Path temporary;

    @Test
    void loadsAClosedVersionedRegistryForThePackagedServer() throws Exception {
        Path file = temporary.resolve("policies.json");
        Files.writeString(file, """
                {"schemaVersion":1,"policies":[{"id":"public","version":"v1","maxCandidateBytes":4096,
                "rules":[
                  {"type":"destination","id":"destination.approved","allowedTypes":["repository"],"allowedAddresses":["public"]},
                  {"type":"logical-path","id":"path.private","privatePrefixes":["private/"],"denyAbsolute":true,"denyParentTraversal":true,"denyHomeRelative":true},
                  {"type":"language","id":"language.approved","allowedLanguages":["en"],"allowSubtags":true},
                  {"type":"artifact-type","id":"artifact.approved","allowedTypes":["document"],"allowBinary":false},
                  {"type":"required-file-pair","id":"pair.metadata","firstSuffix":".bin","requiredSuffix":".json"},
                  {"type":"provenance","id":"provenance.complete","allowedSourceTypes":["graph"]}
                ]}]}""");

        var policy = PublicationPolicyConfiguration.fromEnvironment(
                Map.of(PublicationPolicyConfiguration.CONFIG_VARIABLE, file.toString())).resolve("public", "v1").orElseThrow();

        assertEquals(6, policy.rules().size());
        assertTrue(policy.reference().digest().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void absentConfigurationRetainsTheFailClosedEmptyResolver() {
        assertTrue(PublicationPolicyConfiguration.fromEnvironment(Map.of()).resolve("public", "v1").isEmpty());
    }

    @Test
    void rejectsUnknownFieldsBeforePoliciesReachTheCatalog() throws Exception {
        Path file = temporary.resolve("invalid.json");
        Files.writeString(file, """
                {"schemaVersion":1,"policies":[],"unexpected":true}""");

        assertThrows(IllegalArgumentException.class, () -> PublicationPolicyConfiguration.fromEnvironment(
                Map.of(PublicationPolicyConfiguration.CONFIG_VARIABLE, file.toString())));
    }
}
