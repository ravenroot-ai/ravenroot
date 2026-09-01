package ai.ravenroot.extensions.ocr;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable operator authority for one tenant's local Tesseract installation. */
public record OcrProfile(
        String tenantId,
        String name,
        Path executable,
        Path languageData,
        Set<String> allowedLanguages,
        Path temporaryRoot,
        Duration deadline,
        int maxInputBytes,
        int maxOutputBytes,
        int maxConcurrency,
        Duration shutdownBound) {

    static final int ABSOLUTE_MAX_INPUT_BYTES = 32 * 1024 * 1024;
    static final int ABSOLUTE_MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    static final int ABSOLUTE_MAX_CONCURRENCY = 64;
    static final Duration ABSOLUTE_MAX_DEADLINE = Duration.ofMinutes(2);
    static final Duration ABSOLUTE_MAX_SHUTDOWN = Duration.ofSeconds(10);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,31}");

    public OcrProfile {
        tenantId = identifier(tenantId, "tenantId");
        name = identifier(name, "name");
        executable = absolute(executable, "executable");
        languageData = absolute(languageData, "languageData");
        temporaryRoot = absolute(temporaryRoot, "temporaryRoot");
        allowedLanguages = Set.copyOf(Objects.requireNonNull(allowedLanguages, "allowedLanguages"));
        if (allowedLanguages.isEmpty() || allowedLanguages.size() > 32
                || allowedLanguages.stream().anyMatch(value -> value == null || !LANGUAGE.matcher(value).matches())) {
            throw new IllegalArgumentException("allowedLanguages must contain 1-32 safe Tesseract language ids");
        }
        deadline = duration(deadline, ABSOLUTE_MAX_DEADLINE, "deadline");
        shutdownBound = duration(shutdownBound, ABSOLUTE_MAX_SHUTDOWN, "shutdownBound");
        maxInputBytes = positive(maxInputBytes, ABSOLUTE_MAX_INPUT_BYTES, "maxInputBytes");
        maxOutputBytes = positive(maxOutputBytes, ABSOLUTE_MAX_OUTPUT_BYTES, "maxOutputBytes");
        maxConcurrency = positive(maxConcurrency, ABSOLUTE_MAX_CONCURRENCY, "maxConcurrency");
    }

    public boolean permitsLanguage(String language) {
        return language != null && allowedLanguages.contains(language);
    }

    static boolean safeIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String identifier(String value, String name) {
        if (!safeIdentifier(value)) throw new IllegalArgumentException(name + " is not a safe identifier");
        return value;
    }

    private static Path absolute(Path value, String name) {
        Path path = Objects.requireNonNull(value, name).normalize();
        if (!path.isAbsolute()) throw new IllegalArgumentException(name + " must be absolute");
        return path;
    }

    private static Duration duration(Duration value, Duration maximum, String name) {
        Duration safe = Objects.requireNonNull(value, name);
        if (safe.isZero() || safe.isNegative() || safe.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the supported positive bound");
        }
        try { safe.toNanos(); } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(name + " is not representable", overflow);
        }
        return safe;
    }

    private static int positive(int value, int maximum, String name) {
        if (value <= 0 || value > maximum) throw new IllegalArgumentException(name + " is outside 1.." + maximum);
        return value;
    }
}
