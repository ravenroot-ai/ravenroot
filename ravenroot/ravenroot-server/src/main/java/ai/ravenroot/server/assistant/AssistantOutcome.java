package ai.ravenroot.server.assistant;

import ai.ravenroot.server.audit.JsonStrings;

/**
 * Everything one author turn can produce (ADR 0025). A sealed set, so that handling it is an
 * exhaustive switch and adding an outcome without deciding what the panel does with it fails to
 * compile.
 *
 * <h2>The rule this type exists to make unbreakable</h2>
 * <p><b>A turn is words, an inert typed proposal, or a named failure.</b> No member carries empty text;
 * a proposal carries a non-blank human summary and still requires browser-side confirmation.
 * {@code assistant-client.js} already refuses to fabricate on the HTTP
 * side — a 2xx it cannot parse throws rather than becoming an {@code ASSISTANT} turn with empty text
 * (its lines 171-179) — and this type is that guard's twin at the provider boundary. Without it the
 * service itself becomes the thing putting words in the model's mouth: a provider response we cannot
 * read would arrive as {@code Reply("")}, the panel would render an empty assistant turn, and the
 * defect would be identical to echoing while wearing a quieter costume.</p>
 *
 * <h2>Truncation is a reply, not a failure</h2>
 * <p>{@link Reply#truncated()} exists because pooling a truncated turn into the failure tier throws
 * away an answer the author can use. The model stopped at the output ceiling with real text already
 * written; the honest report is that text plus a visible notice saying it was cut off, which is also
 * what ADR 0025's failure table says about context overflow — "explicit, shown truncation, never
 * silent". A truncated reply with empty text is still a failure, and {@link Reply}'s constructor
 * enforces that rather than trusting callers.</p>
 */
public sealed interface AssistantOutcome permits AssistantOutcome.Reply, AssistantOutcome.Proposal,
        AssistantOutcome.Failure {

    /**
     * The provider's own words.
     *
     * @param text      never blank — see {@link AssistantOutcome}'s Javadoc for why blank is a failure
     * @param model     the model that actually produced the text, as the provider reported it. Read
     *                  from the response rather than echoed from configuration, so a provider that
     *                  served the turn on a different model says so.
     * @param truncated whether the provider stopped at the output ceiling with more to say
     */
    record Reply(String text, String model, boolean truncated) implements AssistantOutcome {
        public Reply {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "an empty reply is a failure, not a reply: construct a Failure instead");
            }
        }

        /** The author-facing text, carrying the truncation notice when there is one to carry. */
        public String displayText() {
            return truncated
                    ? text + "\n\n[The model reached this deployment's output limit; the answer above "
                            + "is incomplete.]"
                    : text;
        }

        public String toJson() {
            return "{\"text\":\"" + JsonStrings.escape(displayText()) + "\""
                    + ",\"model\":" + (model == null ? "null" : "\"" + JsonStrings.escape(model) + "\"")
                    + ",\"truncated\":" + truncated + "}";
        }
    }

    /** Inert structured data awaiting a separate browser-side human confirmation. */
    record Proposal(AssistantGraphProposal proposal) implements AssistantOutcome {
        public Proposal {
            java.util.Objects.requireNonNull(proposal, "proposal");
        }

        public String toJson() {
            return proposal.toJson();
        }
    }

    /**
     * A named failure. The name is the deliverable: a panel that says "the assistant failed" sends
     * every one of these to the same wrong person.
     */
    record Failure(Reason reason, String message) implements AssistantOutcome {
        public Failure {
            java.util.Objects.requireNonNull(reason, "reason");
            if (message == null || message.isBlank()) {
                message = reason.defaultMessage();
            }
        }
    }

    /**
     * The failure vocabulary.
     *
     * <p>Every member is reachable from a fault this service can actually meet, and each carries a
     * sentence written for the author rather than for a log. <b>None of them interpolates a provider
     * error body, a URL, a header or a credential</b> — the same discipline {@code ErrorCode} applies
     * to this codebase's other error surface, for the same reason: an error path that concatenates an
     * upstream message is how an internal detail reaches a client.</p>
     */
    enum Reason {
        /**
         * The provider's safety classifiers declined the request. Arrives as a <b>successful HTTP
         * 200</b> with {@code stop_reason: "refusal"}, which is why the adapter branches on
         * {@code stop_reason} before it touches {@code content}: code that reads {@code content[0]}
         * unconditionally breaks here, and code that branches on {@code stop_details} breaks too,
         * because that field can be null on a genuine refusal.
         */
        PROVIDER_REFUSED("ASSISTANT_PROVIDER_REFUSED",
                "The model declined to answer this request.", false),

        /**
         * A 2xx whose body this build cannot read. Deliberately not "an empty answer" — see
         * {@link AssistantOutcome}'s Javadoc. This is the provider-side twin of the guard
         * {@code assistant-client.js} already applies to our own responses.
         */
        PROVIDER_UNREADABLE("ASSISTANT_PROVIDER_UNREADABLE",
                "The model provider returned a response this build cannot read, so nothing "
                + "was answered.", true),

        /**
         * The provider rejected the request (4xx): a revoked or wrong credential, a model this
         * subscription cannot use, a malformed request. Terminal, never retried — ADR 0018 §4 makes an
         * authentication rejection terminal, and silently retrying a rejected credential is how a
         * subscription gets locked out.
         */
        PROVIDER_REJECTED("ASSISTANT_PROVIDER_REJECTED",
                "The model provider rejected this request. Check the configured profile.", false),

        /** The provider was unreachable, failed, or did not answer inside the configured bound. */
        PROVIDER_UNAVAILABLE("ASSISTANT_PROVIDER_UNAVAILABLE",
                "The model provider could not be reached.", true),

        /**
         * The destination was refused before any bytes left the JVM — the operator allowlist, the
         * SEC-10 reserved-network filter, or the resolver guard. Distinguished from
         * {@code PROVIDER_UNAVAILABLE} because recovery requires an operator egress policy, not a retry.
         */
        EGRESS_REFUSED("ASSISTANT_EGRESS_REFUSED",
                "This deployment's outbound policy refused the provider destination.", false),

        /**
         * The model kept asking for tools past the configured iteration bound. The assistant is
         * read-only so a loop damages nothing, but it spends the author's subscription, and stopping
         * silently would leave the panel with a turn that simply never answered.
         */
        TOOL_LOOP_EXHAUSTED("ASSISTANT_TOOL_LOOP_EXHAUSTED",
                "The model kept requesting context without reaching an answer, so the turn "
                + "was stopped.", false),

        /** The author's own request was not something this service can act on. */
        INVALID_TURN("ASSISTANT_INVALID_TURN",
                "The message could not be read.", false),

        /**
         * The request reached the model, but its graph proposal stayed invalid after the bounded
         * same-turn correction path. This must not share {@link #INVALID_TURN}: that older reason
         * means the author's input was rejected before provider egress.
         */
        MODEL_PROPOSAL_INVALID("ASSISTANT_MODEL_PROPOSAL_INVALID",
                "The model returned an invalid graph proposal after the request was sent. Nothing "
                        + "was applied.", false),

        /**
         * An unchecked exception escaped this build's own provider adapter — not a
         * fault the provider reported, and not one of the seven shapes above that this adapter already
         * knows how to name. {@code writeRequest}, the transport call and {@code readTurn} are each
         * capable of throwing something none of the specific catches above was written for, and every
         * one of those is <em>our</em> defect rather than the provider's.
         *
         * <p><b>Deliberately not {@code UNCLASSIFIED}.</b> That name was proposed and rejected: it
         * belongs to a provider whose failures arrive genuinely undistinguished, which is a different
         * shape of ignorance than this one. Here the failure is fully classified — an adapter bug,
         * known precisely as such — so calling it unclassified would assert an ignorance this build does
         * not have, and would spend the one honest name the impoverished-provider case needs if it ever
         * arrives.</p>
         *
         * <p>The cause is never interpolated into {@link #defaultMessage()}: it is retained on the
         * {@code AssistantProviderException} for the server log only, the same discipline every other
         * reason here already applies to a provider's own response body.</p>
         */
        ADAPTER_DEFECT("ASSISTANT_ADAPTER_DEFECT",
                "Ravenroot's assistant connection failed because of a defect in Ravenroot itself, not "
                + "in the provider. This needs a fix in Ravenroot -- please report it.", false);

        private final String wireToken;
        private final String defaultMessage;
        private final boolean retryable;

        Reason(String wireToken, String defaultMessage, boolean retryable) {
            this.wireToken = wireToken;
            this.defaultMessage = defaultMessage;
            this.retryable = retryable;
        }

        /**
         * The stable token this reason crosses the wire as, in {@code ErrorEnvelope.assistantReason}.
         *
         * <h4>Why a constructor argument and not a method with a switch</h4>
         * <p><b>Totality is enforced by the compiler.</b> A constructor argument cannot be omitted, so
         * adding a reason without deciding its token is a compile error, not a runtime surprise and not
         * a default that silently reuses someone else's name. A {@code switch} with a {@code default}
         * arm — the obvious alternative — is exactly how the previous mapping collapsed seven reasons
         * onto four {@code ErrorCode}s: the arm that catches everything catches new members too, and
         * nothing fails.</p>
         *
         * <p><b>Injectivity is enforced by a test</b> ({@code AssistantReasonTokenTest}), because the
         * compiler cannot see that two constants were given the same string. That test also pins the
         * grammar: an upper-case token that survives {@code ErrorEnvelope}'s own {@code safeToken}, so
         * the field cannot become a channel for configuration, credential or provider-response detail.
         * It is a reason, not a diagnostic.</p>
         *
         * <p>The token is prefixed {@code ASSISTANT_} so that a reader seeing one in a log or a
         * response never has to work out which vocabulary it belongs to — it is deliberately not
         * confusable with an {@link ai.ravenroot.api.error.ErrorCode} name.</p>
         */
        public String wireToken() {
            return wireToken;
        }

        public String defaultMessage() {
            return defaultMessage;
        }

        /**
         * Whether the identical request is worth sending again unchanged.
         *
         * <p>A constructor argument rather than a derived guess, for the same reason {@code wireToken}
         * is one: whether retrying is worth it is the author's actual next decision, it differs per
         * reason, and a new reason added without deciding it should not compile. Structural rather than
         * prose-only, so a caller can act on it rather than parse {@link #defaultMessage()} for a
         * sentence like "will not help". Only the transport-shaped reasons — a response this build
         * could not read or a provider that could not be reached — are {@code true}; everything else
         * (a policy refusal, a rejection, a loop, this build's own defect) would meet the same fate
         * again unchanged.
         */
        public boolean retryable() {
            return retryable;
        }
    }
}
