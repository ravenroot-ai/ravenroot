package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.ExecutionKey;

/**
 * Another worker holds the lease on this process instance, so this one refuses to execute it.
 *
 * <h2>Why this is its own type</h2>
 * <p>The alternatives are worse in ways that are not symmetric. <strong>Proceeding unfenced</strong>
 * is the double-execution hole this exception exists to close: two engines would run the same
 * attempt and neither would know. <strong>Waiting</strong> hides contention behind latency until it
 * presents as a performance problem, which sends whoever investigates to the wrong subsystem.</p>
 *
 * <p>And a <em>generic</em> failure is the third wrong answer, which is why this class exists rather
 * than a bare {@code IllegalStateException}: "another worker owns this instance" and "the store is
 * broken" need opposite responses — the first is a correct refusal that resolves itself when the
 * other worker finishes or its lease expires, the second is an incident. An operator who cannot tell
 * them apart will escalate the first or ignore the second.</p>
 *
 * <p>Ordinary submission cannot raise this. A fresh submission mints a new random
 * {@code processInstanceId} through {@link ai.ravenroot.api.application.ExecutionIdentitySource},
 * and the caller-supplied identifier on {@code startGraphMl} becomes the <em>traversal</em> id, never
 * the instance id — so nobody else can already hold a lease on it. This is reachable on the re-entry
 * and recovery paths, where an existing instance is picked up again.</p>
 */
public final class ExecutionInstanceBusyException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final transient ExecutionKey key;

    public ExecutionInstanceBusyException(ExecutionKey key, Throwable cause) {
        super("Process instance " + (key == null ? "?" : key.processInstanceId())
                + " is leased by another worker; this one refuses to execute it rather than "
                + "run an unfenced duplicate", cause);
        this.key = key;
    }

    /** The instance that is busy, so an operator can look up who holds it through {@code leases()}. */
    public ExecutionKey key() {
        return key;
    }
}
