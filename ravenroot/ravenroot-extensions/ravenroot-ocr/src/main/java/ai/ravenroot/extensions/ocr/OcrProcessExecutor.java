package ai.ravenroot.extensions.ocr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Bounds process lifetime, both output streams and the descendant-first termination protocol. */
final class OcrProcessExecutor {
    enum State { SUCCESS, START_FAILED, PROCESS_FAILED, DEADLINE_EXCEEDED, OUTPUT_TOO_LARGE,
        INVALID_OUTPUT, CANCELLED, TERMINATION_FAILED }

    record Result(State state, String text) {
        Result { state = Objects.requireNonNull(state, "state"); text = text == null ? "" : text; }
    }

    private final TesseractProcessFactory factory;
    private final LongSupplier ticker;

    OcrProcessExecutor(TesseractProcessFactory factory, LongSupplier ticker) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    Result execute(OcrInvocation invocation, Duration deadline, Duration shutdownBound, int maxOutputBytes) {
        long end = endAfter(deadline);
        TesseractProcessFactory.TesseractProcess process;
        try {
            process = factory.start(invocation);
        } catch (IOException | RuntimeException unavailable) {
            return new Result(State.START_FAILED, "");
        }
        process.closeStdin();
        FutureTask<Capture> stdout = task(() -> capture(process.stdout(), maxOutputBytes));
        FutureTask<Void> stderr = task(() -> { discard(process.stderr()); return null; });
        Thread outThread = Thread.ofVirtual().name("ravenroot-ocr-stdout").start(stdout);
        Thread errThread = Thread.ofVirtual().name("ravenroot-ocr-stderr").start(stderr);
        boolean exited;
        try {
            exited = process.await(remaining(end));
        } catch (InterruptedException cancelled) {
            // Clear interruption while performing the mandatory bounded kill/reap, then restore it
            // for the caller. Leaving it set would make every bounded await fail immediately.
            Thread.interrupted();
            boolean terminated = terminate(process, shutdownBound);
            closeStreams(process);
            cancelDrain(outThread, errThread);
            awaitDrain(stdout, stderr, endAfter(shutdownBound));
            Thread.currentThread().interrupt();
            return new Result(terminated ? State.CANCELLED : State.TERMINATION_FAILED, "");
        } catch (RuntimeException failed) {
            boolean terminated = terminate(process, shutdownBound);
            closeStreams(process);
            cancelDrain(outThread, errThread);
            awaitDrain(stdout, stderr, endAfter(shutdownBound));
            return new Result(terminated ? State.PROCESS_FAILED : State.TERMINATION_FAILED, "");
        }
        if (!exited) {
            boolean terminated = terminate(process, shutdownBound);
            closeStreams(process);
            awaitDrain(stdout, stderr, endAfter(shutdownBound));
            return new Result(terminated ? State.DEADLINE_EXCEEDED : State.TERMINATION_FAILED, "");
        }

        Capture captured;
        try {
            captured = get(stdout, end);
            get(stderr, end);
        } catch (InterruptedException cancelled) {
            Thread.interrupted();
            closeStreams(process);
            cancelDrain(outThread, errThread);
            awaitDrain(stdout, stderr, endAfter(shutdownBound));
            Thread.currentThread().interrupt();
            return new Result(State.CANCELLED, "");
        } catch (ExecutionException | TimeoutException failed) {
            closeStreams(process);
            cancelDrain(outThread, errThread);
            return new Result(State.PROCESS_FAILED, "");
        }
        if (captured.overflow()) return new Result(State.OUTPUT_TOO_LARGE, "");
        if (process.exitCode() != 0) return new Result(State.PROCESS_FAILED, "");
        try {
            return new Result(State.SUCCESS, safeText(captured.bytes()));
        } catch (CharacterCodingException malformed) {
            return new Result(State.INVALID_OUTPUT, "");
        }
    }

    private boolean terminate(TesseractProcessFactory.TesseractProcess process, Duration shutdownBound) {
        long end = endAfter(shutdownBound);
        process.closeStdin();
        List<TesseractProcessFactory.ProcessRef> descendants = new ArrayList<>(process.descendants());
        TesseractProcessFactory.ProcessRef root = process.root();
        descendants.forEach(this::destroy);
        destroy(root);
        awaitAll(descendants, root, end);
        descendants.stream().filter(TesseractProcessFactory.ProcessRef::alive).forEach(this::destroyForcibly);
        if (root.alive()) destroyForcibly(root);
        awaitAll(descendants, root, end);
        return descendants.stream().noneMatch(TesseractProcessFactory.ProcessRef::alive) && !root.alive();
    }

    private void awaitAll(List<TesseractProcessFactory.ProcessRef> descendants,
                          TesseractProcessFactory.ProcessRef root, long end) {
        for (TesseractProcessFactory.ProcessRef descendant : descendants) await(descendant, end);
        await(root, end);
    }

    private void await(TesseractProcessFactory.ProcessRef process, long end) {
        if (!process.alive()) return;
        try { process.await(remaining(end)); }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        catch (RuntimeException ignored) { }
    }

    private void destroy(TesseractProcessFactory.ProcessRef process) {
        try { if (process.alive()) process.destroy(); } catch (RuntimeException ignored) { }
    }

    private void destroyForcibly(TesseractProcessFactory.ProcessRef process) {
        try { if (process.alive()) process.destroyForcibly(); } catch (RuntimeException ignored) { }
    }

    private static Capture capture(InputStream stream, int maximum) throws IOException {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        boolean overflow = false;
        try (stream) {
            for (int read; (read = stream.read(buffer)) >= 0; ) {
                if (read == 0) continue;
                int room = Math.max(0, maximum - total);
                int copy = Math.min(room, read);
                if (copy > 0) kept.write(buffer, 0, copy);
                total = Math.min(maximum, total + copy);
                if (copy < read) overflow = true;
            }
        }
        return new Capture(kept.toByteArray(), overflow);
    }

    private static void discard(InputStream stream) throws IOException {
        byte[] buffer = new byte[8192];
        try (stream) { while (stream.read(buffer) >= 0) { /* bounded discard, never retained */ } }
    }

    private static String safeText(byte[] bytes) throws CharacterCodingException {
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        StringBuilder safe = new StringBuilder(decoded.length());
        decoded.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || codePoint >= 0x20 && codePoint != 0x7f) {
                safe.appendCodePoint(codePoint);
            }
        });
        return safe.toString().strip();
    }

    private static <T> FutureTask<T> task(java.util.concurrent.Callable<T> action) {
        return new FutureTask<>(action);
    }

    private <T> T get(Future<T> future, long end)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(Math.max(0L, remaining(end).toNanos()), TimeUnit.NANOSECONDS);
    }

    private void awaitDrain(Future<?> stdout, Future<?> stderr, long end) {
        try { get(stdout, end); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException ignored) { }
        try { get(stderr, end); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException ignored) { }
    }

    private static void cancelDrain(Thread stdout, Thread stderr) {
        stdout.interrupt();
        stderr.interrupt();
    }

    private static void closeStreams(TesseractProcessFactory.TesseractProcess process) {
        try { process.stdout().close(); } catch (IOException ignored) { }
        try { process.stderr().close(); } catch (IOException ignored) { }
    }

    private long endAfter(Duration duration) {
        long now = ticker.getAsLong();
        long nanos = duration.toNanos();
        return Long.MAX_VALUE - now < nanos ? Long.MAX_VALUE : now + nanos;
    }

    private Duration remaining(long end) {
        return Duration.ofNanos(Math.max(0L, end - ticker.getAsLong()));
    }

    private record Capture(byte[] bytes, boolean overflow) { }
}
