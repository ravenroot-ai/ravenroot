package ai.ravenroot.api.programming;

/**
 * This deployment cannot run program artifacts at all, and it is not the author's source that is
 * wrong.
 *
 * <h2>The failure this exists for, and why it is the worse of the two</h2>
 * <p>A syntax error was once answered with {@code the request was rejected as invalid}. Measurement
 * found <b>two</b> causes behind that one sentence, and this is the second:
 * on the reporter's machine {@code RAVENROOT_GRAAL_SANDBOX_SUPERVISOR} was not set, so the adapter
 * installed its missing-launcher stub and <em>every</em> validation failed with
 * {@code SANDBOX_LAUNCHER_MISSING} — before the source was ever compiled. Correcting the indentation
 * changed nothing, because the indentation had never been the problem.</p>
 *
 * <p>That is the more expensive failure of the two, and the reason this type exists separately from
 * {@link ProgramSourceRejectedException}. A compiler diagnostic that is merely vague still points at
 * the right file. "The request was rejected as invalid" for an unconfigured deployment points at the
 * author's own code, which is the one place the fault is not. An author who reads "an operator must
 * configure the program sandbox" calls an operator; an author who reads anything about their request
 * being invalid reads their source again, and again.</p>
 *
 * <h2>Why the vocabulary is closed and there is no free text here</h2>
 * <p>Unlike a compiler diagnostic, everything a caller needs is already a closed set: the deployment
 * has no adapter, or it has one whose sandbox is not usable. Each maps to one {@code ErrorCode} whose
 * message is a server-authored literal, so this classification travels in the ordinary error envelope
 * and needs no extension of it — and it does not pre-empt the open question in the documented contract
 * about carrying a connector's finer reason, because it asks nothing of the envelope at all.</p>
 *
 * <p>The underlying token ({@code SANDBOX_LAUNCHER_MISSING}, {@code SANDBOX_CAPABILITY_UNSUPPORTED})
 * is kept as this exception's message. It is not part of what a caller is told: {@code
 * ErrorCode.PROGRAM_SANDBOX_UNAVAILABLE} answers a fixed literal, and the caller-facing message
 * points at the server log rather than repeating the token. It reaches that log because {@code
 * GraalVmProgramRuntime} writes it there when the check fails, at both startup (for a configured but
 * unusable launcher) and at request time -- this class holding the message is necessary for
 * that but was not, by itself, sufficient: previously nothing actually read this message and wrote
 * it anywhere an operator could see it, despite an earlier version of this paragraph claiming it did.</p>
 */
public final class ProgramRuntimeUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * What an operator has to do about it. Closed, and closed at the granularity of the <b>action</b>
     * rather than of the internal token.
     *
     * <p>{@code SANDBOX_LAUNCHER_MISSING} and {@code SANDBOX_CAPABILITY_UNSUPPORTED} are one member
     * here, deliberately. They are different facts but the same instruction — an operator must attend
     * to this deployment's sandbox configuration — and splitting a caller-facing vocabulary on a
     * distinction the caller cannot act on differently is how a vocabulary grows members nobody
     * branches on. The distinction is not lost: the token stays on the exception, and
     * {@code GraalVmProgramRuntime} writes it to the server log where the operator who acts on it is
     * already looking, alongside the launcher's own path.</p>
     *
     * <p>They are also not <em>guessed</em> apart, which matters more. {@code SandboxSupervisorLauncher}
     * is an implementable interface, so a third-party launcher may fail its capability check with text
     * this repository has never seen; classifying by substring would eventually put such a failure
     * under a claim nobody established.</p>
     */
    public enum Reason {
        /**
         * No program runtime adapter is installed in this build, so there is nothing to validate with.
         * This is the <b>default</b> state of a Ravenroot install — see {@code DisabledProgramRuntime},
         * whose Javadoc calls it the safe default — which is what makes this the most common way to
         * encounter this failure rather than an exotic one.
         */
        RUNTIME_NOT_INSTALLED,
        /**
         * An adapter is installed but this deployment's sandbox is not usable: none is configured, or
         * the configured supervisor does not meet this build's capability requirement.
         */
        SANDBOX_UNAVAILABLE,
    }

/**
 * Closed operator-action category for this deployment's unavailable runtime.
 */
    private final Reason reason;

/**
 * Creates an unavailable-runtime failure without an underlying infrastructure cause.
 * @param reason operator-action category for the unavailable capability.
 * @param operatorDetail diagnostic retained for server logs, not caller error text.
 */
    public ProgramRuntimeUnavailableException(Reason reason, String operatorDetail) {
        super(operatorDetail);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

/**
 * Creates an unavailable-runtime failure retaining its infrastructure cause.
 * @param reason operator-action category for the unavailable capability.
 * @param operatorDetail diagnostic retained for server logs, not caller error text.
 * @param cause underlying adapter or sandbox failure.
 */
    public ProgramRuntimeUnavailableException(Reason reason, String operatorDetail, Throwable cause) {
        super(operatorDetail, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

/**
 * Returns the closed category used to select the operator remediation.
 * @return unavailable-runtime reason.
 */
    public Reason reason() {
        return reason;
    }
}
