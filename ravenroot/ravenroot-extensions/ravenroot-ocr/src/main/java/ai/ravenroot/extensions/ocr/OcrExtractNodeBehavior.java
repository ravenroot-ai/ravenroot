package ai.ravenroot.extensions.ocr;

import ai.ravenroot.api.catalog.NodePropertyDescriptor;
import ai.ravenroot.api.catalog.NodePropertyType;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.execution.NodeMessage;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodeConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/** Extracts bounded text through one operator-profiled local Tesseract process. */
public final class OcrExtractNodeBehavior implements NodeBehavior {
    public static final String BEHAVIOR = "ocr.extract";
    public static final String CONTRACT = "ocr.extract.v1";
    private static final Set<String> CONFIGURATION = Set.of(
            "ocrProfile", "language", "deadlineMs", "maxInputBytes", "maxOutputBytes", "maxConcurrency");
    private static final NodeTypeDescriptor DESCRIPTOR = descriptorValue();

    private final OcrProfileResolver profiles;
    private final OcrRuntimeControls runtime;
    private final TesseractProcessFactory processFactory;
    private final LongSupplier ticker;

    public OcrExtractNodeBehavior() {
        this(new EnvironmentOcrProfileResolver(), OcrRuntimeControls.PRODUCTION,
                new JdkTesseractProcessFactory(), System::nanoTime);
    }

    OcrExtractNodeBehavior(OcrProfileResolver profiles, OcrRuntimeControls runtime,
                           TesseractProcessFactory processFactory, LongSupplier ticker) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.processFactory = Objects.requireNonNull(processFactory, "processFactory");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    @Override public NodeTypeDescriptor descriptor() { return DESCRIPTOR; }

    @Override
    public NodeAction create(NodeConfiguration configuration) {
        final GraphSettings graph;
        try {
            graph = GraphSettings.from(configuration);
        } catch (Refusal refusal) {
            return message -> CompletableFuture.completedFuture(output("REJECTED", refusal.reason, null, null, "<100ms"));
        }
        return message -> handle(graph, message);
    }

    private java.util.concurrent.CompletionStage<NodeResult> handle(GraphSettings graph, NodeMessage message) {
        final OcrProfile profile;
        try {
            Optional<OcrProfile> resolved = profiles.resolve(message.tenantId(), graph.profileName);
            profile = resolved == null ? null : resolved.orElse(null);
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(output("REJECTED", "PROFILE_UNAVAILABLE", null, null, "<100ms"));
        }
        if (profile == null || !message.tenantId().equals(profile.tenantId())) {
            return CompletableFuture.completedFuture(output("REJECTED", "PROFILE_UNAVAILABLE", null, null, "<100ms"));
        }
        final EffectiveSettings settings;
        try {
            settings = graph.apply(profile);
        } catch (Refusal refusal) {
            return CompletableFuture.completedFuture(output("REJECTED", refusal.reason, null, null, "<100ms"));
        }
        OcrRuntimeControls.Admission admission = runtime.acquire(
                message.tenantId(), profile.name(), settings.maxConcurrency);
        if (!admission.acquired()) {
            return CompletableFuture.completedFuture(output(
                    "TEMPORARY_FAILURE", "LOCAL_CAPACITY", null, settings.language, "<100ms"));
        }
        try {
            return runtime.submit(admission, () -> execute(settings, message.payload()));
        } catch (RuntimeException rejected) {
            admission.release();
            return CompletableFuture.completedFuture(output(
                    "TEMPORARY_FAILURE", "LOCAL_CAPACITY", null, settings.language, "<100ms"));
        }
    }

    private NodeResult execute(EffectiveSettings settings, Object payload) {
        long started = ticker.getAsLong();
        OcrImage image;
        try {
            image = OcrImage.from(payload, settings.maxInputBytes);
        } catch (RuntimeException invalid) {
            return output("REJECTED", "INVALID_IMAGE", null, settings.language, elapsed(started));
        }
        if (Thread.currentThread().isInterrupted()) {
            return output("TEMPORARY_FAILURE", "CANCELLED", image, settings.language, elapsed(started));
        }
        try {
            Path executable = availableExecutable(settings.profile.executable());
            Path languageData = availableDirectory(settings.profile.languageData());
            try (OcrWorkspace workspace = OcrWorkspace.create(settings.profile, image)) {
                if (Thread.currentThread().isInterrupted()) {
                    return output("TEMPORARY_FAILURE", "CANCELLED", image, settings.language, elapsed(started));
                }
                OcrInvocation invocation = new OcrInvocation(executable, workspace.input(), workspace.directory(),
                        languageData, settings.language);
                OcrProcessExecutor.Result result = new OcrProcessExecutor(processFactory, ticker)
                        .execute(invocation, settings.deadline, settings.profile.shutdownBound(), settings.maxOutputBytes);
                return switch (result.state()) {
                    case SUCCESS -> success(result.text(), image, settings.language, elapsed(started));
                    case OUTPUT_TOO_LARGE -> output("REJECTED", "OUTPUT_TOO_LARGE", image,
                            settings.language, elapsed(started));
                    case DEADLINE_EXCEEDED -> output("TEMPORARY_FAILURE", "DEADLINE_EXCEEDED", image,
                            settings.language, elapsed(started));
                    case CANCELLED -> output("TEMPORARY_FAILURE", "CANCELLED", image,
                            settings.language, elapsed(started));
                    case START_FAILED -> output("PERMANENT_FAILURE", "EXECUTABLE_UNAVAILABLE", image,
                            settings.language, elapsed(started));
                    case PROCESS_FAILED, INVALID_OUTPUT -> output("PERMANENT_FAILURE", "OCR_FAILED", image,
                            settings.language, elapsed(started));
                    case TERMINATION_FAILED -> output("TEMPORARY_FAILURE", "PROCESS_DRAIN_INCOMPLETE", image,
                            settings.language, elapsed(started));
                };
            }
        } catch (IOException | RuntimeException unavailable) {
            return output("PERMANENT_FAILURE", "PROFILE_UNAVAILABLE", image, settings.language, elapsed(started));
        }
    }

    private static Path availableExecutable(Path configured) throws IOException {
        Path executable = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(executable)) {
            throw new IOException("OCR executable is unavailable");
        }
        return executable;
    }

    private static Path availableDirectory(Path configured) throws IOException {
        Path directory = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("OCR language data is unavailable");
        }
        return directory;
    }

    private NodeResult success(String text, OcrImage image, String language, String elapsed) {
        Map<String, Object> values = base("EXTRACTED", "NONE", image, language, elapsed);
        values.put("text", text);
        return NodeResult.continueWith(Map.copyOf(values));
    }

    private static NodeResult output(String status, String reason, OcrImage image,
                                     String language, String elapsed) {
        return NodeResult.continueWith(Map.copyOf(base(status, reason, image, language, elapsed)));
    }

    private static LinkedHashMap<String, Object> base(String status, String reason, OcrImage image,
                                                       String language, String elapsed) {
        var values = new LinkedHashMap<String, Object>();
        values.put("version", CONTRACT);
        values.put("status", status);
        values.put("reason", reason);
        if (language != null) values.put("language", language);
        if (image != null) {
            values.put("format", image.format());
            values.put("imageBytes", image.bytes().length);
            values.put("width", image.width());
            values.put("height", image.height());
            values.put("pages", image.pages());
        }
        values.put("elapsedBucket", elapsed);
        return values;
    }

    private String elapsed(long started) {
        long nanos = Math.max(0L, ticker.getAsLong() - started);
        if (nanos < 100_000_000L) return "<100ms";
        if (nanos < 500_000_000L) return "100-499ms";
        if (nanos < 1_000_000_000L) return "500-999ms";
        if (nanos < 5_000_000_000L) return "1-4s";
        return ">=5s";
    }

    private static NodeTypeDescriptor descriptorValue() {
        List<NodePropertyDescriptor> properties = List.of(
                NodePropertyDescriptor.required("ocrProfile", "OCR profile", NodePropertyType.STRING,
                        "Opaque tenant-scoped operator profile; executable and filesystem authority never come from GraphML."),
                NodePropertyDescriptor.required("language", "Language", NodePropertyType.STRING,
                        "One Tesseract language identifier allowed by the selected operator profile."),
                NodePropertyDescriptor.optional("deadlineMs", "Deadline (ms)", NodePropertyType.INTEGER,
                        "Optional deadline tightening; zero or an increase is refused.", ""),
                NodePropertyDescriptor.optional("maxInputBytes", "Input bytes", NodePropertyType.INTEGER,
                        "Optional input-byte ceiling tightening.", ""),
                NodePropertyDescriptor.optional("maxOutputBytes", "Output bytes", NodePropertyType.INTEGER,
                        "Optional OCR-output ceiling tightening.", ""),
                NodePropertyDescriptor.optional("maxConcurrency", "Concurrency", NodePropertyType.INTEGER,
                        "Optional per-tenant/profile concurrency tightening.", ""));
        return new NodeTypeDescriptor(BEHAVIOR, "Extract text with OCR", "Documents",
                "Extracts bounded UTF-8 text from one inline PNG, JPEG or TIFF using an operator-fixed local Tesseract executable.",
                "actor", false, properties, Set.of("compute", "local-process"));
    }

    private record GraphSettings(String profileName, String language, Integer deadlineMs,
                                 Integer maxInputBytes, Integer maxOutputBytes, Integer maxConcurrency) {
        static GraphSettings from(NodeConfiguration configuration) {
            if (!BEHAVIOR.equals(configuration.behavior())
                    || configuration.properties().keySet().stream().anyMatch(key -> !CONFIGURATION.contains(key))) {
                throw new Refusal("INVALID_CONFIGURATION");
            }
            String profile = safeIdentifier(configuration.requiredProperty("ocrProfile"), "INVALID_PROFILE");
            String language = safeLanguage(configuration.requiredProperty("language"));
            return new GraphSettings(profile, language,
                    optionalPositive(configuration, "deadlineMs"),
                    optionalPositive(configuration, "maxInputBytes"),
                    optionalPositive(configuration, "maxOutputBytes"),
                    optionalPositive(configuration, "maxConcurrency"));
        }

        EffectiveSettings apply(OcrProfile profile) {
            if (!profile.permitsLanguage(language)) throw new Refusal("LANGUAGE_FORBIDDEN");
            Duration deadline = deadlineMs == null ? profile.deadline() : Duration.ofMillis(deadlineMs);
            if (deadline.isZero() || deadline.isNegative() || deadline.compareTo(profile.deadline()) > 0) {
                throw new Refusal("LIMIT_WIDENING");
            }
            int input = tightened(maxInputBytes, profile.maxInputBytes());
            int output = tightened(maxOutputBytes, profile.maxOutputBytes());
            int concurrency = tightened(maxConcurrency, profile.maxConcurrency());
            return new EffectiveSettings(profile, language, deadline, input, output, concurrency);
        }

        private static int tightened(Integer requested, int maximum) {
            if (requested == null) return maximum;
            if (requested <= 0 || requested > maximum) throw new Refusal("LIMIT_WIDENING");
            return requested;
        }

        private static Integer optionalPositive(NodeConfiguration configuration, String name) {
            Optional<String> raw = configuration.property(name);
            if (raw.isEmpty()) return null;
            try {
                int value = Integer.parseInt(raw.get());
                if (value <= 0) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException invalid) {
                throw new Refusal("INVALID_CONFIGURATION");
            }
        }

        private static String safeIdentifier(String value, String reason) {
            if (!OcrProfile.safeIdentifier(value)) throw new Refusal(reason);
            return value;
        }

        private static String safeLanguage(String value) {
            if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{1,31}")) {
                throw new Refusal("INVALID_LANGUAGE");
            }
            return value;
        }
    }

    private record EffectiveSettings(OcrProfile profile, String language, Duration deadline,
                                     int maxInputBytes, int maxOutputBytes, int maxConcurrency) { }

    private static final class Refusal extends RuntimeException {
        private final String reason;
        private Refusal(String reason) { super(reason, null, false, false); this.reason = reason; }
    }
}
