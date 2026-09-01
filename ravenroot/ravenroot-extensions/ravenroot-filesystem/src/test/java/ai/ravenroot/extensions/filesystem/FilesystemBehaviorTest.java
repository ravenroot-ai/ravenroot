package ai.ravenroot.extensions.filesystem;

import ai.ravenroot.api.catalog.RecoveryRepeatabilityProperty;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.execution.NodeResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemBehaviorTest {
    @TempDir Path root;

    @Test void unknownProfileAuthorityAndInvalidPayloadFailThroughTypedStage() {
        var missing = new FilesystemReadNodeBehavior(new FilesystemRuntime((tenant, name) -> Optional.empty()));
        assertFailure(missing.create(FilesystemTestSupport.configuration(FilesystemReadNodeBehavior.BEHAVIOR, Map.of()))
                        .handle(FilesystemTestSupport.message(Map.of("version", "filesystem.read.v1"))),
                FilesystemNodeException.Reason.PROFILE_UNAVAILABLE);

        FilesystemProfile writeOnly = new FilesystemProfile(FilesystemTestSupport.PROFILE, root.toAbsolutePath(),
                false, true, java.util.Set.of("**"), 100, 1, java.time.Duration.ofSeconds(1));
        var refused = new FilesystemReadNodeBehavior(new FilesystemRuntime((tenant, name) -> Optional.of(writeOnly)));
        assertFailure(refused.create(FilesystemTestSupport.configuration(FilesystemReadNodeBehavior.BEHAVIOR, Map.of()))
                        .handle(FilesystemTestSupport.message(Map.of("version", "filesystem.read.v1"))),
                FilesystemNodeException.Reason.AUTHORITY_REFUSED);

        var invalid = new FilesystemWriteNodeBehavior(new FilesystemRuntime((tenant, name) ->
                Optional.of(FilesystemTestSupport.profile(root))));
        assertFailure(invalid.create(FilesystemTestSupport.configuration(FilesystemWriteNodeBehavior.BEHAVIOR, Map.of()))
                        .handle(FilesystemTestSupport.message(Map.of("version", "filesystem.write.v1", "text", "x",
                                "unknown", true))), FilesystemNodeException.Reason.INVALID_INPUT);
    }

    @Test void behaviorRoundTripHasVersionBytesDigestAndNoPath() throws Exception {
        try (DirectoryStream<Path> directory = Files.newDirectoryStream(root)) {
            Assumptions.assumeTrue(directory instanceof SecureDirectoryStream<?>);
        }
        Files.createDirectory(root.resolve("folder"));
        var runtime = new FilesystemRuntime((tenant, name) -> Optional.of(FilesystemTestSupport.profile(root)));
        NodeAction write = new FilesystemWriteNodeBehavior(runtime).create(
                FilesystemTestSupport.configuration(FilesystemWriteNodeBehavior.BEHAVIOR, Map.of()));
        @SuppressWarnings("unchecked") Map<String, Object> writeResult = (Map<String, Object>) write.handle(
                FilesystemTestSupport.message(Map.of("version", "filesystem.write.v1", "text", "hello")))
                .toCompletableFuture().join().payload();
        assertEquals("filesystem.write.result.v1", writeResult.get("version"));
        assertEquals("CREATED", writeResult.get("status"));
        assertEquals(5L, writeResult.get("bytes"));
        assertTrue(writeResult.values().stream().noneMatch(value -> value.toString().contains(root.toString())));

        NodeAction read = new FilesystemReadNodeBehavior(runtime).create(
                FilesystemTestSupport.configuration(FilesystemReadNodeBehavior.BEHAVIOR, Map.of()));
        @SuppressWarnings("unchecked") Map<String, Object> readResult = (Map<String, Object>) read.handle(
                FilesystemTestSupport.message(Map.of("version", "filesystem.read.v1")))
                .toCompletableFuture().join().payload();
        assertEquals("hello", readResult.get("text"));
        assertEquals(writeResult.get("sha256"), readResult.get("sha256"));
    }

    @Test void writeDescriptorDeclaresRecoveryRepeatabilityWithoutDefault() {
        var descriptor = new FilesystemWriteNodeBehavior().descriptor();
        var property = descriptor.properties().stream()
                .filter(item -> item.name().equals(RecoveryRepeatabilityProperty.NAME)).findFirst().orElseThrow();
        assertEquals("", property.defaultValue());
    }

    @Test void totalDeadlineIncludesProfileResolutionAndInputPreparation() {
        var runtime = new FilesystemRuntime((tenant, name) -> {
            try { Thread.sleep(25); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return Optional.of(new FilesystemProfile(FilesystemTestSupport.PROFILE, root.toAbsolutePath(),
                    true, false, java.util.Set.of("**"), 100, 1, java.time.Duration.ofMillis(1)));
        });
        var behavior = new FilesystemReadNodeBehavior(runtime);
        assertFailure(behavior.create(FilesystemTestSupport.configuration(
                        FilesystemReadNodeBehavior.BEHAVIOR, Map.of()))
                        .handle(FilesystemTestSupport.message(Map.of("version", "filesystem.read.v1"))),
                FilesystemNodeException.Reason.TIMEOUT);
    }

    @Test void concurrencyCeilingIsTenantAndProfileScoped() throws Exception {
        FilesystemProfile profile = new FilesystemProfile(FilesystemTestSupport.PROFILE, root.toAbsolutePath(),
                true, false, java.util.Set.of("**"), 100, 1, java.time.Duration.ofSeconds(2));
        var runtime = new FilesystemRuntime((tenant, name) -> Optional.of(profile));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = runtime.execute("tenant-a", profile, java.time.Duration.ofSeconds(2), () -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.TIMEOUT);
            }
            return NodeResult.continueWith(Map.of());
        }, new FilesystemAccess.InvocationState());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            assertFailure(runtime.execute("tenant-a", profile, java.time.Duration.ofSeconds(2),
                            () -> NodeResult.continueWith(Map.of()), new FilesystemAccess.InvocationState()),
                    FilesystemNodeException.Reason.SATURATED);
            runtime.execute("tenant-b", profile, java.time.Duration.ofSeconds(2),
                    () -> NodeResult.continueWith(Map.of()), new FilesystemAccess.InvocationState()).join();
        } finally {
            release.countDown();
        }
        first.join();
    }

    private static void assertFailure(java.util.concurrent.CompletionStage<?> stage,
                                      FilesystemNodeException.Reason reason) {
        CompletionException failure = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> stage.toCompletableFuture().join());
        FilesystemNodeException typed = assertInstanceOf(FilesystemNodeException.class, failure.getCause());
        assertEquals(reason, typed.reason());
        assertEquals(reason.name(), typed.getMessage());
    }
}
