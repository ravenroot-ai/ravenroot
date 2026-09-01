package ai.ravenroot.api.deployment;

import ai.ravenroot.api.payload.PayloadValue;

import java.time.Instant;
import java.util.Objects;

/**
 * Engine-neutral, live request/reply admission for one deployed graph.
 *
 * <p>Identity is intentionally absent from {@link #request}. This capability is created by the
 * deployment and is already bound to the {@link ai.ravenroot.api.security.SecurityContext} that
 * activated its generation. A caller can shape payload, target and deadline; it cannot choose who
 * the traversal runs as.</p>
 */
public interface RequestReplyIngress {
    /**
     * Admits one traversal, or returns an expected refusal that starts nothing.
     *
     * @param target where the traversal begins; the first implementation supports the graph start
     * @param payload already projected into Ravenroot's bounded JSON-compatible model
     * @param deadline absolute finite deadline, validated against the deployment's operator ceiling
 * @return admission carrying either a live exchange or the expected refusal.
     */
    RequestReplyAdmission request(IngressTarget target, PayloadValue payload, Instant deadline);

    /**
     * Admits one traversal whose payload is built <em>after</em> admission, from runtime-issued
     * metadata.
     *
     * <p>The projection runs once the request has been admitted and its correlation, process,
     * traversal, deadline and deployment-generation binding have been allocated, and before the
     * traversal is dispatched. It therefore sees identity it could not otherwise know, without ever
     * being able to choose it: {@link RequestReplyContext} is an immutable value the runtime issues.</p>
     *
     * <h4>Why this is a distinct name and not an overload of {@link #request}</h4>
     * <p>An overload {@code request(IngressTarget, RequestReplyProjection, Instant)} would sit beside
     * {@code request(IngressTarget, PayloadValue, Instant)} with the same arity and an unrelated
     * middle parameter. Existing call sites that pass a {@code null} literal, or a variable whose
     * static type is neither, would stop compiling or silently bind to the other method — and the
     * requirement here is <b>source</b> compatibility for existing callers, not merely binary. A
     * separate name cannot become ambiguous, so no existing call site changes meaning.</p>
     *
     * <p>The projected payload passes the same bounded validation as {@link #request}. A projection
     * that returns {@code null}, throws, or produces an out-of-bounds payload is refused with
     * {@link RequestReplyRefusal#PAYLOAD_REJECTED}; no traversal is admitted and no exchange is left
     * pending. Identifiers allocated for a refused offer are discarded and never reissued.</p>
     *
     * <p>The default is deny-only so an ingress implementation compiled before this additive method
     * existed remains linkable and fails closed. This keeps the interface functional: the SAM is
     * still {@link #request}, so {@link #unsupported()} and every existing lambda still compile.</p>
     *
     * @param target where the traversal begins; the first implementation supports the graph start
     * @param projection builds the final immutable payload from runtime-issued metadata
     * @param deadline absolute finite deadline, validated against the deployment's operator ceiling
 * @return admission carrying either a live exchange or the expected projection refusal.
     */
    default RequestReplyAdmission requestProjected(IngressTarget target, RequestReplyProjection projection,
                                                   Instant deadline) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(projection, "projection");
        Objects.requireNonNull(deadline, "deadline");
        return new RequestReplyAdmission.Refused(RequestReplyRefusal.UNSUPPORTED);
    }

/**
 * A deny-only implementation used as the binary-compatible default on older implementations.
 * @return ingress implementation that refuses every request without allocating work.
 */
    static RequestReplyIngress unsupported() {
        return (target, payload, deadline) -> {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(deadline, "deadline");
            return new RequestReplyAdmission.Refused(RequestReplyRefusal.UNSUPPORTED);
        };
    }
}
