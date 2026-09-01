package ai.ravenroot.extensions.ocr;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Shell-free production launcher with a deliberately minimal child environment. */
final class JdkTesseractProcessFactory implements TesseractProcessFactory {
    private static final Map<String, String> CLEAN_ENVIRONMENT = Map.of(
            "LANG", "C.UTF-8",
            "LC_ALL", "C.UTF-8");

    @Override
    public TesseractProcess start(OcrInvocation invocation) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command(invocation));
        builder.directory(invocation.workingDirectory().toFile());
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(cleanEnvironment());
        return new JdkProcess(builder.start());
    }

    static List<String> command(OcrInvocation invocation) {
        return List.of(
                invocation.executable().toString(),
                invocation.inputFile().toString(),
                "stdout",
                "--tessdata-dir", invocation.languageData().toString(),
                "-l", invocation.language(),
                "--psm", "3");
    }

    static Map<String, String> cleanEnvironment() { return CLEAN_ENVIRONMENT; }

    private static final class JdkProcess implements TesseractProcess {
        private final Process process;

        private JdkProcess(Process process) { this.process = process; }
        @Override public InputStream stdout() { return process.getInputStream(); }
        @Override public InputStream stderr() { return process.getErrorStream(); }
        @Override public void closeStdin() {
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
        }
        @Override public boolean await(Duration bound) throws InterruptedException {
            return process.waitFor(Math.max(0L, bound.toNanos()), TimeUnit.NANOSECONDS);
        }
        @Override public int exitCode() { return process.exitValue(); }
        @Override public List<ProcessRef> descendants() {
            var descendants = new ArrayList<ProcessRef>();
            process.descendants().forEach(handle -> descendants.add(new JdkRef(handle)));
            java.util.Collections.reverse(descendants);
            return List.copyOf(descendants);
        }
        @Override public ProcessRef root() { return new JdkRef(process.toHandle()); }
    }

    private record JdkRef(ProcessHandle handle) implements ProcessRef {
        @Override public boolean alive() { return handle.isAlive(); }
        @Override public void destroy() { handle.destroy(); }
        @Override public void destroyForcibly() { handle.destroyForcibly(); }
        @Override public boolean await(Duration bound) throws InterruptedException {
            if (!handle.isAlive()) return true;
            try {
                handle.onExit().get(Math.max(0L, bound.toNanos()), TimeUnit.NANOSECONDS);
                return true;
            } catch (java.util.concurrent.TimeoutException timeout) {
                return !handle.isAlive();
            } catch (java.util.concurrent.ExecutionException failed) {
                return !handle.isAlive();
            }
        }
    }
}
