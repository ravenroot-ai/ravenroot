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
            Retryability stated = statedClassification(failure);
            return stated == null ? Retryability.DETERMINISTIC_REJECT : stated;
        };
    }

    /**
     * The classification the failure itself asserts, or {@code null} when it asserts nothing.
     *
     * <h4>Why this is separate from {@link #failClosed()}, and why it may not be folded back in</h4>
     * <p>{@code failClosed} answers {@link Retryability#DETERMINISTIC_REJECT} for two different
     * facts: "this failure said it is deterministic" and "this failure said nothing". Collapsing them
     * is right for a caller that only wants a decision, and <strong>wrong</strong> for
     * {@link #declaredRetryable(Set)}, which must widen the second and must not touch the first. That
     * is not a hypothetical distinction: with the two folded together, an author declaring
     * {@code retry.retryOn=RuntimeException} silently overrode a connector's own
     * {@link Retryability#INDETERMINATE} — "the POST may already have landed" — and the effect was
     * repeated automatically. This method exists so that the difference is representable and the
     * ordering below is structural rather than a comment.</p>
     *
     * <p>Three sources are consulted, in the order a reader would expect them to bind: the failure's
     * own statement through {@link RetryClassified}, which is how a connector says precisely what it
     * knows; an {@link ExecutionStoreException}, which already carries a classification on its
     * failure and needs no second opinion; and a {@link CancellationException}, which is stated
     * explicitly rather than left to fall through, because "cancelled" must never be readable as a
     * transient condition worth another attempt.</p>
     *
     * <p>A {@link RetryClassified} returning {@code null} has not classified anything and is treated
     * as silence, not as a refusal — the widening below may then apply to it, which is correct: the
     * implementation declined to answer, so the author's declaration is the only statement there is.
     * The interface's own contract already tells implementations to return
     * {@link Retryability#INDETERMINATE} rather than {@code null} when they cannot decide.</p>
     *
     * @param failure the throwable as delivered, wrapped or not; never {@code null}
     * @return the asserted classification, or {@code null} when nothing was asserted
     */
    private static Retryability statedClassification(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof RetryClassified classified) {
            return classified.retryability();
        }
        if (cause instanceof ExecutionStoreException stored && stored.failure() != null) {
            return stored.failure().retryability();
        }
        if (cause instanceof CancellationException) {
            return Retryability.DETERMINISTIC_REJECT;
        }
        return null;
    }

    /**
     * A classifier that widens the named throwable types to
     * {@link Retryability#RETRYABLE_NO_EFFECT}, and changes nothing a failure stated about itself.
     *
     * <h4>The allowlist may only widen silence, never a stated classification</h4>
     * <p>{@link #statedClassification(Throwable)} is consulted <strong>first</strong>, and whatever it
     * asserts is returned untouched. The declaration applies only when the failure asserted nothing.
     * The ordering is the safety property, and reversing it is not a subtle regression: an author
     * declaring a family — {@code retry.retryOn=RuntimeException}, which the tests and this Javadoc
     * both encourage — would otherwise match before a connector's own
     * {@link Retryability#INDETERMINATE} was ever read, and the runtime would automatically repeat an
     * effect that may already have landed. It would also override a
     * {@link CancellationException}, turning a stop into a retry loop.</p>
     *
     * <p>What the author's channel is <em>for</em> is unaffected: the runtime cannot know that
     * <em>this</em> node's {@code SocketTimeoutException} left no effect, and the author can. That
     * exception states nothing about itself, so the declaration is the only statement there is and it
     * binds. A connector precise enough to implement {@link RetryClassified} has already answered the
     * question the declaration was guessing at, and its answer wins.</p>
     *
     * <p>Names are matched against the failure's own class and every supertype — superclasses and
     * interfaces, transitively — by both fully qualified and simple name, so
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
     * @return a classifier that widens unclassified failures named here, and defers to every
     *         classification a failure states about itself
     */
    static RetryClassifier declaredRetryable(Set<String> retryableTypeNames) {
        if (retryableTypeNames == null || retryableTypeNames.isEmpty()) {
            return failClosed();
        }
        Set<String> declared = Set.copyOf(retryableTypeNames);
        return failure -> {
            Retryability stated = statedClassification(failure);
            if (stated != null) {
                return stated;
            }
            return namesAnySupertypeOf(declared, unwrap(failure))
                    ? Retryability.RETRYABLE_NO_EFFECT
                    : Retryability.DETERMINISTIC_REJECT;
        };
    }

    /**
     * Whether {@code declared} names {@code cause}'s own type or any of its supertypes.
     *
     * <p>Walks superclasses <em>and</em> interfaces, transitively, which is what "every supertype"
     * means and what an author reading that sentence would expect. The superclass-only walk this
     * replaces could never match an interface name, so a declaration naming one silently retried
     * nothing — the quiet direction, but still a promise the code did not keep.</p>
     *
     * <p>Breadth-first over a worklist rather than recursion, and guarded by a visited set, because
     * the interface graph is a DAG: a type implementing two interfaces that share a super-interface
     * would otherwise be walked through it twice, and a deep hierarchy would be walked
     * exponentially.</p>
     *
     * @param declared    the author's exact names, fully qualified or simple
     * @param cause       the unwrapped failure whose hierarchy is searched
     * @return whether any type in that hierarchy is named
     */
    private static boolean namesAnySupertypeOf(Set<String> declared, Throwable cause) {
        var pending = new java.util.ArrayDeque<Class<?>>();
        var seen = new java.util.HashSet<Class<?>>();
        pending.add(cause.getClass());
        while (!pending.isEmpty()) {
            Class<?> type = pending.poll();
            if (type == Object.class || !seen.add(type)) {
                continue;
            }
            if (declared.contains(type.getName()) || declared.contains(type.getSimpleName())) {
                return true;
            }
            // Guarded, because an interface reached through getInterfaces() has a null superclass and
            // ArrayDeque refuses null outright. Object itself is skipped above rather than here: every
            // throwable extends it, so naming it would widen the allowlist to everything.
            Class<?> parent = type.getSuperclass();
            if (parent != null) {
                pending.add(parent);
            }
            java.util.Collections.addAll(pending, type.getInterfaces());
        }
        return false;
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
