package ai.ravenroot.api.persistence;

import ai.ravenroot.api.security.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable operator hold on one traversal, created inside a batch together with the transitions
 * that move the traversal to {@link ai.ravenroot.api.application.TraversalStatus#WAITING}.
 *
 * <h2>Why this carries a continuation when {@link HandlerRegistration} refuses to</h2>
 * <p>A handler records <em>that</em> something is waiting and deliberately carries no continuation,
 * because the thing it waits for arrives from outside. A hold is the opposite: nothing arrives. The
 * traversal stopped between two nodes of its own accord and the only way to continue it is to know
 * which node it had not yet entered and with what. That knowledge exists nowhere else once the
 * process that held it is gone, so it is stored here — bounded, versioned and digested, exactly as
 * {@link ToolApprovalRegistration} stores the continuation of an approved tool call.</p>
 *
 * <h2>The boundary this describes, and why it cannot repeat an effect</h2>
 * <p>{@code nodeId} is the node the traversal was <strong>about to enter</strong> and never did. The
 * hold is taken before the invocation and attempt identities are minted, so no invocation exists for
 * it, no attempt was recorded and no behaviour ran. Continuing from here therefore starts a node for
 * the first time rather than repeating one — the "without repeating a completed effect" property is
 * a consequence of where the boundary is, not a promise made about it.</p>
 *
 * <p>{@code afterInvocationId} is the completed invocation the hold sits behind. It is the anchor the
 * paired {@link HandlerRegistration} names, because a handler must name an invocation that exists in
 * the traversal and the only one that does here is the predecessor's.</p>
 *
 * @param pauseId           stable identity of this hold, also the identity of the paired handler and
 *                          therefore the {@link PendingWork#workItemId()} of the trigger a resume
 *                          produces
 * @param traversalId       the traversal being held; must exist in the post-fold aggregate
 * @param afterInvocationId the completed invocation the hold sits behind; must exist in that traversal
 * @param nodeId            the node the held traversal had not yet entered
 * @param commandDirective  the structural directive the withheld dispatch carried
 * @param commandName       the command name the withheld dispatch carried
 * @param requester         the principal whose request the held traversal is running as; the resumed
 *                          traversal runs as this principal and never as whoever resumed it
 * @param graphVersionPin   the immutable graph bytes the resumed traversal must be routed against
 * @param continuationVersion positive schema version of {@code continuation}, so a build that does
 *                          not recognise it refuses to resume rather than guessing
 * @param continuation      canonical encoding of the withheld payload and attributes
 * @param continuationDigest lower-case {@code sha256:} digest of {@code continuation}
 */
public record ExecutionPauseRegistration(UUID pauseId, UUID traversalId, UUID afterInvocationId,
                                         String nodeId, String commandDirective, String commandName,
                                         SecurityContext requester, GraphVersionPin graphVersionPin,
                                         int continuationVersion, byte[] continuation,
                                         String continuationDigest) {

    /** Inclusive bound on the stored continuation, in bytes. */
    public static final int MAX_CONTINUATION_BYTES = 256 * 1024;

    /** Validates and snapshots the stored hold before an adapter can be asked to write it. */
    public ExecutionPauseRegistration {
        Objects.requireNonNull(pauseId, "pauseId");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(afterInvocationId, "afterInvocationId");
        nodeId = HandlerRegistration.requireBoundedKey(nodeId, "nodeId");
        commandDirective = HandlerRegistration.requireBoundedKey(commandDirective, "commandDirective");
        commandName = HandlerRegistration.requireBoundedKey(commandName, "commandName");
        requester = Objects.requireNonNull(requester, "requester");
        graphVersionPin = Objects.requireNonNull(graphVersionPin, "graphVersionPin");
        if (continuationVersion < 1) {
            throw new IllegalArgumentException("continuationVersion must be positive");
        }
        Objects.requireNonNull(continuation, "continuation");
        if (continuation.length > MAX_CONTINUATION_BYTES) {
            throw new IllegalArgumentException("continuation exceeds " + MAX_CONTINUATION_BYTES
                    + " bytes: " + continuation.length);
        }
        continuation = continuation.clone();
        continuationDigest = HandlerRegistration.requireBoundedKey(continuationDigest,
                "continuationDigest");
        if (!continuationDigest.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("continuationDigest must be a lower-case SHA-256 digest");
        }
        if (!continuationDigest.equals(digest(continuation))) {
            throw new IllegalArgumentException("continuationDigest does not match continuation");
        }
    }

    /**
     * Returns a copy of the stored continuation.
     *
     * @return the canonical continuation bytes.
     */
    @Override
    public byte[] continuation() {
        return continuation.clone();
    }

    /**
     * Computes the canonical digest an adapter and a caller must both agree on.
     *
     * @param bytes content to digest.
     * @return lower-case {@code sha256:} digest of {@code bytes}.
     */
    public static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            var text = new StringBuilder("sha256:");
            for (byte value : hash) {
                text.append(Character.forDigit((value >> 4) & 0xF, 16));
                text.append(Character.forDigit(value & 0xF, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every supported runtime", impossible);
        }
    }

    /**
     * Digests text content, for callers that hold a canonical string rather than bytes.
     *
     * @param text content to digest as UTF-8.
     * @return lower-case {@code sha256:} digest of the UTF-8 encoding of {@code text}.
     */
    public static String digest(String text) {
        return digest(text.getBytes(StandardCharsets.UTF_8));
    }
}
