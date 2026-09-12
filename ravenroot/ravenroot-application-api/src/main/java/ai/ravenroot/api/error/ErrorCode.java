package ai.ravenroot.api.error;

/**
 * The closed vocabulary of machine-readable error codes (API-01).
 *
 * <p>Each constant owns its public message. That pairing is the whole redaction mechanism: because
 * {@link ErrorEnvelope} has no <em>public</em> constructor at all and every factory derives the
 * message from a code, the text a client sees is a pure function of the code, and no exception
 * message, file path, SQL fragment or stack frame has a route to a response. The property is
 * structural rather than a convention someone has to remember, which matters because the failing case
 * is always the one hurried call site that used {@code getMessage()}.</p>
 *
 * <p>The API-01 emphasis is load-bearing. While {@code ErrorEnvelope} was
 * a {@code public record} the language generated a public canonical constructor, so this paragraph
 * described a guarantee the code did not have.</p>
 *
 * <p>The status is attached here too, so two adapters cannot disagree about what a code means.</p>
 */
public enum ErrorCode {
    /** No authenticated principal was available for an endpoint that requires one. */
    AUTHENTICATION_REQUIRED(401, "authentication required"),
    /** An authenticated principal lacks permission for the requested operation. */
    ACCESS_DENIED(403, "access denied"),
    /** The addressed resource does not admit the requested HTTP method. */
    METHOD_NOT_ALLOWED(405, "method not allowed"),
    /** The route or resource is not visible to the requesting principal. */
    UNKNOWN_RESOURCE(404, "unknown resource"),
    /** The requested operation does not apply to a known program artifact. */
    UNKNOWN_ARTIFACT_OPERATION(404, "unknown artifact operation"),
    /**
     * Deliberately says nothing about why: an execution that never existed, one belonging to
     * another tenant, and one evicted past the retention horizon all answer with this code, because
     * distinguishing the first two would disclose them. See {@code ExecutionLookup.Unknown}.
     */
    UNKNOWN_EXECUTION(404, "unknown execution"),
    /**
     * The durable-inventory counterpart of {@link #UNKNOWN_EXECUTION}, and deliberately a distinct
     * code rather than a reuse: a process instance is the durable aggregate, addressed by
     * {@code processInstanceId}, and is not the same identity {@link #UNKNOWN_EXECUTION} names
     * ({@code executionId}, which is the traversal id). Says nothing about why for the identical
     * reason {@link #UNKNOWN_EXECUTION} does not: an instance that never existed, one belonging to
     * another tenant, and one purged past its terminal retention window all answer with this code,
     * because distinguishing the first two would disclose them. A caller that needs to tell "never
     * existed" from "expired by policy" apart compares against the retention floor a durable
     * inventory listing carries, not against a second failure channel here.
     */
    UNKNOWN_PROCESS_INSTANCE(404, "unknown process instance"),
    /**
     * The counterpart that makes {@link #UNKNOWN_EXECUTION} honest rather than a catch-all: the
     * execution provably ran and its terminal status is still known, but its result is past the
     * retention horizon. A caller that receives this learns its run really did happen, which an empty
     * body or a bare 404 would have hidden.
     */
    EXECUTION_RESULT_EXPIRED(410, "the execution result is no longer retained"),
    /**
     * The counterpart that keeps {@link #EXECUTION_RESULT_EXPIRED} honest in the other direction: the
     * execution provably ran and its terminal status is still known, but its payload was never
     * retained in the first place -- refused because it exceeded a configured payload budget, or
     * because the value does not project onto the closed payload model at all. Distinct from
     * {@link #EXECUTION_RESULT_EXPIRED} on purpose: one names a record that aged out under a
     * retention policy working as configured, the other names a record whose payload was refused at
     * write time and that no amount of reading it sooner would have recovered. A caller told "expired"
     * learns nothing actionable; a caller told "redacted" can distinguish a size limit an operator may
     * raise from a node returning a value no remote adapter could ever persist -- the two published
     * {@code payloadState} values ({@code WITHHELD}, {@code UNCONVERTIBLE}) this response body carries
     * beside {@code status} and {@code terminationReason}, for the identical reason those two travel
     * beside {@link #EXECUTION_RESULT_EXPIRED}'s own body. 410, the same status
     * {@link #EXECUTION_RESULT_EXPIRED} uses, because both describe the identical shape of absence to
     * an HTTP caller -- the resource is known and its content is not being returned -- and the two are
     * told apart by the closed-vocabulary {@code code}, not by the transport status.
     */
    EXECUTION_RESULT_REDACTED(410, "the execution result was never retained"),
    /** The request violates an input contract without disclosing rejected content. */
    INVALID_REQUEST(400, "the request was rejected as invalid"),
    /**
     * A bounded read was asked for more items than the server will return in one answer.
     *
     * <p>Distinct from {@link #INVALID_REQUEST} because the caller must be able to learn the cap it
     * exceeded, and the cap is the entire content of the answer. A generic "rejected as invalid" leaves
     * a caller to guess a working value — or invites a maintainer to read the unhelpful refusal as a
     * reason to clamp silently instead, which answers a question the caller did not ask.</p>
     *
     * <p>The bound is stated here as a literal rather than interpolated, because {@link ErrorEnvelope}
     * has no public entry point that accepts caller-composed message text and must not gain one: the
     * message is part of the closed vocabulary. {@code RecentExecutionEventsRouteTest} asserts this
     * message against {@code RavenrootServer.RECENT_EVENTS_MAX_LIMIT}, so the two cannot drift apart
     * without a test failing.</p>
     */
    EVENT_LIMIT_ABOVE_MAXIMUM(400, "limit exceeds the maximum of 2048 events per request"),
    /** The request cannot be applied to the resource's current state. */
    CONFLICT(409, "the request conflicts with the current state of the resource"),
    /** The endpoint does not accept the submitted content type. */
    UNSUPPORTED_MEDIA_TYPE(415, "the request content type is not supported"),
    /** The deployment does not implement the requested execution policy. */
    EXECUTION_POLICY_UNSUPPORTED(501, "the requested execution policy is not implemented"),
    /**
     * This deployment has no durable, inventory-capable execution store configured, so
     * the durable process inventory cannot answer at all. 501, the same status
     * {@link #EXECUTION_POLICY_UNSUPPORTED} uses, because this is a fact about this deployment's
     * composed capability rather than about the caller's request: an operator must compose a store
     * that declares {@code StoreCapability.PROCESS_INVENTORY} before this route can answer, and no
     * retry of the same request changes that.
     */
    PROCESS_INVENTORY_UNAVAILABLE(501, "this deployment has no durable process inventory configured; "
            + "an operator must compose an execution store that declares PROCESS_INVENTORY"),
    /** Processing stopped before the operation reached a terminal result. */
    REQUEST_INTERRUPTED(503, "the operation was interrupted before it completed"),
    /** A request-rate or concurrency limit refused the operation. */
    REQUEST_LIMIT_EXCEEDED(429, "request limit exceeded"),
    /** Submitted program source exceeds the deployment's configured size budget. */
    PROGRAM_SOURCE_TOO_LARGE(413, "the program source exceeds the configured size limit"),
    /**
     * This deployment has no program runtime adapter installed.
     *
     * <p>Distinct from {@link #INVALID_REQUEST}, which is what an author used to receive for it, and
     * distinct for the reason {@link #EVENT_LIMIT_ABOVE_MAXIMUM} is: a generic "rejected as invalid"
     * leaves the caller to guess, and here the guess it invites is the most expensive one available —
     * that the artifact source is wrong. It is not. Nothing has looked at it. This is the <b>default</b>
     * state of an install ({@code DisabledProgramRuntime}), so it is the likeliest way to meet the
     * defect, not an edge of it.</p>
     *
     * <p>501 rather than 503: an unconfigured deployment is not temporarily busy, and it will answer
     * this way forever until a person acts. A 503 invites a retry that can never succeed. 501 is also
     * the status this vocabulary already uses for a capability the deployment does not provide — see
     * {@link #EXECUTION_POLICY_UNSUPPORTED}.</p>
     *
     * <p>The final sentence of the message is the load-bearing one and is not decoration: it is what
     * stops the reader going back to their own code.</p>
     */
    PROGRAM_RUNTIME_NOT_INSTALLED(501, "this deployment has no program runtime adapter installed, so no "
            + "artifact can be validated, tested or executed; an operator must install one. The artifact "
            + "source is not at fault"),
    /**
     * An adapter is installed, but this deployment's sandbox is not usable — none is configured,
     * or the configured supervisor does not meet this build's capability requirement.
     *
     * <p>This is the exact measured failure condition: {@code RAVENROOT_GRAAL_SANDBOX_SUPERVISOR}
     * unset, every validation failing with {@code SANDBOX_LAUNCHER_MISSING} before the source was ever
     * compiled, and the author told their request was invalid. Correcting the source changed nothing,
     * because the source had never been read.</p>
     *
     * <p>One code for both underlying tokens, on purpose: they are different facts but the same
     * instruction to whoever receives this. The token itself, and the launcher's own path, go to the
     * server log instead -- not into this message, which stays this deployment's fixed,
     * server-authored literal regardless of policy, so a path or token this deployment considers
     * internal is never at risk of leaving through the envelope. The message's last sentence points
     * the reader at the log rather than repeating either. See
     * {@code ProgramRuntimeUnavailableException.Reason} for why the two tokens are not told apart by
     * inspecting a third-party launcher's text.</p>
     */
    PROGRAM_SANDBOX_UNAVAILABLE(501, "this deployment's program sandbox is not usable, so no artifact "
            + "can be validated, tested or executed; an operator must configure or repair it. The "
            + "artifact source is not at fault. The specific reason is recorded in this deployment's "
            + "server log"),
    /**
     * The sandbox did not finish within this deployment's configured time budget.
     *
     * <p>The third cause to be split out of the pair {@link #INVALID_REQUEST} / {@link #CONFLICT},
     * and both of those were measured answering it before this code existed. A raw {@code
     * TimeoutException} escaping the adapter produced 400 <em>the request was rejected as invalid</em>;
     * the far commoner path — the adapter's own deadline check — produced 409 <em>the request
     * conflicts with the current state of the resource</em>, a body identical in every diagnostic
     * field to the one a genuine state conflict produces. The 409 was the worse of the two: it names
     * a cause, so a reader believes it instead of suspecting the classification.</p>
     *
     * <p><b>504 rather than 501, and the distinction is what the condition is a fact ABOUT.</b>
     * {@link #PROGRAM_RUNTIME_NOT_INSTALLED} and {@link #PROGRAM_SANDBOX_UNAVAILABLE} are facts about
     * this deployment's <em>capability</em>: it has no runtime adapter, or no usable sandbox, so the
     * remedy is an operator action and 501 names it. A deadline is a fact about <em>one run's elapsed
     * time against a configured budget</em>. The capability was present and worked — {@code
     * verifyCapability()} passed before any of what follows — and only the clock ran out. GraalPy's
     * cold start was measured at 2929 ms against a budget that was 5000 ms, so the identical
     * request against the identical deployment fails on a loaded machine and succeeds on an idle one.
     * Retrying, or raising the budget, are the two correct responses, and 504 is the status that says
     * an upstream did not answer in time rather than that the caller asked for something impossible.</p>
     *
     * <p><b>How much of the run had already happened is not part of that argument, because it varies
     * and is sometimes unknown.</b> The adapter raises this at <b>seven</b> points, and they are not
     * alike: at {@code before_launch} the sandbox has not started; at {@code after_launch} it has, but
     * the source has not been written; at {@code write_request} the write itself expired; and at both
     * {@code after_request_write} and {@code sandbox_outcome} <em>whether the worker ever ran is not
     * established</em>. Only {@code diagnostics} and {@code after_response} are reached past a parsed
     * worker response, so only those two establish that it ran. No sentence here says what had been
     * accomplished, because for the commonest stage no such sentence would be true.</p>
     *
     * <p>{@code sandbox_outcome} is the trap, and it is worth naming. It looks like the supervisor
     * reporting a verdict, but the only production implementation answers {@code DEADLINE_EXCEEDED}
     * from {@code !process.waitFor(remaining)} alone — the caller's own timeout, which is exactly what
     * {@code SandboxSupervisorContract} requires a conforming supervisor not to leave the deadline to.
     * A supervisor stuck in setup, or a worker still inside GraalPy's cold start, lands here with the
     * program never executed, which is the case this timeout classification covers. It is also the stage that occurs in
     * practice: measured over two full reactor runs, {@code sandbox_outcome} five times each and
     * {@code after_launch} never. See {@code GraalVmProgramRuntime}'s comment at that call site, and
     * the anchored table in {@code docs/architecture/payload-and-error-contract.md}, before writing
     * anything about what a stage implies.</p>
     *
     * <p>What every stage does share is the part the argument rests on: the capability was verified,
     * and one run's budget elapsed. Which stage it was goes to the server log, where an operator can
     * act on it.</p>
     *
     * <p><b>What this reasoning deliberately does NOT claim, because it would be false here.</b> It
     * does not claim that a 501 in this deployment always holds until a person intervenes. It does
     * not: {@code SandboxSupervisorProcessLauncher.verifyCapability()} bounds its capability probe at
     * two seconds and turns {@code !process.waitFor(2, TimeUnit.SECONDS)} — itself a deadline — into
     * {@code SANDBOX_CAPABILITY_UNSUPPORTED}, hence {@link #PROGRAM_SANDBOX_UNAVAILABLE} and 501, and
     * it runs that probe on <em>every</em> request. On a loaded machine — precisely the condition
     * described above — that answer tells an operator to configure or repair a sandbox that is
     * healthy, for a condition a retry would clear.</p>
     *
     * <p>That is a <b>separate defect in the capability probe's own classification</b>: this timeout
     * category covers the execution deadline, while
     * changing what a capability probe does with its two-second bound is a separate change to a
     * separate class. It is written down here rather than left implicit because the earlier wording
     * of this paragraph rested on "a 501 is never retryable", and a reader who checked that claim
     * against the launcher would have found it untrue and had no way to tell whether the 504 above
     * was therefore wrong too. It is not: the 504 stands on what a deadline is, not on what every
     * 501 is.</p>
     *
     * <p>The budget and the elapsed wait are deliberately <b>not</b> in this message: it is the
     * server-authored literal every caller receives, and those numbers describe this deployment's
     * configuration rather than the caller's request. They go to the server log instead, which is why
     * the last sentence points there — the same division used for
     * {@link #PROGRAM_SANDBOX_UNAVAILABLE}.</p>
     */
    PROGRAM_EXECUTION_TIMEOUT(504, "the program sandbox did not complete within this deployment's "
            + "configured time budget; it may succeed if retried, and an operator can raise the budget. "
            + "The artifact source is not at fault. The budget and the elapsed wait are recorded in "
            + "this deployment's server log"),
    /** Submission document bytes exceed the endpoint's configured maximum. */
    SUBMISSION_DOCUMENT_TOO_LARGE(413, "the submission document exceeds the configured size limit"),
    /** GraphML bytes exceed the configured document-size limit before parsing. */
    GRAPHML_DOCUMENT_TOO_LARGE(413, "the GraphML document exceeds the configured byte limit"),
    /** Secure GraphML parsing exceeded a configured resource budget. */
    GRAPHML_RESOURCE_LIMIT(413, "the GraphML document exceeds a configured resource limit"),
    /** A valid graph exceeds an operator-owned execution or amplification limit. */
    GRAPH_EXECUTION_RESOURCE_LIMIT(413, "the graph exceeds a configured execution resource limit"),
    /** Secure XML parsing rejected a construct that could expand or access unsafe resources. */
    GRAPHML_UNSAFE_XML(400, "the GraphML document was refused by the secure parser"),
    /** GraphML bytes are not well-formed XML. */
    GRAPHML_MALFORMED_XML(400, "the GraphML document is not well-formed"),
    /** The submitted GraphML input is a compressed archive rather than XML. */
    GRAPHML_COMPRESSED_ARCHIVE(400, "the GraphML document is a compressed archive, not GraphML XML"),
    /** Well-formed GraphML does not satisfy Ravenroot's supported graph model. */
    GRAPHML_INVALID_GRAPH(400, "the GraphML document does not describe a supported graph"),
    /** An unexpected server-side failure occurred; details remain correlated server-side. */
    INTERNAL_ERROR(500, "the request could not be completed");

    private final int status;
    private final String message;

    ErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * The stable token a client matches on. Never localised, never reworded for a caller.
     *
     * @return stable enum token suitable for clients to match without parsing localized text
     */
    public String code() {
        return name();
    }

    /**
     * The HTTP status that transports this category without changing its public meaning.
     *
     * @return HTTP status consistently assigned to this public error category
     */
    public int status() {
        return status;
    }

    /**
     * Server-authored text derived from nothing the caller supplied.
     *
     * @return fixed server-authored text safe to include in an {@link ErrorEnvelope}
     */
    public String message() {
        return message;
    }
}
