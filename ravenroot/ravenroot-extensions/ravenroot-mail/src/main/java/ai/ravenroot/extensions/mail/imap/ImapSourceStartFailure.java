package ai.ravenroot.extensions.mail.imap;

import ai.ravenroot.api.deployment.SourceStartFailureCode;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Compile-time-closed IMAP source-start failure vocabulary. */
enum ImapSourceStartFailure implements SourceStartFailureCode {
    CONTENT_PREVIEW_FORBIDDEN("content-preview-forbidden"),
    CREDENTIAL_RESOLVER_BUSY("credential-resolver-busy"),
    CREDENTIAL_UNAVAILABLE("credential-unavailable"),
    DURABLE_INGRESS_LOST("durable-ingress-lost"),
    DURABLE_INGRESS_REQUIRED("durable-ingress-required"),
    IMAP_CHECKPOINT_CONFLICT("imap-checkpoint-conflict"),
    IMAP_CHECKPOINT_INVALID("imap-checkpoint-invalid"),
    IMAP_CONSUMER_ALREADY_ACTIVE("imap-consumer-already-active"),
    IMAP_CONSUMER_FAILED("imap-consumer-failed"),
    IMAP_CONSUMER_OWNERSHIP_UNAVAILABLE("imap-consumer-ownership-unavailable"),
    IMAP_CONSUMER_POLICY_UNAVAILABLE("imap-consumer-policy-unavailable"),
    IMAP_FOLDER_NOT_AUTHORIZED("imap-folder-not-authorized"),
    IMAP_FOLDER_NOT_CANONICAL("imap-folder-not-canonical"),
    IMAP_HEADERS_NOT_AUTHORIZED("imap-headers-not-authorized"),
    IMAP_MESSAGE_POISON_HALTED("imap-message-poison-halted"),
    IMAP_MESSAGE_PROJECTION_HALTED("imap-message-projection-halted"),
    IMAP_PROFILE_UNAVAILABLE("imap-profile-unavailable"),
    IMAP_UIDVALIDITY_CHANGED("imap-uidvalidity-changed"),
    IMAP_UIDVALIDITY_INVALID("imap-uidvalidity-invalid"),
    INVALID_ALLOWED_HEADERS("invalid-allowed-headers"),
    INVALID_BATCH_SIZE("invalid-batch-size"),
    INVALID_CHECKPOINT_POLICY("invalid-checkpoint-policy"),
    INVALID_CONSUMER_ID("invalid-consumer-id"),
    INVALID_CONTENT_MODE("invalid-content-mode"),
    INVALID_INITIAL_POSITION("invalid-initial-position"),
    INVALID_MAX_IN_FLIGHT("invalid-max-in-flight"),
    INVALID_MAX_RETRY_BACKOFF("invalid-max-retry-backoff"),
    INVALID_POISON_ATTEMPTS("invalid-poison-attempts"),
    INVALID_POLL_INTERVAL("invalid-poll-interval"),
    INVALID_PREVIEW_CHARS("invalid-preview-chars"),
    INVALID_RETRY_BACKOFF("invalid-retry-backoff"),
    STARTUP_CANCELLED("startup-cancelled"),
    UNKNOWN_GRAPH_PROPERTY("unknown-graph-property");

    private final String code;
    ImapSourceStartFailure(String code) { this.code = SourceStartFailureCode.requireValid(code); }
    @Override public String code() { return code; }
    static Set<String> codes() {
        return Arrays.stream(values()).map(ImapSourceStartFailure::code)
                .collect(Collectors.toUnmodifiableSet());
    }
}
