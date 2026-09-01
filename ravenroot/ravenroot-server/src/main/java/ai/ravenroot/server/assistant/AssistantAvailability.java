package ai.ravenroot.server.assistant;

import ai.ravenroot.server.audit.JsonStrings;

/**
 * What {@code GET /v1/assistant} answers: which of the things the panel needs is missing, and who
 * can fix it under the assistant-availability contract.
 *
 * <h2>Why this is booleans on the wire and not one reason string</h2>
 * <p>The wire shape is dictated by {@code ravenroot-ui/src/assistant-client.js}, which normalizes
 * {@code configured}, {@code allowlisted}, {@code signedIn}, {@code linkRequired} and
 * {@code provider}, then derives the reason itself in {@code assistant-session.js}'s
 * {@code inertReason()}. This class must therefore project its reason <em>back</em> into those
 * booleans rather than inventing a field the client would ignore. The projection is the
 * {@code switch} in {@link #toJson()} and it is exhaustive over the enum, so adding a reason without
 * deciding how the panel should render it does not compile.</p>
 *
 * <h2>The one reason this class never carries onto the wire</h2>
 * <p>{@link InertReason#SERVICE_UNAVAILABLE} has no 200 representation. When the operator disables
 * the assistant the route answers {@code 404}, which
 * {@code assistant-client.js}'s {@code status()} already maps to {@code reachable: false} — the same
 * answer it gives for a build that never had the route at all. That is deliberate: from the panel's
 * side "this deployment does not provide the assistant service" is one fact, and giving it two
 * different wire encodings would be two code paths to keep agreeing forever. It is still
 * server-provokable — set {@code RAVENROOT_ASSISTANT_ENABLED=false} — which is what keeps it from
 * being an inert reason no test can reach.</p>
 */
public record AssistantAvailability(InertReason reason, String provider, String detail) {

    /**
     * The distinguished inert reasons, in the assistant-availability contract's most-fundamental-first order.
     * Mirrors {@code assistant-session.js}'s {@code INERT_REASON_ORDER} constant; the wire strings are
     * that file's constants, not new vocabulary.
     */
    public enum InertReason {
        /** This deployment has no assistant service. Answered as 404, never as a 200 body. */
        SERVICE_UNAVAILABLE("service-unavailable"),
        /** Operator configuration: no provider profile, or no credential for it. */
        NO_PROFILE("no-profile"),
        /** Plain HTTP was requested outside the explicit credential-free local exception. */
        INSECURE_REFUSED("insecure-refused"),
        /** Operator egress policy. A user cannot widen it and must not be told to try. */
        HOST_NOT_ALLOWLISTED("host-not-allowlisted"),
        /** The reader's own Ravenroot session. Reachable only via a 401/403 on this route. */
        NOT_SIGNED_IN("not-signed-in"),
        /**
         * This author has not connected to the provider yet, in a deployment that expects them to.
         *
         * <p><b>Distinct from {@link #NOT_SIGNED_IN}.</b> Projecting both facts onto the same wire state
         * produces a false sentence: the panel's
         * {@code not-signed-in} means the RAVENROOT session is unauthenticated and says "Sign in to
         * Ravenroot and try again", which an author reading it had already done. The two are
         * different facts with different remedies and only this one has a remedy the panel can
         * offer, so they are now different reasons.</p>
         */
        NOT_LINKED("not-linked");

        private final String wireValue;

        InertReason(String wireValue) {
            this.wireValue = wireValue;
        }

        /** The exact string {@code assistant-session.js} compares against. */
        public String wireValue() {
            return wireValue;
        }
    }

    public static AssistantAvailability ready(String provider) {
        return new AssistantAvailability(null, provider, "");
    }

    public static AssistantAvailability inert(InertReason reason, String detail) {
        return new AssistantAvailability(java.util.Objects.requireNonNull(reason, "reason"), null, detail);
    }

    public boolean ready() {
        return reason == null;
    }

    /**
     * The projection onto the client's prerequisite fields.
     *
     * <p>Each flag is false from the first missing prerequisite onward, never "false only for the one
     * that failed" — {@code inertReason()} reads them in order and returns the first falsehood, so a
     * body claiming {@code configured: false, allowlisted: true} would be describing a deployment
     * whose egress policy had been evaluated against a provider it does not have.</p>
     */
    public String toJson() {
        boolean configured;
        boolean allowlisted;
        boolean insecureRefused;
        boolean signedIn;
        // The fifth field, and the only one whose false reading is the compatibility default rather
        // than the fail-closed one. It is named for the state that requires a connection, so a body
        // that omits it -- including an API-key deployment -- reads "no connection expected". The
        // obvious
        // alternative name, `linked`, inverts that: every operator-key deployment would read
        // `linked: false` and go inert behind a control that resolves nothing. See `inertReason()` in
        // `assistant-session.js` for the client's half of the same argument.
        boolean linkRequired;
        if (reason == null) {
            configured = true;
            allowlisted = true;
            insecureRefused = false;
            signedIn = true;
            linkRequired = false;
        } else {
            linkRequired = false;
            insecureRefused = false;
            switch (reason) {
                case SERVICE_UNAVAILABLE, NO_PROFILE -> {
                    configured = false;
                    allowlisted = false;
                    signedIn = false;
                }
                case INSECURE_REFUSED -> {
                    configured = true;
                    insecureRefused = true;
                    allowlisted = false;
                    signedIn = false;
                }
                case HOST_NOT_ALLOWLISTED -> {
                    configured = true;
                    allowlisted = false;
                    signedIn = false;
                }
                case NOT_SIGNED_IN -> {
                    configured = true;
                    allowlisted = true;
                    signedIn = false;
                }
                case NOT_LINKED -> {
                    // Everything the deployment owns is in place AND the author is authenticated to
                    // Ravenroot: `signedIn` is true here, which is exactly what distinguishes this
                    // from the reason above and what stops `inertReason()` returning that one.
                    configured = true;
                    allowlisted = true;
                    signedIn = true;
                    linkRequired = true;
                }
                default -> throw new IllegalStateException("unhandled inert reason: " + reason);
            }
        }
        // `provider` is a display name only. assistant-client.js renders it as escaped text and never
        // builds a URL from it -- `assistantUrl` would refuse one anyway -- so a provider id cannot
        // become a destination even if this field were attacker-influenced, which it is not: it comes
        // from RAVENROOT_ASSISTANT_PROVIDER and from nowhere else.
        return "{\"configured\":" + configured
                + ",\"allowlisted\":" + allowlisted
                + ",\"insecureRefused\":" + insecureRefused
                + ",\"signedIn\":" + signedIn
                + ",\"linkRequired\":" + linkRequired
                + ",\"provider\":" + (provider == null ? "null" : "\"" + JsonStrings.escape(provider) + "\"")
                + ",\"detail\":\"" + JsonStrings.escape(detail == null ? "" : detail) + "\"}";
    }
}
