package ai.ravenroot.api.persistence;

import java.util.UUID;

/** Generation-fenced lifecycle transition for a durable human task. */
public sealed interface HumanTaskTransition {
    /**
     * Returns the target task identity.
     *
     * @return target task identity.
     */
    UUID taskId();
    /**
     * Returns the generation fence.
     *
     * @return generation that must still be current.
     */
    long expectedGeneration();
    /**
     * Returns the requested successor status.
     *
     * @return requested successor status.
     */
    HumanTaskStatus next();
    /**
     * Returns the transition actor.
     *
     * @return bounded responder identity, or an empty string for timer transitions.
     */
    String actor();

    /**
     * Returns the separately persisted decision comment.
     *
     * @return normalized decision comment, or an empty string when no comment was supplied.
     */
    default String comment() { return ""; }

    /**
     * Durable escalation transition.
     *
     * @param taskId target task identity.
     * @param expectedGeneration generation that must still be current.
     */
    record Escalated(UUID taskId, long expectedGeneration) implements HumanTaskTransition {
        /** Validates the generation fence. */
        public Escalated { require(taskId, expectedGeneration); }
        /** {@inheritDoc} */
        @Override public HumanTaskStatus next() { return HumanTaskStatus.ESCALATED; }
        /** {@inheritDoc} */
        @Override public String actor() { return ""; }
    }

    /**
     * Successful response transition.
     *
     * @param taskId target task identity.
     * @param expectedGeneration generation that must still be current.
     * @param actor authorized responder identity.
     * @param comment separately persisted decision metadata.
     */
    record Resolved(UUID taskId, long expectedGeneration, String actor, String comment)
            implements HumanTaskTransition {
        /** Validates the generation fence and responder identity. */
        public Resolved {
            require(taskId, expectedGeneration);
            actor = requireActor(actor);
            comment = requireComment(comment);
        }
        /**
         * Compatibility constructor without a decision comment.
         * @param taskId target task identity
         * @param expectedGeneration generation that must still be current
         * @param actor authorized responder identity
         */
        public Resolved(UUID taskId, long expectedGeneration, String actor) {
            this(taskId, expectedGeneration, actor, "");
        }
        /** {@inheritDoc} */
        @Override public HumanTaskStatus next() { return HumanTaskStatus.RESOLVED; }
    }

    /**
     * Authorized denial transition.
     *
     * @param taskId target task identity.
     * @param expectedGeneration generation that must still be current.
     * @param actor authorized responder identity.
     * @param comment separately persisted decision metadata.
     */
    record Denied(UUID taskId, long expectedGeneration, String actor, String comment)
            implements HumanTaskTransition {
        /** Validates the generation fence and responder identity. */
        public Denied {
            require(taskId, expectedGeneration);
            actor = requireActor(actor);
            comment = requireComment(comment);
        }
        /**
         * Compatibility constructor without a decision comment.
         * @param taskId target task identity
         * @param expectedGeneration generation that must still be current
         * @param actor authorized responder identity
         */
        public Denied(UUID taskId, long expectedGeneration, String actor) {
            this(taskId, expectedGeneration, actor, "");
        }
        /** {@inheritDoc} */
        @Override public HumanTaskStatus next() { return HumanTaskStatus.DENIED; }
    }

    /**
     * Durable expiry transition.
     *
     * @param taskId target task identity.
     * @param expectedGeneration generation that must still be current.
     */
    record Expired(UUID taskId, long expectedGeneration) implements HumanTaskTransition {
        /** Validates the generation fence. */
        public Expired { require(taskId, expectedGeneration); }
        /** {@inheritDoc} */
        @Override public HumanTaskStatus next() { return HumanTaskStatus.EXPIRED; }
        /** {@inheritDoc} */
        @Override public String actor() { return ""; }
    }

    /**
     * Authorized cancellation transition.
     *
     * @param taskId target task identity.
     * @param expectedGeneration generation that must still be current.
     * @param actor authorized requester or responder identity.
     * @param comment separately persisted decision metadata.
     */
    record Cancelled(UUID taskId, long expectedGeneration, String actor, String comment)
            implements HumanTaskTransition {
        /** Validates the generation fence and actor identity. */
        public Cancelled {
            require(taskId, expectedGeneration);
            actor = requireActor(actor);
            comment = requireComment(comment);
        }
        /**
         * Compatibility constructor without a decision comment.
         * @param taskId target task identity
         * @param expectedGeneration generation that must still be current
         * @param actor authorized requester or responder identity
         */
        public Cancelled(UUID taskId, long expectedGeneration, String actor) {
            this(taskId, expectedGeneration, actor, "");
        }
        /** {@inheritDoc} */
        @Override public HumanTaskStatus next() { return HumanTaskStatus.CANCELLED; }
    }

    private static void require(UUID taskId, long generation) {
        if (taskId == null) throw new IllegalArgumentException("taskId cannot be null");
        if (generation < 1) throw new IllegalArgumentException("expectedGeneration must be positive");
    }

    private static String requireActor(String actor) {
        return HandlerRegistration.requireBoundedKey(actor, "actor");
    }

    private static String requireComment(String comment) {
        return comment == null ? "" : comment;
    }
}
