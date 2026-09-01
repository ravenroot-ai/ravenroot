package ai.ravenroot.api.error;

import ai.ravenroot.api.payload.PayloadException;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The stable machine-readable error representation returned by every Ravenroot surface (API-01).
 *
 * <h2>What it guarantees</h2>
 * <ol>
 *   <li><b>The message is a pure function of the code.</b> This type is deliberately <em>not</em> a
 *       record and has <b>no public constructor</b>. Every route to an instance is a static factory
 *       that derives the message from {@link ErrorCode}, or the one typed factory below. A caller
 *       holding an arbitrary exception has no parameter to pass {@code getMessage()} to, which is a
 *       stronger property than a rule saying not to.</li>
 *   <li><b>Every envelope carries a correlation id.</b> A client gets a handle it can quote; the
 *       server keeps the cause. That is the trade that lets the response say nothing while diagnosis
 *       stays possible.</li>
 *   <li><b>The envelope is closed, and closed in both senses.</b> Its five fields are all a client
 *       ever receives, so adding a field to an exception cannot widen the response by accident; and
 *       every field is escaped on the way out, so no field value can terminate its own JSON string
 *       and introduce a sibling member.</li>
 *   <li><b>The code is a bare token.</b> Whatever a factory supplies is forced through
 *       {@link #safeToken(String)} and replaced with {@link ErrorCode#INTERNAL_ERROR}'s token if it
 *       is not one, so {@code code} cannot carry punctuation, let alone structure.</li>
 * </ol>
 *
 * <h2>Why not a record</h2>
 * <p>Before API-01 it was a record. A {@code public record} is required by the language to expose a
 * <b>public canonical constructor</b>, so {@code new ErrorEnvelope(CONTRACT, code, message, id, null)}
 * was reachable and accepted arbitrary text in {@code message} — which is precisely what guarantee 1
 * says is impossible. The guarantee was documented in four places and enforced in none. A private
 * constructor on a plain final class is what actually makes the sentence true; the record's generated
 * accessors, {@code equals} and {@code hashCode} are reproduced by hand below because they were
 * cheap to keep and the shape is part of the published contract.</p>
 *
 * <h2>The one typed exception</h2>
 * <p>{@link #of(PayloadException, String)} reads {@code getMessage()} from a {@link PayloadException}.
 * That is not a hole: {@code PayloadException}'s constructor is package-private to the payload module
 * and only its {@code PayloadRejection} policy can build one, so its message is authored under the
 * same rule as {@link ErrorCode}'s. The type is the proof — an arbitrary {@code RuntimeException}
 * does not fit this signature.</p>
 *
 * <h2>What it does not protect against</h2>
 * <p>It bounds what an <em>error response</em> discloses. It says nothing about what a successful
 * response, an execution event or a node's own output contains: a node that copies a secret into its
 * result payload is disclosing it through a 200, and no error contract can see that.</p>
 */
public final class ErrorEnvelope {

    /** The error representation version this build implements. */
    public static final String CONTRACT = "ravenroot.error/1";

    private static final SecureRandom CORRELATIONS = new SecureRandom();
    private static final int MAX_CORRELATION_LENGTH = 128;

    private final String contract;
    private final String code;
    private final String message;
    private final String correlationId;
    private final String incidentId;
    /**
     * The authoring assistant's own reason token, when this envelope describes an assistant
     * turn. Null everywhere else.
     *
     * <p>It exists because {@code code} cannot carry it. {@code code} is coarse by design — it is the
     * status-bearing member, declared per route in {@code RouteTable} and rendered into the checked-in
     * OpenAPI document — so mapping the assistant's failure reasons onto it collapsed three of them:
     * the seven that existed at the time, onto four codes. The reason then survived only in the
     * server's own log, which is to say the panel could not tell a provider refusal from an exhausted
     * tool loop. That count is the historical one and is left as such deliberately; the vocabulary is
     * now eight, and the collapse it describes is what this member exists to have ended.</p>
     *
     * <p>Additive and optional, exactly like {@link #incidentId}: no other surface emits it, and no
     * existing reader is affected by a member it does not look for.</p>
     *
     * <p>It is constrained by {@link #safeReasonToken(String)}, <b>not</b> by
     * {@link #safeToken(String)}. That distinction is the whole control and it was wrong here until a
     * real inputs exposed it: {@code safeToken} permits {@code . - :} and lower case, so
     * {@code api.anthropic.com}, {@code sk-ant-would-work} and
     * {@code ASSISTANT_PROVIDER_REFUSED:api.anthropic.com} all survive it — only a space is dropped.
     * This field's Javadoc claimed a bare-upper-case guarantee that nothing enforced, which is a
     * documented control that did not exist. {@code safeReasonToken} enforces {@code [A-Z][A-Z0-9_]*}
     * for real, so the member cannot carry a host, a model id, a credential or a fragment of a
     * provider's error body — <b>it is a reason, not a diagnostic</b>.</p>
     */
    private final String assistantReason;

    /**
     * Private on purpose. Making this constructor visible in any form re-opens the exact hole
     * described above, so a new factory must be added here rather than the constructor widened.
     */
    private ErrorEnvelope(String contract, String code, String message, String correlationId,
                          String incidentId) {
        this(contract, code, message, correlationId, incidentId, null);
    }

    private ErrorEnvelope(String contract, String code, String message, String correlationId,
                          String incidentId, String assistantReason) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.message = Objects.requireNonNull(message, "message");
        String token = safeToken(Objects.requireNonNull(code, "code"));
        this.code = token == null ? ErrorCode.INTERNAL_ERROR.code() : token;
        this.correlationId = safeHandle(correlationId);
        this.incidentId = incidentId == null ? null : safeHandle(incidentId);
        // A reason that does not survive the grammar is dropped rather than coerced: an envelope with
        // no assistant reason is honest, and one carrying a mangled or detail-bearing token invites a
        // client to map it.
        this.assistantReason = assistantReason == null ? null : safeReasonToken(assistantReason);
    }

    /**
     * Builds a closed public envelope from one vocabulary entry.
     *
     * @param code server-authored error category that determines the public message and status
     * @param correlationId bounded handle the client can quote to an operator
     * @return envelope with no incident or assistant-specific reason
     */
    public static ErrorEnvelope of(ErrorCode code, String correlationId) {
        return new ErrorEnvelope(CONTRACT, code.code(), code.message(), correlationId, null);
    }

    /**
     * The envelope for a classified payload rejection.
     *
     * <p>The incident id comes from the rejection rather than from the request, because one request
     * may submit several payloads and a handle that cannot tell them apart is not a handle.</p>
     * @param rejection typed payload failure whose message is safe by construction
     * @param correlationId bounded handle the client can quote to an operator
     * @return envelope retaining only the rejection's public data and incident handle
     */
    public static ErrorEnvelope of(PayloadException rejection, String correlationId) {
        return new ErrorEnvelope(CONTRACT, rejection.code(), rejection.getMessage(), correlationId,
                rejection.incidentId());
    }

    /**
     * Adds a server-side incident handle without changing the public code or message.
     *
     * @param incident bounded correlation handle, normalized before it is exposed
     * @return new immutable envelope carrying the incident handle
     */
    public ErrorEnvelope withIncident(String incident) {
        return new ErrorEnvelope(contract, code, message, correlationId, incident, assistantReason);
    }

    /**
     * The same envelope, additionally naming the authoring assistant's reason.
     *
     * <p>Deliberately a wither rather than a parameter on every factory: only one route in the product
     * has an assistant reason to carry, and threading a null through {@code of(...)} at every other
     * call site would put the burden of a single feature on the whole error surface.</p>
     * @param reason assistant-owned reason token; invalid or detail-bearing values are discarded
     * @return new immutable envelope with the constrained assistant reason when valid
     */
    public ErrorEnvelope withAssistantReason(String reason) {
        return new ErrorEnvelope(contract, code, message, correlationId, incidentId, reason);
    }

    /**
     * A code supplied by a server-side component that owns its own closed vocabulary.
     *
     * <p>The rate limiter is the one such component today: its codes are constants in its own
     * configuration and are already asserted by name in its tests. The code is still forced through
     * {@link #safeToken(String)}, so a value that is not a bare upper-case token cannot become one
     * here, and the message remains {@link ErrorCode}'s rather than the component's.</p>
     * @param serverCode component-owned token, accepted only when it satisfies the envelope grammar
     * @param fallback public category providing the fixed message and fallback token
     * @param correlationId bounded handle the client can quote to an operator
     * @return envelope carrying a sanitized component token or the fallback token
     */
    public static ErrorEnvelope ofServerCode(String serverCode, ErrorCode fallback, String correlationId) {
        String token = safeToken(serverCode);
        return new ErrorEnvelope(CONTRACT, token == null ? fallback.code() : token, fallback.message(),
                correlationId, null);
    }

    /**
     * The version marker consumers use to recognize this envelope shape.
     *
     * @return error-representation contract version emitted by this envelope
     */
    public String contract() {
        return contract;
    }

    /**
     * The machine-readable category selected by the factory or its trusted component.
     *
     * @return bare machine-readable code token, never component diagnostic text
     */
    public String code() {
        return code;
    }

    /**
     * The public message paired with {@link #code()} by the closed error vocabulary.
     *
     * @return fixed public message determined by the error vocabulary
     */
    public String message() {
        return message;
    }

    /**
     * The response handle that lets an operator locate server-side diagnostics without exposing them.
     *
     * @return client-visible handle for joining the response to server-side diagnostics
     */
    public String correlationId() {
        return correlationId;
    }

    /**
     * The optional handle for the specific incident represented by a typed payload rejection.
     *
     * @return optional incident handle; {@code null} when no incident record was attached
     */
    public String incidentId() {
        return incidentId;
    }

    /**
     * Canonical JSON. {@code error} repeats {@code message} for pre-API-01 clients.
     *
     * <p>Every field is escaped, including the ones a factory already constrains. Before API-01 only
     * {@code message} was escaped, which left {@code code} able to close its own string and add a
     * sibling member — an envelope that is "closed" by field list but not by encoding is not closed.
     * The redundancy with {@link #safeToken(String)} is deliberate: the escape is what holds if a
     * future factory forgets the token grammar.</p>
     * @return compact JSON representation with every member escaped for transport
     */
    public String toJson() {
        var out = new StringBuilder(160);
        out.append("{\"contract\":\"").append(escape(contract)).append('"')
                .append(",\"code\":\"").append(escape(code)).append('"')
                .append(",\"message\":\"").append(escape(message)).append('"')
                .append(",\"error\":\"").append(escape(message)).append('"')
                .append(",\"correlationId\":\"").append(escape(correlationId)).append('"');
        if (incidentId != null) {
            out.append(",\"incidentId\":\"").append(escape(incidentId)).append('"');
        }
        if (assistantReason != null) {
            out.append(",\"assistantReason\":\"").append(escape(assistantReason)).append('"');
        }
        return out.append('}').toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ErrorEnvelope envelope
                && contract.equals(envelope.contract)
                && code.equals(envelope.code)
                && message.equals(envelope.message)
                && correlationId.equals(envelope.correlationId)
                && Objects.equals(incidentId, envelope.incidentId)
                && Objects.equals(assistantReason, envelope.assistantReason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contract, code, message, correlationId, incidentId, assistantReason);
    }

    @Override
    public String toString() {
        return "ErrorEnvelope[contract=" + contract + ", code=" + code + ", message=" + message
                + ", correlationId=" + correlationId + ", incidentId=" + incidentId
                + ", assistantReason=" + assistantReason + ']';
    }

    /**
     * The assistant reason token, or null when this envelope does not describe an assistant turn.
     *
     * @return optional constrained assistant reason; {@code null} when the envelope is not assistant-related
     */
    public String assistantReason() {
        return assistantReason;
    }

    /**
     * Accepts a correlation handle only if it is a bounded, boring token, and mints one otherwise.
     *
     * <p>This never throws. A failure to sanitise must not become a 500, because a 500 raised while
     * building an error response is exactly the path that ends up returning a stack trace. Minting is
     * the fail-safe outcome: the response is still joinable to a server-side record, and the
     * substituted handle is visible in that record as a handle nobody else knows.</p>
     */
    private static String safeHandle(String value) {
        String token = safeToken(value);
        if (token != null) {
            return token;
        }
        byte[] minted = new byte[16];
        CORRELATIONS.nextBytes(minted);
        return HexFormat.of().formatHex(minted);
    }

    /**
     * The strict grammar for {@link #assistantReason}: {@code [A-Z][A-Z0-9_]*}, or null.
     *
     * <p>Deliberately narrower than {@link #safeToken(String)}, which exists to keep a value from
     * breaking the JSON encoding and therefore admits {@code . - :} and lower case — everything you
     * need to spell a hostname, a model id or an API key. That is the right latitude for {@code code},
     * whose vocabulary is a closed set of enum names and rate-limiter constants supplied by trusted
     * components; it is the wrong latitude for a member whose whole purpose is to be a bare label.</p>
     *
     * <p>This is a second layer, not the first: the assistant's own tokens come from an enum and are
     * pinned by {@code AssistantReasonTokenTest}. It exists because the first layer is one careless
     * edit from a token that names an endpoint, and because the class's Javadoc already promised this
     * property to anyone reading it. A promise that nothing enforces is worse than no promise —
     * a reviewer stops looking.</p>
     */
    private static String safeReasonToken(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_CORRELATION_LENGTH) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'A' && character <= 'Z')
                    || (index > 0 && ((character >= '0' && character <= '9') || character == '_'));
            if (!allowed) {
                return null;
            }
        }
        return value;
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_CORRELATION_LENGTH) {
            return null;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '.' || character == '_' || character == '-' || character == ':';
            if (!allowed) {
                return null;
            }
        }
        return value;
    }

    /**
     * Defence in depth. Every message reaching here is already author-controlled, but an encoder that
     * relies on its inputs being well-behaved is the encoder that breaks when one stops being.
     */
    private static String escape(String value) {
        var escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
