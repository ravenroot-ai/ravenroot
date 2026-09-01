package ai.ravenroot.extensions.ai;

/**
 * A skill declared on an {@code agent} node is not one this bundle can ever serve.
 *
 * <h2>Why this is not an {@link AgentException}</h2>
 * <p>{@code AgentException} is the vocabulary of a <em>running</em> agent, and its own rule is that a
 * refusal is a failed future and never a synchronous throw. This is the opposite kind of failure: a
 * skill over the ceiling, or one with no name, is unsatisfiable for every input the node will ever
 * see, so it is refused while the graph is being composed. Widening {@code AgentException} to cover
 * both would have made its central invariant untrue for two of its own codes.</p>
 *
 * <h2>Why it extends {@link IllegalArgumentException}, which is the load-bearing part</h2>
 * <p>Because that is what makes the refusal <b>answerable</b>. The server's submission handler maps
 * {@code IllegalArgumentException} to {@code INVALID_REQUEST} and {@code IllegalStateException} to
 * {@code CONFLICT}; a {@code RuntimeException} of any other type matches no clause and the request is
 * not answered through the product's error contract at all. This is exactly the precedent CORE-03 set
 * with {@code JoinConfigurationException} for a malformed join property, and for the same reason: a
 * defect in an authored property must come back as a refused submission, not as a dropped one.</p>
 *
 * <h2>What the author actually sees, which is less than this message says</h2>
 * <p><b>The message does not reach them.</b> API-01 left the server no error signature that accepts
 * text — {@code fail(exchange, ErrorCode)} is the only one — so the author receives
 * {@code INVALID_REQUEST} and no detail; the deployment path sanitizes harder still, to the class
 * name alone.</p>
 *
 * <p><b>And no operator reads it either, today.</b> The submission handler binds the exception and
 * never uses it, and {@code DefaultGraphDeployment.recordFailure} keeps only the class's simple name,
 * so this message is write-only: it names the node, the field, the skill, the actual length and the
 * ceiling, and reaches no surface at all. It is nevertheless composed correctly, so a future
 * author-facing surface can reuse the complete diagnostic.</p>
 *
 * <p>That gap cannot be closed from inside a node bundle:
 * a construction-time refusal that can name an author-written token needs a mapped error code and a
 * bounded catalog-owned detail in the core, which the {@code extension} boundary forbids here. The mitigation
 * that <em>is</em> available here is prevention rather than diagnosis — every ceiling is stated in
 * the property's own description, so the Inspector shows the limit before it is exceeded.</p>
 */
final class AgentSkillException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    /** Which kind of defect, so a test and a log reader do not have to match on prose. */
    enum Code {
        /** Unnamed, undescribed, empty, a duplicate name, or a slot leaving an earlier one blank. */
        DECLARATION_INVALID,
        /** A name, description or body over this bundle's ceiling. */
        TOO_LARGE
    }

    private final Code code;
    private final String skill;

    /**
     * @param nodeId the graph node the defect is on
     * @param skill the skill's name, or its slot when the name is itself the field at fault. Never a
     *     payload, a body, a prompt or a credential reference: a name is bounded and control-character
     *     free before it can reach here, and a body never reaches here at all
     * @param detail what is wrong, in words an operator can act on
     */
    AgentSkillException(Code code, String nodeId, String skill, String detail) {
        super("Agent node '" + nodeId + "' skill '" + skill + "' " + detail);
        this.code = code;
        this.skill = skill;
    }

    Code code() {
        return code;
    }

    /** The skill this refusal is about: its name, or its slot. */
    String skill() {
        return skill;
    }
}
