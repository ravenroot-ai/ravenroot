package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.execution.NodeResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrRuntimeControlsTest {
    @Test
    void successfulTerminalReleasesAdmissionBeforeDependentContinuationRuns() {
        var runtime = new OcrRuntimeControls(1);
        var invocations = new AtomicInteger();

        for (int round = 0; round < 100; round++) {
            var firstAdmission = runtime.acquire("tenant-a", "profile-a", 1);
            assertTrue(firstAdmission.acquired());
            var chained = runtime.submit(firstAdmission, () -> result(invocations.incrementAndGet()))
                    .thenCompose(ignored -> {
                        var secondAdmission = runtime.acquire("tenant-a", "profile-a", 1);
                        assertTrue(secondAdmission.acquired(),
                                "terminal completion must not become visible before its admission is reusable");
                        return runtime.submit(secondAdmission, () -> result(invocations.incrementAndGet()));
                    });

            assertEquals((round + 1) * 2, chained.join().payload());
        }
        assertEquals(200, invocations.get());
    }

    private static NodeResult result(int invocation) {
        return NodeResult.continueWith(invocation);
    }
}
