package ai.ravenroot.api.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionManifestDigestTest {

    @Test
    void literalV1DigestRemainsByteExactAndV2UsesADistinctOuterDomain() {
        assertEquals("efe6d75f452c74c5308023155b9129c4fd6e832556fd364b55bd4c2c01f8a06c",
                manifest(ExecutionManifest.LEGACY_FORMAT_VERSION).digest().value());
        assertEquals("668be4b578716913dede72cf87b68a2934ec4f0d623aa81c16ae32fd1c863990",
                manifest(ExecutionManifest.CURRENT_FORMAT_VERSION).digest().value());
        assertNotEquals(manifest(ExecutionManifest.LEGACY_FORMAT_VERSION).digest(),
                manifest(ExecutionManifest.CURRENT_FORMAT_VERSION).digest());
    }

    @Test
    void aV1ReaderCannotValidateAV2RecordWithTheOldOuterDomain() {
        ExecutionManifest v2 = manifest(ExecutionManifest.FORMAT_VERSION_2);
        String oldReaderDigest = ExecutionManifestDigest.component(
                "ravenroot.execution-manifest.v1", List.of(
                        Integer.toString(v2.formatVersion()),
                        v2.executionKey().tenantId(),
                        v2.executionKey().processInstanceId().toString(),
                        v2.graphContentId().value(),
                        v2.graphIdentity().kind().token(),
                        v2.graphIdentity().identityDigest(),
                        Integer.toString(v2.runtime().engineProtocolVersion()),
                        Integer.toString(v2.runtime().graphMlCanonicalizationVersion()),
                        v2.runtime().executionPolicy(),
                        v2.runtime().unknownBehaviorPolicy(),
                        v2.runtime().engineDigest(),
                        v2.runtime().storeDigest(),
                        v2.runtime().executionLimitsDigest(),
                        v2.runtime().programRuntimeDigest(),
                        Integer.toString(v2.nodePackages().size()),
                        v2.nodePackages().getFirst().packageId(),
                        v2.nodePackages().getFirst().identityDigest()));

        assertEquals("d1759de1e0e94fb5462800fc606bc06bda533c295b155a94c218772366674cc6",
                oldReaderDigest);
        assertNotEquals(oldReaderDigest, v2.digest().value());
    }

    @Test
    void componentGenerationsAreDomainSeparated() {
        List<String> sameBytes = List.of("same", "component", "bytes");
        assertNotEquals(ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.ENGINE_DOMAIN, sameBytes),
                ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.ENGINE_DOMAIN_V2, sameBytes));
        assertNotEquals(ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.STORE_DOMAIN, sameBytes),
                ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.STORE_DOMAIN_V2, sameBytes));
        assertNotEquals(ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.LIMITS_DOMAIN, sameBytes),
                ResolvedRuntimeProfile.digestOf(ResolvedRuntimeProfile.LIMITS_DOMAIN_V2, sameBytes));
        assertNotEquals(ResolvedRuntimeProfile.digestOf(
                        ResolvedRuntimeProfile.PROGRAM_RUNTIME_DOMAIN, sameBytes),
                ResolvedRuntimeProfile.digestOf(
                        ResolvedRuntimeProfile.PROGRAM_RUNTIME_DOMAIN_V2, sameBytes));
    }

    @Test
    void anUnknownFormatHasNoImplicitDigestAlgorithm() {
        assertThrows(IllegalArgumentException.class, () -> manifest(3).digest());
    }

    private static ExecutionManifest manifest(int formatVersion) {
        var key = new ExecutionKey("acme",
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var content = new GraphContentId("a".repeat(64));
        var runtime = new ResolvedRuntimeProfile(1, 1, "STANDARD", "pass-through",
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64));
        return new ExecutionManifest(formatVersion, key, content,
                GraphDefinitionIdentity.forSubmission(content), runtime,
                List.of(new PinnedNodePackage("alpha.nodes", "5".repeat(64))),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
