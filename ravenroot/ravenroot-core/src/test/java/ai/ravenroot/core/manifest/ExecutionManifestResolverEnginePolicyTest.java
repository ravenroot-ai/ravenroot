package ai.ravenroot.core.manifest;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.execution.ExecutionEngine;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.ExecutionManifestDifference;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionIdentity;
import ai.ravenroot.core.persistence.InMemoryExecutionManifestStore;
import ai.ravenroot.core.runtime.BehaviorEnvironment;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.GraphExecutionLimits;
import ai.ravenroot.core.runtime.UnknownBehaviorPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionManifestResolverEnginePolicyTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final GraphContentId CONTENT =
            GraphContentId.of("engine policy manifest".getBytes(StandardCharsets.UTF_8));
    private static final String INVALID_MESSAGE =
            "Execution engine compatibility fingerprint must be empty or lowercase SHA-256 hexadecimal";

    @Test
    void legacyAdapterDigestsStayByteForByteStableAndFingerprintIsReadOnce() {
        var pekkoReads = new AtomicInteger();
        var akkaReads = new AtomicInteger();

        assertEquals("8a3d10e1ca385cefe21a8b84f98a79dd57d19de98c056dfc0612bc034435900d",
                manifest(resolver(engine("apache-pekko", "", pekkoReads))).runtime().engineDigest());
        assertEquals("7d5f032bb9cf3067e2efc38b4819555f5c6d70a87a0bd86938d0e0cf6893d7f6",
                manifest(resolver(engine("akka", "", akkaReads))).runtime().engineDigest());
        assertEquals(1, pekkoReads.get());
        assertEquals(1, akkaReads.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"
    })
    void malformedNonemptyFingerprintsAreRejectedWithoutEchoOrCause(String fingerprint) {
        var reads = new AtomicInteger();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> resolver(engine("adapter", fingerprint, reads)));

        assertEquals(INVALID_MESSAGE, failure.getMessage());
        assertFalse(failure.getMessage().contains(fingerprint));
        assertNull(failure.getCause());
        assertEquals(1, reads.get());
    }

    @Test
    void missingFingerprintIsRejectedThroughTheSameClosedDiagnostic() {
        var reads = new AtomicInteger();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> resolver(engine("adapter", null, reads)));
        assertEquals(INVALID_MESSAGE, failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, reads.get());
    }

    @Test
    void changedFingerprintRefusesAsEngineAndLeavesTheStoredManifestUntouched() {
        var key = new ExecutionKey("tenant-a", UUID.randomUUID());
        var store = new InMemoryExecutionManifestStore(CLOCK);
        var acceptedReads = new AtomicInteger();
        var changedReads = new AtomicInteger();
        var accepting = new ExecutionManifestService(
                store, resolver(engine("adapter", "a".repeat(64), acceptedReads)), CLOCK);
        var accepted = accepting.pin(key, CONTENT, GraphDefinitionIdentity.forSubmission(CONTENT),
                ExecutionPolicy.STANDARD);

        var same = new ExecutionManifestService(
                store, resolver(engine("adapter", "a".repeat(64), new AtomicInteger())), CLOCK);
        assertEquals(accepted, same.verify(key, ExecutionPolicy.STANDARD));

        var changed = new ExecutionManifestService(
                store, resolver(engine("adapter", "b".repeat(64), changedReads)), CLOCK);
        ExecutionManifestIncompatibleException refusal = assertThrows(
                ExecutionManifestIncompatibleException.class,
                () -> changed.verify(key, ExecutionPolicy.STANDARD));
        assertEquals(List.of(ExecutionManifestDifference.Dimension.ENGINE), refusal.report().dimensions());
        assertEquals(accepted, store.load(key).toCompletableFuture().join());
        assertEquals(1, acceptedReads.get());
        assertEquals(1, changedReads.get());
    }

    private static ai.ravenroot.api.persistence.ExecutionManifest manifest(ExecutionManifestResolver resolver) {
        return resolver.manifestFor(new ExecutionKey("tenant-a", UUID.randomUUID()), CONTENT,
                GraphDefinitionIdentity.forSubmission(CONTENT), ExecutionPolicy.STANDARD, CLOCK.instant());
    }

    private static ExecutionManifestResolver resolver(ExecutionEngine engine) {
        return ExecutionManifestResolver.from(engine, Set.of(),
                BehaviorRegistry.standard(BehaviorEnvironment.safeDefaults()),
                UnknownBehaviorPolicy.passThrough(), GraphExecutionLimits.DEFAULTS, null);
    }

    private static ExecutionEngine engine(String id, String fingerprint, AtomicInteger reads) {
        return (ExecutionEngine) Proxy.newProxyInstance(
                ExecutionEngine.class.getClassLoader(), new Class<?>[] {ExecutionEngine.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> id;
                    case "capabilities" -> Set.of();
                    case "compatibilityFingerprint" -> {
                        reads.incrementAndGet();
                        yield fingerprint;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
