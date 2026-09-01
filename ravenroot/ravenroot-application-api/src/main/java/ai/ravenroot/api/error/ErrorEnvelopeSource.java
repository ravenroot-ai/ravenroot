package ai.ravenroot.api.error;

import java.util.Objects;

/**
 * A failure that can state which {@link ErrorCode} represents it, so a consumer can build the common
 * {@link ErrorEnvelope} without knowing which component failed.
 *
 * <h2>What this exists to end</h2>
 * <p>{@code ErrorEnvelope} reached the application API, the CLI and the server, and <b>no connector</b>.
 * Each connector instead grew its own exception with its own enum of codes, and those enums overlap
 * without coinciding: SMTP and Telegram call a missing deployment profile {@code CONFIGURATION} while
 * IMAP calls it {@code PROFILE_UNAVAILABLE}; SMTP and Telegram call admission refusal
 * {@code CAPACITY_UNAVAILABLE} while IMAP calls it {@code SATURATED}. A consumer holding the resulting
 * {@code RuntimeException} therefore had to know <em>which</em> connector spoke before it could
 * interpret anything. This interface is the type it can test for instead.
 *
 * <h2>Why the only member an implementor writes is an {@link ErrorCode}</h2>
 * <p>A connector's failures form around a host, a port, a credential reference or a fragment of a
 * remote system's reply. Those are exactly what must not reach a consumer. So the one abstract member
 * returns a constant of a closed enum, and {@link #envelopeOf} — the route from an implementor to an
 * envelope — is <b>{@code static} rather than {@code default}</b>. A {@code static} interface method
 * cannot be overridden or hidden, so an implementor supplies a choice among {@link ErrorCode}'s
 * constants and <em>nothing else</em>: not the message, which {@link ErrorCode} owns; not the
 * correlation handle, which the consumer passes; and not any other member of the envelope.
 *
 * <p>The distinction between {@code static} and {@code default} is load-bearing. A {@code default}
 * method is overridable, and an override <b>can</b> reach two factories on
 * {@link ErrorEnvelope} that this interface must not put within a connector's reach:
 * {@link ErrorEnvelope#ofServerCode(String, ErrorCode, String)} writes the caller's token into
 * {@code code}, and {@link ErrorEnvelope#withIncident(String)} writes the caller's handle into
 * {@code incidentId}. Both are filtered only by {@code ErrorEnvelope}'s token grammar, which admits
 * {@code . - : _} and lower case — enough, as that class's own documentation puts it, to spell a
 * hostname, a model id or an API key. A third-party implementor overriding a {@code default} method
 * could therefore publish {@code code = imap.example.test:993} and
 * {@code incidentId = user:alice-sk-ant-0xdeadbeef}, and every word of this paragraph would have been
 * a promise nothing enforced. Those two factories still exist and are still legitimate for the
 * components that own them; what changed is that <b>this interface is not a route to them</b>.
 *
 * <p>Both factories remain reachable by any code that holds an {@code ErrorEnvelope} directly, which
 * is outside what this type can govern. The guarantee is scoped precisely: <em>an implementor of this
 * interface, acting through this interface, cannot put text of its own into an envelope.</em>
 *
 * <h2>What it does not attempt</h2>
 * <p>It does not widen {@link ErrorCode}. A connector vocabulary is generally finer than the common
 * one, so an implementor's mapping will collapse distinctions; declaring the collapse and what it
 * costs is the implementor's job, not something this interface can carry. {@code code} cannot carry
 * the finer token either — it is the status-bearing member declared per route in the server's
 * {@code RouteTable} and rendered into the checked-in OpenAPI document, so a token no route declares
 * has no business appearing in it. Assistant reasons meet that same wall and are carried
 * by an additive, grammar-constrained member ({@code assistantReason}) rather than by loosening
 * {@code code}; nothing equivalent exists for connectors yet, and inventing one from a single
 * connector's evidence would fix the shape too early.
 */
public interface ErrorEnvelopeSource {

    /**
     * The common code that represents this failure.
     *
     * <p>Implementors should derive this from their own vocabulary with an exhaustive {@code switch}
     * that has no {@code default}, so a code added later cannot acquire a mapping by accident: an
     * implicit mapping is a loss of information nobody notices.
     * @return the server-authored public category to use for this failure
     */
    ErrorCode errorCode();

    /**
     * A failure as the common envelope, joined to a correlation handle the caller supplies.
     *
     * <p>{@code static} on purpose — see the class documentation. It takes the source as a parameter
     * rather than being an instance method precisely so that no implementor can substitute its own
     * version of it.
     *
     * <p>The handle is a parameter rather than something the failing component mints, because the
     * party that can join it to a server-side record is the one answering the request. It is also the
     * one field of the envelope that carries caller-influenced text at all ({@code ErrorEnvelope}
     * accepts any bounded {@code [A-Za-z0-9._:-]} token verbatim), so deriving it from a profile id, a
     * host or a payload would put exactly the material this interface exists to exclude back into the
     * response.
     * @param source failure carrying one closed-vocabulary category
     * @param correlationId bounded response handle supplied by the component answering the request
     * @return closed envelope made from the source category and the supplied handle
     */
    static ErrorEnvelope envelopeOf(ErrorEnvelopeSource source, String correlationId) {
        return ErrorEnvelope.of(Objects.requireNonNull(source, "source").errorCode(), correlationId);
    }
}
