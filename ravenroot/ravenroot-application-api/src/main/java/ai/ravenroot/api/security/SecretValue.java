package ai.ravenroot.api.security;

import java.util.Arrays;

/** Best-effort erasable secret. Callers must close it immediately after use. */
public final class SecretValue implements AutoCloseable {
    private final char[] value;

/**
 * Copies secret characters into an independently owned buffer.
 * @param value secret characters to copy; {@code null} becomes an empty buffer
 */
    public SecretValue(char[] value) {
        this.value = value == null ? new char[0] : value.clone();
    }

/**
 * Copies the still-held secret for immediate caller use.
 * @return a new caller-owned character array, which the caller must erase after use
 */
    public char[] copy() {
        return value.clone();
    }

    @Override
    public void close() {
        Arrays.fill(value, '\0');
    }
}
