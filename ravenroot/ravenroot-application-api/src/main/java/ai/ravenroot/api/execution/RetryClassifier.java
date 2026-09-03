package ai.ravenroot.api.execution;

import ai.ravenroot.api.persistence.ExecutionStoreException;
import ai.ravenroot.api.persistence.Retryability;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Decides what class of failure a node produced, in the one vocabulary the persistence port already
 * uses (ADR 0010 section 12).
 *
 * <p>A classifier answers only "what kind of failure is this". It never decides whether to retry:
 * that is {@link RetryPolicy}'s job, and keeping the two apart is what lets the disposition of each
 * {@link Retryability} value stay fixed rather than becoming a second thing an author can configure
 * wrongly.</p>
 */
@FunctionalInterface
public interface RetryClassifier {

    /**
     * Classifies one failure.
     *
     * @param failure the throwable a node attempt produced; never {@code null}
     * @return the classification, never {@code null}; an implementation that cannot decide returns
     *         {@link Retryability#DETERMINISTIC_REJECT}, which is the fail-closed answer
     */
    Retryability classify(Throwable failure);

    /**
     * The classifier every node gets unless its declaration replaces it.
     *
     * <h4>It is fail-closed, and that is the whole of its safety argument</h4>
     * <p>Anything it cannot positively classify is {@link Retryability#DETERMINISTIC_REJECT}, so a
     * node that declares a retry count and nothing else retries <em>nothing</em>, and a node that
     * declares no policy at all behaves exactly as this runtime behaved before retries existed. The
     * alternative — treating an unrecognised exception as retryable — would automatically repeat
     * effects nobody authorised, which is the posture ADR 0022 exists to refuse. A retry policy that
     * appears to do nothing is a policy an author can debug; one that silently repeats a payment is
     * not.</p>
     *
     * <p>Three things are positively classified, and only three:</p>
     * <ul>
     *   <li>a failure implementing {@link RetryClassified}, which states its own classification —
     *       this is how a connector says precisely what it knows;</li>
     *   <li>an {@link ExecutionStoreException}, which already carries a
     *       {@link Retryability} on its failure and needs no second opinion;</li>
     *   <li>a {@link CancellationException}, classified {@link Retryability#DETERMINISTIC_REJECT}
     *       explicitly rather than by falling through, because "cancelled" must never be read as a
     *       transient condition worth another attempt.</li>
     * </ul>
     *
     * <p>Wrappers are unwrapped first. A failure arriving through a {@link java.util.concurrent.CompletionStage}
     * is routinely a {@link CompletionException} around the real cause, and a classifier that looked
     * only at the outer type would classify every asynchronous failure identically — which for a
     * fail-closed default means never retrying anything at all through the path every node actually
     * uses.</p>
     *
     * @return the shared fail-closed classifier
     */
    static RetryClassifier failClosed() {
        return failure -> {
            Throwable cause = unwrap(failure);
            if (cause instanceof RetryClassified classified) {
                Retryability stated = classified.retryability();
                return stated == null ? Retryability.DETERMINISTIC_REJECT : stated;
            }
            if (cause instanceof ExecutionStoreException stored && stored.failure() != null) {
                return stored.failure().retryability();
            }
            if (cause instanceof CancellationException) {
                // Stated rather than left to the fall-through below. The two produce the same value
                // today, and a later reader loosening the fall-through must not silently loosen this
                // one with it: a cancelled attempt is not a transient condition worth another try.
                return Retryability.DETERMINISTIC_REJECT;
            }
            return Retryability.DETERMINISTIC_REJECT;
        };
    }

    /**
     * A classifier that treats the named throwable types as {@link Retryability#RETRYABLE_NO_EFFECT}
     * and delegates everything else to {@link #failClosed()}.
     *
     * <p>This is the author's channel: the runtime cannot know that <em>this</em> node's
     * {@code SocketTimeoutException} left no effect, and the author can. Names are matched against
     * the failure's own class and every supertype, by both fully qualified and simple name, so
     * {@code java.io.IOException} covers a subclass the author never enumerated and
     * {@code IOException} works without the package. Matching is exact and case-sensitive: an
     * approximate match here authorises repeating an effect nobody named.</p>
     *
     * <p>An unrecognisable name is <strong>not</strong> an error and is simply never matched. The
     * name is author text about types the runtime may not have loaded — a connector's exception class
     * lives in a plugin that may be absent from this deployment — so refusing the graph would make a
     * node undeployable because of a declaration about a dependency it is not using. The failure mode
     * of a typo is therefore "this failure is not retried", which is the fail-closed direction.</p>
     *
     * @param retryableTypeNames throwable class names the author declares safe to retry; {@code null}
     *                           or empty yields exactly {@link #failClosed()}
     * @return a classifier honouring the allowlist over the fail-closed default
     */
    static RetryClassifier declaredRetryable(Set<String> retryableTypeNames) {
        if (retryableTypeNames == null || retryableTypeNames.isEmpty()) {
            return failClosed();
        }
        Set<String> declared = Set.copyOf(retryableTypeNames);
        RetryClassifier fallback = failClosed();
        return failure -> {
            Throwable cause = unwrap(failure);
            for (Class<?> type = cause.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                if (declared.contains(type.getName()) || declared.contains(type.getSimpleName())) {
                    return Retryability.RETRYABLE_NO_EFFECT;
                }
            }
            return fallback.classify(failure);
        };
    }

    /**
     * Strips the asynchronous plumbing wrappers a node failure routinely arrives inside.
     *
     * <p>Bounded by an identity set rather than by a depth counter: a {@link Throwable} may be its
     * own cause, and a cycle here would hang the classifier on the one input a hostile or merely
     * careless node can construct. The loop stops at the first cause already seen and returns what it
     * has, which is always a real throwable and never {@code null}.</p>
     *
     * @param failure the throwable as delivered; never {@code null}
     * @return the innermost non-wrapper cause, or {@code failure} itself when it is not wrapped
     */
    static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        var seen = new LinkedHashSet<Throwable>();
        while (seen.add(current)) {
            boolean wrapper = current instanceof CompletionException
                    || current instanceof ExecutionException;
            Throwable cause = wrapper ? current.getCause() : null;
            if (cause == null) {
                return current;
            }
            current = cause;
        }
        return current;
    }
}
