package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.node.NodeAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrExtractNodeBehaviorTest {
    @TempDir Path root;

    @Test void successfulInvocationUsesPrivateFilesNotPayloadArgumentsAndCleansTheWorkspace() throws Exception {
        OcrProfile profile = OcrTestSupport.profile(root, 1);
        var factory = new OcrTestSupport.FakeFactory();
        NodeAction action = OcrTestSupport.action((tenant, name) -> Optional.of(profile),
                new OcrRuntimeControls(2), factory, Map.of());

        Map<String, Object> output = OcrTestSupport.output(action,
                OcrTestSupport.message(OcrTestSupport.TENANT, OcrTestSupport.payload(OcrTestSupport.png())));

        assertEquals("EXTRACTED", output.get("status"));
        assertEquals("recognized text", output.get("text"));
        OcrInvocation invocation = factory.invocation.get();
        assertTrue(invocation.inputFile().startsWith(profile.temporaryRoot()));
        assertEquals(profile.languageData(), invocation.languageData());
        assertEquals("eng", invocation.language());
        assertFalse(JdkTesseractProcessFactory.command(invocation).toString().contains("imageBase64"));
        assertFalse(Files.exists(invocation.workingDirectory()));
    }

    @Test void deliveredTenantOwnsProfileResolutionAndCannotUseAnotherTenantProfile() throws Exception {
        OcrProfile profile = OcrTestSupport.profile(root, 1);
        var factory = new OcrTestSupport.FakeFactory();
        NodeAction action = OcrTestSupport.action((tenant, name) -> Optional.of(profile),
                new OcrRuntimeControls(2), factory, Map.of());

        Map<String, Object> output = OcrTestSupport.output(action,
                OcrTestSupport.message("tenant-b", OcrTestSupport.payload(OcrTestSupport.png())));

        assertEquals("REJECTED", output.get("status"));
        assertEquals("PROFILE_UNAVAILABLE", output.get("reason"));
        assertEquals(null, factory.invocation.get());
    }

    @Test void graphCanOnlyTightenProfileAuthorityAndForbiddenLanguageNeverSpawns() throws Exception {
        OcrProfile profile = OcrTestSupport.profile(root, 1);
        var factory = new OcrTestSupport.FakeFactory();
        var runtime = new OcrRuntimeControls(2);
        NodeAction widening = OcrTestSupport.action((tenant, name) -> Optional.of(profile), runtime, factory,
                Map.of("maxInputBytes", profile.maxInputBytes() + 1));
        NodeAction forbidden = OcrTestSupport.action((tenant, name) -> Optional.of(profile), runtime, factory,
                Map.of("language", "fra"));

        assertEquals("LIMIT_WIDENING", OcrTestSupport.output(widening,
                OcrTestSupport.message(OcrTestSupport.TENANT, OcrTestSupport.payload(OcrTestSupport.png()))).get("reason"));
        assertEquals("LANGUAGE_FORBIDDEN", OcrTestSupport.output(forbidden,
                OcrTestSupport.message(OcrTestSupport.TENANT, OcrTestSupport.payload(OcrTestSupport.png()))).get("reason"));
        assertEquals(null, factory.invocation.get());
    }

    @Test void invalidCumulativeTiffWorkIsRefusedBeforeWorkspaceAndProcessStart() throws Exception {
        OcrProfile profile = OcrTestSupport.profile(root, 1);
        var factory = new OcrTestSupport.FakeFactory();
        NodeAction action = OcrTestSupport.action((tenant, name) -> Optional.of(profile),
                new OcrRuntimeControls(2), factory, Map.of());
        byte[] imageBomb = OcrTestSupport.tiffPages(new int[][] {{400, 100}, {10_000, 4_001}});

        Map<String, Object> output = OcrTestSupport.output(action,
                OcrTestSupport.message(OcrTestSupport.TENANT, OcrTestSupport.payload(imageBomb)));

        assertEquals("REJECTED", output.get("status"));
        assertEquals("INVALID_IMAGE", output.get("reason"));
        assertEquals(null, factory.invocation.get());
        try (var children = Files.list(profile.temporaryRoot())) {
            assertEquals(0L, children.count());
        }
    }

    @Test void zeroDirectoryMissingDimensionsAndIncompleteTiffChainsAreRefusedBeforeWorkspaceAndProcessStart() throws Exception {
        OcrProfile profile = OcrTestSupport.profile(root, 1);
        var factory = new OcrTestSupport.FakeFactory();
        NodeAction action = OcrTestSupport.action((tenant, name) -> Optional.of(profile),
                new OcrRuntimeControls(2), factory, Map.of());
        byte[] zeroDirectories = new byte[] {'M', 'M', 0, 42, 0, 0, 0, 0};
        byte[] incompleteChain = OcrTestSupport.tiff();
        java.nio.ByteBuffer.wrap(incompleteChain).order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(34, incompleteChain.length - 1);
        byte[] missingDimensions = OcrTestSupport.tiff();
        java.nio.ByteBuffer.wrap(missingDimensions).order(java.nio.ByteOrder.BIG_ENDIAN).putShort(8, (short) 1);

        for (byte[] invalid : java.util.List.of(zeroDirectories, missingDimensions, incompleteChain)) {
            Map<String, Object> output = OcrTestSupport.output(action,
                    OcrTestSupport.message(OcrTestSupport.TENANT, OcrTestSupport.payload(invalid)));
            assertEquals("REJECTED", output.get("status"));
            assertEquals("INVALID_IMAGE", output.get("reason"));
        }
        assertEquals(null, factory.invocation.get());
        try (var children = Files.list(profile.temporaryRoot())) {
            assertEquals(0L, children.count());
        }
    }

    @Test void admissionRefusesBeforeDecodeWorkspaceAndSpawnThenRecoversAfterDrain() throws Exception {
        OcrProfile profile = OcrTestSupport.profile(root, 1);
        var factory = new OcrTestSupport.FakeFactory();
        var blocking = new OcrTestSupport.FakeProcess(new byte[0], new byte[0],
                OcrTestSupport.FakeProcess.Mode.BLOCK, 0);
        factory.next.set(blocking);
        NodeAction action = OcrTestSupport.action((tenant, name) -> Optional.of(profile),
                new OcrRuntimeControls(4), factory, Map.of());

        CompletableFuture<?> first = action.handle(OcrTestSupport.message(OcrTestSupport.TENANT,
                OcrTestSupport.payload(OcrTestSupport.png()))).toCompletableFuture();
        assertTrue(blocking.entered.await(3, TimeUnit.SECONDS), () -> first.isDone()
                ? "invocation completed before process start: " + first.join()
                : "invocation did not reach process start");
        OcrInvocation firstInvocation = factory.invocation.get();
        Map<String, Object> refused = OcrTestSupport.output(action,
                OcrTestSupport.message(OcrTestSupport.TENANT, Map.of("url", "https://attacker.invalid")));
        assertEquals("LOCAL_CAPACITY", refused.get("reason"));
        assertEquals(firstInvocation, factory.invocation.get());

        blocking.release.countDown();
        first.get(1, TimeUnit.SECONDS);
        Map<String, Object> recovered = OcrTestSupport.output(action,
                OcrTestSupport.message(OcrTestSupport.TENANT, OcrTestSupport.payload(OcrTestSupport.jpeg())));
        assertEquals("EXTRACTED", recovered.get("status"));
        assertNotEquals(firstInvocation.workingDirectory(), factory.invocation.get().workingDirectory());
        assertFalse(Files.exists(firstInvocation.workingDirectory()));
    }

    @Test void profileIsResolvedAgainForEveryInvocationSoRotationIsImmediate() throws Exception {
        Path secondRoot = Files.createDirectory(root.resolve("rotated"));
        OcrProfile first = OcrTestSupport.profile(root, 1);
        OcrProfile second = OcrTestSupport.profile(secondRoot, 1);
        AtomicInteger lookups = new AtomicInteger();
        var factory = new OcrTestSupport.FakeFactory();
        NodeAction action = OcrTestSupport.action((tenant, name) -> Optional.of(
                        lookups.getAndIncrement() == 0 ? first : second),
                new OcrRuntimeControls(2), factory, Map.of());

        OcrTestSupport.output(action, OcrTestSupport.message(OcrTestSupport.TENANT,
                OcrTestSupport.payload(OcrTestSupport.png())));
        Path firstExecutable = factory.invocation.get().executable();
        OcrTestSupport.output(action, OcrTestSupport.message(OcrTestSupport.TENANT,
                OcrTestSupport.payload(OcrTestSupport.png())));

        assertEquals(2, lookups.get());
        assertNotEquals(firstExecutable, factory.invocation.get().executable());
    }

    @Test void cancellationKillsAndCleansBeforeAdmissionBecomesReusable() throws Exception {
        OcrProfile profile = OcrTestSupport.profile(root, 1);
        var factory = new OcrTestSupport.FakeFactory();
        var blocking = new OcrTestSupport.FakeProcess(new byte[0], new byte[0],
                OcrTestSupport.FakeProcess.Mode.BLOCK, 0);
        factory.next.set(blocking);
        NodeAction action = OcrTestSupport.action((tenant, name) -> Optional.of(profile),
                new OcrRuntimeControls(1), factory, Map.of());
        CompletableFuture<?> future = action.handle(OcrTestSupport.message(OcrTestSupport.TENANT,
                OcrTestSupport.payload(OcrTestSupport.png()))).toCompletableFuture();
        assertTrue(blocking.entered.await(3, TimeUnit.SECONDS));
        Path invocationDirectory = factory.invocation.get().workingDirectory();

        assertTrue(future.cancel(true));
        await(() -> !blocking.root.alive && !blocking.child.alive && !Files.exists(invocationDirectory));

        Map<String, Object> recovered = OcrTestSupport.output(action,
                OcrTestSupport.message(OcrTestSupport.TENANT, OcrTestSupport.payload(OcrTestSupport.png())));
        assertEquals("EXTRACTED", recovered.get("status"));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "condition did not become true within the deterministic test bound");
    }
}
