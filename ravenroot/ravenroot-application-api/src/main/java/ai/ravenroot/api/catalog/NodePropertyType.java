package ai.ravenroot.api.catalog;

/** Transport-neutral field types understood by node editors. */
public enum NodePropertyType {
    /** A single-line textual value. */
    STRING,
    /** Multi-line or longer textual content. */
    TEXT,
    /** A true-or-false value. */
    BOOLEAN,
    /** A whole-number value. */
    INTEGER,
    /** A fractional numeric value. */
    DECIMAL,
    /** A URI value. */
    URI,

    /**
     * An opaque name for a server-side secret. Never the secret itself.
     *
     * <h4>The normalisation of this type is deliberately the identity</h4>
     * <p>A reader of a {@code SECRET_REFERENCE} passes the authored characters downstream
     * <strong>unchanged</strong>: no {@code trim()}, no {@code strip()}, no case folding, no
     * substitution. The rule is written here, once, because it previously existed nowhere and the two
     * built-in nodes that read this type had quietly drifted apart — the HTTP node trimmed, the LLM
     * node did not. Restating it per node is what allows the next reader to disagree unnoticed.
     *
     * <h4>Why the identity, rather than one canonical form applied in one place</h4>
     * <p>Both were available and the second sounds stronger, so the reason for rejecting it matters.
     * Every resolver behind this type derives its lookup key <em>injectively</em>:
     * {@code " a"}, {@code "a "} and {@code "a"} are three references and derive three distinct keys,
     * pinned in byte literals. Any normalisation upstream of an injective encoder is a many-to-one
     * step, so choosing one and centralising it would not remove the collapse — it would make the
     * collapse uniform, which is the defect rather than the repair, and would put it in a place no
     * single node's author would think to look. {@code EnvironmentCredentialResolver} refused a
     * {@code trim()} before its own encoding for exactly this reason; this is the same decision one
     * layer up, applied to the readers instead of the encoder.
     *
     * <p>The identity also has the better failure mode. A padded reference read verbatim fails to
     * resolve, loudly and with the reference in the message; a padded reference normalised silently
     * resolves to the secret provisioned for a different reference — including, potentially,
     * somebody else's.
     *
     * <h4>What this does not rest on</h4>
     * <p>Not on the validator. {@code BehaviorPropertySchema} refuses a value of this type carrying
     * whitespace, and that refusal is a better error message at admission — it is not what makes the
     * readers correct. A verbatim reader stays correct whatever the validator admits, which is the
     * point: relaxing the whitespace refusal, the most ordinary imaginable change to that control,
     * must not be able to reopen a collision in a file nobody connected to it. The readers are held
     * to this directly by {@code SecretReferenceReaderFidelityTest} in {@code ravenroot-core}, which
     * drives them with values the validator refuses.
     */
    SECRET_REFERENCE,
    /** A Common Expression Language expression evaluated by the behavior that accepts it. */
    CEL_EXPRESSION
}
