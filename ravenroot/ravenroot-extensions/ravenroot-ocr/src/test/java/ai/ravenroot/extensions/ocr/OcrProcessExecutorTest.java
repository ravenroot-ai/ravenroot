package ai.ravenroot.extensions.ocr;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrProcessExecutorTest {
    private static final OcrInvocation INVOCATION = new OcrInvocation(
            Path.of("/opt/ocr/bin/tesseract"), Path.of("/private/input.png"), Path.of("/private"),
            Path.of("/opt/ocr/tessdata"), "eng_ATTEMPT; touch /tmp/pwned");

    @Test void commandIsAnExactShellFreeArgumentVectorAndEnvironmentHasNoPath() {
        assertEquals(List.of(
                "/opt/ocr/bin/tesseract", "/private/input.png", "stdout",
                "--tessdata-dir", "/opt/ocr/tessdata", "-l",
                "eng_ATTEMPT; touch /tmp/pwned", "--psm", "3"),
                JdkTesseractProcessFactory.command(INVOCATION));
        assertEquals(Map.of("LANG", "C.UTF-8", "LC_ALL", "C.UTF-8"),
                JdkTesseractProcessFactory.cleanEnvironment());
        assertFalse(JdkTesseractProcessFactory.cleanEnvironment().containsKey("PATH"));
    }

    @Test void drainsStderrWithoutReturningItAndSanitizesStdout() {
        var factory = new OcrTestSupport.FakeFactory();
        factory.next.set(new OcrTestSupport.FakeProcess(
                "visible\u0000 text\n".getBytes(StandardCharsets.UTF_8),
                "SECRET_ACCESS_TOKEN".getBytes(StandardCharsets.UTF_8),
                OcrTestSupport.FakeProcess.Mode.IMMEDIATE, 0));

        OcrProcessExecutor.Result result = execute(factory, Duration.ofSeconds(1), 1024);

        assertEquals(OcrProcessExecutor.State.SUCCESS, result.state());
        assertEquals("visible text", result.text());
        assertFalse(result.text().contains("SECRET"));
    }

    @Test void continuesDrainingButRefusesOutputPastTheExactByteCeiling() {
        var factory = new OcrTestSupport.FakeFactory();
        factory.next.set(new OcrTestSupport.FakeProcess("12345".getBytes(StandardCharsets.UTF_8), new byte[0],
                OcrTestSupport.FakeProcess.Mode.IMMEDIATE, 0));
        assertEquals(OcrProcessExecutor.State.SUCCESS, execute(factory, Duration.ofSeconds(1), 5).state());

        factory.next.set(new OcrTestSupport.FakeProcess("123456".getBytes(StandardCharsets.UTF_8), new byte[0],
                OcrTestSupport.FakeProcess.Mode.IMMEDIATE, 0));
        assertEquals(OcrProcessExecutor.State.OUTPUT_TOO_LARGE,
                execute(factory, Duration.ofSeconds(1), 5).state());
    }

    @Test void timeoutKillsDescendantsBeforeRootAndForcesReapWithinTheBound() {
        var factory = new OcrTestSupport.FakeFactory();
        var process = new OcrTestSupport.FakeProcess(new byte[0], new byte[0],
                OcrTestSupport.FakeProcess.Mode.TIMEOUT, 0);
        factory.next.set(process);

        long started = System.nanoTime();
        OcrProcessExecutor.Result result = execute(factory, Duration.ofMillis(1), 1024);

        assertEquals(OcrProcessExecutor.State.DEADLINE_EXCEEDED, result.state());
        assertFalse(process.child.alive);
        assertFalse(process.root.alive);
        assertTrue(process.events.indexOf("child.destroy") < process.events.indexOf("root.destroy"));
        assertTrue(process.events.indexOf("child.force") < process.events.indexOf("root.force"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);
    }

    @Test void interruptionStillKillsAndReapsBeforeReturningCancelled() throws Exception {
        var factory = new OcrTestSupport.FakeFactory();
        var process = new OcrTestSupport.FakeProcess(new byte[0], new byte[0],
                OcrTestSupport.FakeProcess.Mode.BLOCK, 0);
        factory.next.set(process);
        var task = new FutureTask<>(() -> execute(factory, Duration.ofSeconds(5), 1024));
        Thread worker = Thread.ofVirtual().start(task);
        assertTrue(process.entered.await(1, TimeUnit.SECONDS));

        worker.interrupt();
        OcrProcessExecutor.Result result = task.get(1, TimeUnit.SECONDS);

        assertEquals(OcrProcessExecutor.State.CANCELLED, result.state());
        assertFalse(process.child.alive);
        assertFalse(process.root.alive);
    }

    private static OcrProcessExecutor.Result execute(OcrTestSupport.FakeFactory factory,
                                                     Duration deadline, int maximum) {
        return new OcrProcessExecutor(factory, System::nanoTime)
                .execute(INVOCATION, deadline, Duration.ofMillis(20), maximum);
    }
}
