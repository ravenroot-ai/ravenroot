package ai.ravenroot.api.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Framework-neutral source of stable execution identities.
 *
 * <p>Runtime composition roots use {@link #randomUuids()}; tests can inject a deterministic
 * implementation without coupling lifecycle transitions to UUID generation.</p>
 */
@FunctionalInterface
public interface ExecutionIdentitySource {
/**
 * Allocates a stable execution identity for the requested lifecycle scope.
 * @param kind identity category for which an identifier is generated.
 * @return a non-null identifier unique within the supplied lifecycle category.
 */
    UUID next(ExecutionIdentityKind kind);

/**
 * Allocates an identity for one new process instance.
 * @return a new identifier for a process instance, never reused for another instance.
 */
    default UUID nextProcessInstanceId() {
        return required(ExecutionIdentityKind.PROCESS_INSTANCE);
    }

/**
 * Allocates an identity for one ingress or re-entry traversal.
 * @return a new identifier for an ingress or re-entry traversal.
 */
    default UUID nextTraversalId() {
        return required(ExecutionIdentityKind.TRAVERSAL);
    }

/**
 * Allocates an identity for one logical node visit.
 * @return a new identifier for the logical visit of one node.
 */
    default UUID nextNodeInvocationId() {
        return required(ExecutionIdentityKind.NODE_INVOCATION);
    }

/**
 * Allocates an identity for one delivery attempt.
 * @return a new identifier for one attempt to deliver a node invocation.
 */
    default UUID nextNodeAttemptId() {
        return required(ExecutionIdentityKind.NODE_ATTEMPT);
    }

/**
 * Creates an identity for one execution event.
 * @see ExecutionIdentityKind#EVENT
 * @return a new identifier for one execution event.
 */
    default UUID nextEventId() {
        return required(ExecutionIdentityKind.EVENT);
    }

/**
 * Creates an identity source backed by random UUID generation.
 * @return an identity source that delegates each allocation to {@link UUID#randomUUID()}.
 */
    static ExecutionIdentitySource randomUuids() {
        return ignored -> UUID.randomUUID();
    }

    private UUID required(ExecutionIdentityKind kind) {
        return Objects.requireNonNull(next(kind), () -> "identity source returned null for " + kind);
    }
}
