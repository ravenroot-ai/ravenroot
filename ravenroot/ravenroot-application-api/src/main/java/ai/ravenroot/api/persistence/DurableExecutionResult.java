package ai.ravenroot.api.persistence;

import ai.ravenroot.api.application.ExecutionTerminationReason;
import ai.ravenroot.api.application.ProcessInstanceStatus;
import ai.ravenroot.api.application.RuntimeActivityData;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The canonical result of one terminal execution, in the form a store can keep.
 *
 * <p>{@code ExecutionOutcome} is the same fact as a value passed inside one JVM, and it cannot be
 * this: its payload is an {@code Object}, and {@link OpaquePayload} states plainly why that is
 * unstorable — no remote adapter can persist an arbitrary JVM object, and Java serialization would be
 * a security defect. Converting that {@code Object} at this boundary, once, is what this type is
 * for, and {@link ResultPayloadState} is what stops the conversion's failure modes from arriving at a
 * reader as an indistinguishable absence.</p>
 *
 * <h2>Identity, and what "the same result" means</h2>
 * <p>A terminal execution is recorded once. A re-delivery of the same terminal event must be a
 * no-op and a genuinely different outcome for the same traversal must be refused, so the two have to
 * be told apart by something more reliable than field-by-field equality of collections whose
 * iteration order is unspecified. {@link #fingerprint()} is that something: a digest over every
 * component the producer decides, with the node sets already ordered by
 * {@link ExecutionResultNodes} and the payload contributing its stored bytes.</p>
 *
 * <p><strong>{@link #retainedUntil()} is excluded from the fingerprint</strong>, and it is the only
 * exclusion. It is assigned by the store from its own clock, so a retry arriving a second later
 * would otherwise carry a different deadline and be refused as a conflicting outcome — turning the
 * idempotency guarantee into its opposite for the one case it exists to serve.</p>
 *
 * @param key               tenant-scoped identity of the process instance that contained the
 *                          traversal
 * @param traversalId       the caller-facing execution id, unique within the tenant and the address
 *                          this record is read by
 * @param graphVersionPin   the immutable definition the execution ran against
 * @param status            the terminal lifecycle status reached; never a non-terminal one.
 *                          <b>A cancelled execution reports {@code FAILED}</b> and is distinguished
 *                          only by {@code terminationReason}
 * @param terminationReason why that status was reached, or {@code null} when nothing distinguishes
 *                          it. See {@link ExecutionTerminationReason}
 * @param startedAt         when the traversal began, on the producing runtime's clock
 * @param endedAt           when it terminated, on the same clock; never before {@code startedAt},
 *                          and stable across a re-delivery of the same terminal event
 * @param retainedUntil     when this record becomes eligible for purge, assigned by the store.
 *                          {@code null} in a record a caller builds for
 *                          {@link ExecutionStore#recordExecutionResult(DurableExecutionResult)}, and
 *                          always present in one a store returns
 * @param payload           what became of the execution's output; see {@link ExecutionResultPayload}
 * @param failureClassifier a closed or source-authored name for the terminal failure, or
 *                          {@code null}. See {@link #failureClassifier()} for why this is never a
 *                          message
 * @param nodes             the ordered, bounded traversal detail
 */
public record DurableExecutionResult(ExecutionKey key, UUID traversalId,
                                     GraphVersionPin graphVersionPin, ProcessInstanceStatus status,
                                     ExecutionTerminationReason terminationReason, Instant startedAt,
                                     Instant endedAt, Instant retainedUntil,
                                     ExecutionResultPayload payload, String failureClassifier,
                                     ExecutionResultNodes nodes) {

    /** Largest accepted failure classifier, in characters. A vocabulary term, not a narrative. */
    public static final int MAX_FAILURE_CLASSIFIER_LENGTH = 255;

    /** Media type of the encoded payload projection this type produces. */
    public static final String PAYLOAD_CONTENT_TYPE = "application/json";

    /**
     * Refuses every record a store must never be asked to keep: a non-terminal status, an end before
     * a start, and a failure classifier that could carry runtime text.
     */
    public DurableExecutionResult {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(traversalId, "traversalId");
        Objects.requireNonNull(graphVersionPin, "graphVersionPin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(endedAt, "endedAt");
        Objects.requireNonNull(payload, "payload");
        nodes = nodes == null ? ExecutionResultNodes.empty() : nodes;
        if (!status.terminal()) {
            throw new IllegalArgumentException(
                    "a durable execution result records a terminal execution; " + status + " is not one");
        }
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt " + endedAt + " precedes startedAt " + startedAt);
        }
        failureClassifier = requireVocabularyTerm(failureClassifier);
    }

    /**
     * Projects one terminal execution onto its durable form, performing the payload conversion in the
     * one place every producer shares.
     *
     * <h4>What happens to the payload</h4>
     * <p>It is projected onto the closed payload model through
     * {@link RuntimeActivityData#output(Object)} — the same projection the author-facing diagnostics
     * already use, deliberately rather than a second one — and the projection is encoded as JSON.
     * Four outcomes are possible and each is reported as a distinct
     * {@link ResultPayloadState}: nothing was produced, the encoding fits and is stored, the encoding
     * exceeds {@code maxPayloadBytes} and none of it is stored, or the value does not project onto
     * the model at all. Recognised credential material is replaced and an over-long projection is
     * shortened before any of that, and both are reported as flags on a payload that is present
     * rather than as a refusal.</p>
     *
     * <h4>What happens to the failure</h4>
     * <p>Only {@code failure.getClass().getName()} is kept. {@code getMessage()} is never read here
     * and must not be read by any caller before calling: a class name is authored in source and
     * cannot contain a fragment of the payload, while a message is assembled at runtime from the
     * values that caused the failure and routinely does.</p>
     *
     * @param key             tenant-scoped identity of the containing process instance.
     * @param traversalId     the caller-facing execution id.
     * @param graphVersionPin the immutable definition the execution ran against.
     * @param status          the terminal status reached.
     * @param reason          why it was reached, or {@code null}.
     * @param startedAt       when the traversal began.
     * @param endedAt         when it terminated.
     * @param payload         the execution's output, or {@code null} when it produced none.
     * @param nodes           the traversal detail, or {@code null} for none.
     * @param failure         the terminal failure, or {@code null}; only its type is retained.
     * @param maxPayloadBytes the adapter's published cap on a stored result payload.
     * @return the durable record, with {@link #retainedUntil()} left for the store to assign.
     */
    public static DurableExecutionResult of(ExecutionKey key, UUID traversalId,
                                            GraphVersionPin graphVersionPin,
                                            ProcessInstanceStatus status,
                                            ExecutionTerminationReason reason, Instant startedAt,
                                            Instant endedAt, Object payload,
                                            ExecutionResultNodes nodes, Throwable failure,
                                            int maxPayloadBytes) {
        return new DurableExecutionResult(key, traversalId, graphVersionPin, status, reason, startedAt,
                endedAt, null, project(payload, maxPayloadBytes),
                failure == null ? null : failure.getClass().getName(),
                nodes == null ? ExecutionResultNodes.empty() : nodes);
    }

    /**
     * The same record, for a producer that has already decided what became of the payload.
     *
     * <p>{@link #of(ExecutionKey, UUID, GraphVersionPin, ProcessInstanceStatus,
     * ExecutionTerminationReason, Instant, Instant, Object, ExecutionResultNodes, Throwable, int)}
     * takes the execution's output and projects it, which is the right shape whenever an output
     * exists to project. It is the wrong shape for a traversal that terminated <em>on</em> its
     * payload: there is no value left to hand over, and passing {@code null} there would record
     * {@link ResultPayloadState#NONE} — the positive claim that the run produced nothing. The caller
     * that holds the rejection builds the payload state with
     * {@link ExecutionResultPayload#refused(ai.ravenroot.api.payload.PayloadException.Reason)} and
     * passes it here.</p>
     *
     * <p>The failure is treated exactly as the projecting factory treats it: only its type is kept,
     * never its message, for the reason stated there.</p>
     *
     * @param key             tenant-scoped identity of the containing process instance.
     * @param traversalId     the caller-facing execution id.
     * @param graphVersionPin the immutable definition the execution ran against.
     * @param status          the terminal status reached.
     * @param reason          why it was reached, or {@code null}.
     * @param startedAt       when the traversal began.
     * @param endedAt         when it terminated.
     * @param payload         what became of the execution's output, already decided.
     * @param nodes           the traversal detail, or {@code null} for none.
     * @param failure         the terminal failure, or {@code null}; only its type is retained.
     * @return the durable record, with {@link #retainedUntil()} left for the store to assign.
     */
    public static DurableExecutionResult of(ExecutionKey key, UUID traversalId,
                                            GraphVersionPin graphVersionPin,
                                            ProcessInstanceStatus status,
                                            ExecutionTerminationReason reason, Instant startedAt,
                                            Instant endedAt, ExecutionResultPayload payload,
                                            ExecutionResultNodes nodes, Throwable failure) {
        return new DurableExecutionResult(key, traversalId, graphVersionPin, status, reason, startedAt,
                endedAt, null, Objects.requireNonNull(payload, "payload"),
                failure == null ? null : failure.getClass().getName(),
                nodes == null ? ExecutionResultNodes.empty() : nodes);
    }

    /**
     * The projection rule on its own, so an adapter or a test can exercise the payload boundary
     * without building a whole record.
     *
     * @param payload         the execution's output, or {@code null} when it produced none.
     * @param maxPayloadBytes the adapter's published cap on a stored result payload.
     * @return what became of that payload, and its bytes when they were kept.
     */
    public static ExecutionResultPayload project(Object payload, int maxPayloadBytes) {
        if (maxPayloadBytes < 1) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        if (payload == null) {
            return ExecutionResultPayload.none();
        }
        RuntimeActivityData.OutputProjection projection = RuntimeActivityData.output(payload);
        if (unconvertible(payload, projection.value())) {
            return ExecutionResultPayload.unconvertible();
        }
        byte[] encoded = PayloadJson.write(projection.value()).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxPayloadBytes) {
            return ExecutionResultPayload.withheld(encoded.length, PAYLOAD_CONTENT_TYPE);
        }
        return ExecutionResultPayload.retained(OpaquePayload.of(encoded, PAYLOAD_CONTENT_TYPE),
                projection.redacted(), projection.truncated());
    }

    /**
     * The projection reports an unrepresentable value by returning the marker text rather than by
     * failing, so the marker at the root <em>is</em> the signal. A payload that is itself that exact
     * string is excluded, because then the marker is the data and the conversion succeeded.
     */
    private static boolean unconvertible(Object payload, PayloadValue projected) {
        return !(payload instanceof CharSequence)
                && projected instanceof PayloadValue.TextValue text
                && UNSUPPORTED_TYPE_MARKER.equals(text.value());
    }

    /**
     * The exact text {@code RuntimeActivityData} substitutes for a value the closed model cannot
     * represent. Pinned by {@code DurableExecutionResultTest} rather than assumed, because it is a
     * private detail of that class and a change to it would otherwise turn every unconvertible
     * payload into a stored string that looks like a result.
     */
    private static final String UNSUPPORTED_TYPE_MARKER = "[ravenroot:truncated:unsupported-type]";

    /**
     * The same record with the store's retention deadline applied.
     *
     * @param deadline when this record becomes eligible for purge.
     * @return a copy carrying that deadline.
     */
    public DurableExecutionResult withRetainedUntil(Instant deadline) {
        return new DurableExecutionResult(key, traversalId, graphVersionPin, status, terminationReason,
                startedAt, endedAt, Objects.requireNonNull(deadline, "deadline"), payload,
                failureClassifier, nodes);
    }

    /**
     * The same record with its payload aged out, for a read past the retention deadline.
     *
     * @return this record when it holds no bytes, and otherwise a copy whose payload reports
     *         {@link ResultPayloadState#EXPIRED}.
     */
    public DurableExecutionResult expired() {
        ExecutionResultPayload aged = payload.expired();
        if (aged == payload) {
            return this;
        }
        return new DurableExecutionResult(key, traversalId, graphVersionPin, status, terminationReason,
                startedAt, endedAt, retainedUntil, aged, failureClassifier, nodes);
    }

    /**
     * Whether this record's termination is a cancellation rather than a fault.
     *
     * <p>Present for the reason every type in this chain repeats: the status of a cancelled execution
     * is {@code FAILED}, so a caller branching on {@link #status()} alone turns every deliberate stop
     * into an incident.</p>
     *
     * @return whether the recorded termination reason is a cancellation.
     */
    public boolean cancelled() {
        return ExecutionTerminationReason.isCancellation(terminationReason);
    }

    /**
     * A closed or source-authored name for the terminal failure, never a runtime message.
     *
     * <p>The rule this codebase states wherever a failure crosses a boundary: publish a classifier
     * drawn from a closed vocabulary or written in source, because a class name cannot contain a
     * fragment of the payload while an exception message is assembled from the values that caused the
     * failure and routinely does. The canonical constructor enforces the shape — bounded length, no
     * whitespace, no control characters — which is a structural guard rather than a proof, and it is
     * the reason {@link #of} reads only the failure's type.</p>
     *
     * @return the classifier, or {@code null} when the termination carries none.
     */
    public String failureClassifier() {
        return failureClassifier;
    }

    /**
     * A stable digest of everything the producer decided, and the comparison that separates a
     * duplicate terminal event from a conflicting one.
     *
     * <p>Every component except {@link #retainedUntil()} contributes, each length-prefixed so no
     * concatenation of two fields can be mistaken for another pair. The node sets contribute in
     * their stored order, which {@link ExecutionResultNodes} has already made deterministic, and the
     * payload contributes its stored bytes rather than its identity.</p>
     *
     * @return the lowercase hexadecimal SHA-256 of this record's canonical form.
     */
    public String fingerprint() {
        var canonical = new StringBuilder();
        append(canonical, key.tenantId());
        append(canonical, key.processInstanceId().toString());
        append(canonical, traversalId.toString());
        append(canonical, graphVersionPin.reference());
        append(canonical, status.name());
        append(canonical, terminationReason == null ? null : terminationReason.name());
        append(canonical, startedAt.toString());
        append(canonical, endedAt.toString());
        append(canonical, payload.state().name());
        append(canonical, Boolean.toString(payload.redacted()));
        append(canonical, Boolean.toString(payload.truncated()));
        append(canonical, Integer.toString(payload.bytes()));
        append(canonical, payload.contentType());
        append(canonical, payload.retained() == null ? null
                : HexFormat.of().formatHex(payload.retained().bytes()));
        append(canonical, failureClassifier);
        for (ExecutionResultNodes.Kind kind : ExecutionResultNodes.Kind.values()) {
            List<String> entries = nodes.entries(kind);
            append(canonical, kind.name());
            append(canonical, Integer.toString(entries.size()));
            entries.forEach(entry -> append(canonical, entry));
        }
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) {
            target.append("-|");
            return;
        }
        target.append(value.length()).append(':').append(value).append('|');
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", impossible);
        }
    }

    private static String requireVocabularyTerm(String classifier) {
        if (classifier == null) {
            return null;
        }
        String trimmed = classifier.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_FAILURE_CLASSIFIER_LENGTH) {
            throw new IllegalArgumentException("failure classifier exceeds "
                    + MAX_FAILURE_CLASSIFIER_LENGTH + " characters, so it is not a vocabulary term");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new IllegalArgumentException(
                        "failure classifier contains whitespace or a control character, so it is "
                                + "runtime text rather than a vocabulary term");
            }
        }
        return trimmed;
    }
}
