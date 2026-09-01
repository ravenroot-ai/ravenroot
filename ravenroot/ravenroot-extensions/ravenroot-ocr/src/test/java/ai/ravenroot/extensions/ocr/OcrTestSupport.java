package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.PrincipalType;
import ai.ravenroot.api.security.SecurityContext;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class OcrTestSupport {
    static final String TENANT = "tenant-a";
    static final String PROFILE = "local";

    private OcrTestSupport() { }

    static OcrProfile profile(Path root, int concurrency) throws Exception {
        Path executable = Files.createFile(root.resolve("tesseract-test"));
        executable.toFile().setExecutable(true, true);
        Path tessdata = Files.createDirectory(root.resolve("tessdata"));
        Path temporary = Files.createDirectory(root.resolve("ocr-tmp"));
        return new OcrProfile(TENANT, PROFILE, executable, tessdata, Set.of("eng", "ita"), temporary,
                Duration.ofSeconds(2), 1024 * 1024, 1024 * 1024, concurrency, Duration.ofMillis(100));
    }

    static NodeAction action(OcrProfileResolver resolver, OcrRuntimeControls controls,
                             TesseractProcessFactory factory, Map<String, Object> overrides) {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("ocrProfile", PROFILE);
        properties.put("language", "eng");
        properties.putAll(overrides);
        return new OcrExtractNodeBehavior(resolver, controls, factory, System::nanoTime)
                .create(new NodeConfiguration("ocr", OcrExtractNodeBehavior.BEHAVIOR, properties));
    }

    static NodeMessage message(String tenant, Object payload) {
        UUID id = UUID.randomUUID();
        return new NodeMessage(new SecurityContext("request", tenant, "subject", PrincipalType.WORKLOAD, "issuer"),
                id, id, id, id, Set.of(), "ocr", payload, Map.of());
    }

    static Map<String, Object> payload(byte[] image) {
        return Map.of("version", OcrExtractNodeBehavior.CONTRACT,
                "imageBase64", Base64.getEncoder().encodeToString(image));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> output(NodeAction action, NodeMessage message) {
        return (Map<String, Object>) action.handle(message).toCompletableFuture().join().payload();
    }

    static byte[] png() {
        byte[] bytes = new byte[24];
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                .putInt(8, 13).putInt(12, 0x49484452).putInt(16, 400).putInt(20, 100);
        return bytes;
    }

    static byte[] jpeg() {
        return new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xc0,
                0, 7, 8, 0, 100, 1, (byte) 144, (byte) 0xff, (byte) 0xd9};
    }

    static byte[] tiff() {
        return tiffPages(new int[][] {{400, 100}});
    }

    static byte[] tiffPages(int[][] dimensions) {
        return tiffPages(dimensions, ByteOrder.BIG_ENDIAN);
    }

    static byte[] tiffPages(int[][] dimensions, ByteOrder order) {
        ByteBuffer buffer = ByteBuffer.allocate(8 + dimensions.length * 30).order(order);
        if (order == ByteOrder.LITTLE_ENDIAN) buffer.put((byte) 'I').put((byte) 'I');
        else buffer.put((byte) 'M').put((byte) 'M');
        buffer.putShort((short) 42).putInt(8);
        for (int page = 0; page < dimensions.length; page++) {
            int ifd = 8 + page * 30;
            buffer.position(ifd);
            buffer.putShort((short) 2);
            buffer.putShort((short) 256).putShort((short) 4).putInt(1).putInt(dimensions[page][0]);
            buffer.putShort((short) 257).putShort((short) 4).putInt(1).putInt(dimensions[page][1]);
            buffer.putInt(page + 1 == dimensions.length ? 0 : ifd + 30);
        }
        return buffer.array();
    }

    static final class FakeFactory implements TesseractProcessFactory {
        final AtomicReference<OcrInvocation> invocation = new AtomicReference<>();
        final AtomicReference<FakeProcess> next = new AtomicReference<>();
        @Override public TesseractProcess start(OcrInvocation value) {
            invocation.set(value);
            FakeProcess process = next.getAndSet(null);
            return process == null ? FakeProcess.success("recognized text\n") : process;
        }
    }

    static final class FakeProcess implements TesseractProcessFactory.TesseractProcess {
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        final FakeRef child = new FakeRef("child", events);
        final FakeRef root = new FakeRef("root", events);
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final InputStream stdout;
        private final InputStream stderr;
        private final Mode mode;
        private final int exitCode;

        enum Mode { IMMEDIATE, TIMEOUT, BLOCK }

        FakeProcess(byte[] stdout, byte[] stderr, Mode mode, int exitCode) {
            this.stdout = new ByteArrayInputStream(stdout);
            this.stderr = new ByteArrayInputStream(stderr);
            this.mode = mode;
            this.exitCode = exitCode;
        }

        static FakeProcess success(String text) {
            return new FakeProcess(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), new byte[0], Mode.IMMEDIATE, 0);
        }

        @Override public InputStream stdout() { return stdout; }
        @Override public InputStream stderr() { return stderr; }
        @Override public void closeStdin() { events.add("stdin.close"); }
        @Override public boolean await(Duration bound) throws InterruptedException {
            events.add("process.await"); entered.countDown();
            if (mode == Mode.IMMEDIATE) { child.alive = false; root.alive = false; return true; }
            if (mode == Mode.TIMEOUT) return false;
            boolean done = release.await(Math.max(0L, bound.toNanos()), TimeUnit.NANOSECONDS);
            if (done) { child.alive = false; root.alive = false; }
            return done;
        }
        @Override public int exitCode() { return exitCode; }
        @Override public List<TesseractProcessFactory.ProcessRef> descendants() { return List.of(child); }
        @Override public TesseractProcessFactory.ProcessRef root() { return root; }
    }

    static final class FakeRef implements TesseractProcessFactory.ProcessRef {
        private final String name;
        private final List<String> events;
        volatile boolean alive = true;
        volatile boolean ignoreGraceful = true;
        FakeRef(String name, List<String> events) { this.name = name; this.events = events; }
        @Override public boolean alive() { return alive; }
        @Override public void destroy() { events.add(name + ".destroy"); if (!ignoreGraceful) alive = false; }
        @Override public void destroyForcibly() { events.add(name + ".force"); alive = false; }
        @Override public boolean await(Duration bound) { events.add(name + ".await"); return !alive; }
    }
}
