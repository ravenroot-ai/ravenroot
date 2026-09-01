package ai.ravenroot.api.deployment;

import ai.ravenroot.api.payload.PayloadException;
import ai.ravenroot.api.payload.PayloadValue;

/**
 * Builds one request payload from runtime-issued metadata, after admission and before dispatch.
 *
 * <p>The projection runs exactly once per accepted offer, on the calling thread, after the runtime
 * has reserved capacity and allocated the identifiers in {@link RequestReplyContext}, and before any
 * traversal exists. It must be side-effect-free enough to be abandoned: a projection that fails
 * starts nothing, so anything it did externally has no traversal to belong to.</p>
 *
 * <p>The returned value goes through the same bounded validation as
 * {@link RequestReplyIngress#request}: the deployment's request {@link ai.ravenroot.api.payload.PayloadLimits}
 * and the reserved-key rule. A projection that returns {@code null}, throws, or produces an
 * out-of-bounds payload yields {@link RequestReplyRefusal#PAYLOAD_REJECTED}, and no exchange becomes
 * pending.</p>
 *
 * <p>{@link PayloadException} is declared even though it is unchecked, because it is the one
 * rejection this contract understands: a projection that cannot produce a bounded payload should
 * throw it rather than an arbitrary runtime exception, so the refusal is deliberate rather than
 * incidental. Both are refused identically; the declaration says which one is meant.</p>
 */
@FunctionalInterface
public interface RequestReplyProjection {
    /**
     * Projects a runtime request into a bounded payload.
     * @param context runtime-issued identity, deadline and deployment-generation binding; read-only
     * @return the request payload, already in Ravenroot's bounded JSON-compatible model
     * @throws PayloadException when this projection declines to produce a payload
     */
    PayloadValue project(RequestReplyContext context) throws PayloadException;
}
