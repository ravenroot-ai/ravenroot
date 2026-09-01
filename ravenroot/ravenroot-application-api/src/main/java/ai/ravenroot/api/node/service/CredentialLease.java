package ai.ravenroot.api.node.service;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Short-lived, best-effort erasable credential material.
 *
 * <p>The lease is available only when the operator separately grants raw credential resolution.
 * HTTP and WebSocket credential bindings do not expose one. Callers must close the lease in the
 * same invocation that obtained it and must not retain a copy.</p>
 */
public final class CredentialLease implements AutoCloseable {
    private final char[] value;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean copied = new AtomicBoolean();

    /**
     * Copies credential characters into lease-owned storage.
     *
     * @param value credential material to protect; {@code null} becomes an empty lease and the caller
     *              retains ownership of its original array
     */
    public CredentialLease(char[] value) {
        this.value = value == null ? new char[0] : value.clone();
    }

/**
 * Returns a caller-owned copy which the caller must erase immediately after use.
     * @return one caller-owned copy; a lease grants exactly one copy operation
 */
    public char[] copy() {
        if (closed.get() || !copied.compareAndSet(false, true)) {
            throw new IllegalStateException("Credential lease is closed or already consumed");
        }
        return value.clone();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Arrays.fill(value, '\0');
        }
    }
}
