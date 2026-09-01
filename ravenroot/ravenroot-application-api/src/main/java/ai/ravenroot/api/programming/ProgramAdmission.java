package ai.ravenroot.api.programming;

/**
 * A one-shot authorization to execute one artifact, redeemed at the last moment before the
 * artifact's source reaches a sandbox (SEC-12; SEC-25).
 *
 * <p><b>Why this type exists instead of a {@link GeneratedArtifact} parameter.</b> Execution used to
 * be admitted by reading the registry, checking {@code state() == ACTIVE}, and then handing the
 * resulting immutable snapshot to {@code ProgramRuntime.execute}. The check and the use were separate
 * acts with an interval between them, and the interval was not small: on this exact path a single
 * sandbox round trip costs ~671 ms idle and ~5.4 s at 8x CPU oversubscription.
 * A retirement could complete in full -- state {@code RETIRED}, audit written -- while the sandbox was
 * still starting, and the revoked source would then execute. Verifying "closer to" the call narrows
 * that window; it does not close it.
 *
 * <p><b>The property this type provides.</b> Nothing can intervene between the check and the use
 * because there is no interval: <b>the check is the acquisition.</b> {@link #redeem()} re-reads
 * authoritative registry state under the registry's own per-artifact lock and returns the artifact --
 * the only way to obtain its source for execution -- or throws. A caller that holds no redeemed
 * artifact holds no source to run.
 *
 * <p><b>The residue, stated rather than implied.</b> Once the source bytes have been written to a
 * sandbox worker, revocation cannot un-run them. Admission alone therefore does not close the hole;
 * {@link #onRevoked(Runnable)} is the other half, and revocation actively cancels executions that
 * were admitted before it landed. Neither half is sufficient alone.
 */
public interface ProgramAdmission extends AutoCloseable {
/**
 * The artifact this admission is for. Always safe to read; carries no authority.
 * @return identifier of the artifact for which this admission was issued.
 */
    String artifactId();

    /**
     * The artifact as it was observed when this admission was created.
     *
     * <p><b>Never gate on this.</b> It is a stale snapshot by construction -- that staleness is the
     * defect this type exists to remove. It is provided for evidence and diagnostics only: the
     * artifact's {@code id} and {@code sha256} are invariant across its whole lifecycle (a transition
     * copies both verbatim), so recording them from here is sound, while any authorization decision
     * taken from it reintroduces exactly the TOCTOU this type closes. The name is deliberately
     * uncomfortable so that misuse reads as wrong at the call site.
 * @return stale creation-time snapshot for audit evidence only, never authorization.
     */
    GeneratedArtifact unverifiedSnapshot();

    /**
     * Re-checks authoritative state and returns the artifact only if it is still admissible.
     *
     * <p>Called immediately before the source is handed to a sandbox, and never earlier. Throws
     * {@link SecurityException} if the artifact is no longer owned by the admitted tenant, is no
     * longer {@code ACTIVE}, or has changed revision since the admission was created. Redeeming more
     * than once is permitted and re-checks every time; redeeming after {@link #close()} is not.
 * @return authoritative active artifact immediately before release to the sandbox.
     */
    GeneratedArtifact redeem();

    /**
     * Registers a cancellation to run if this artifact is revoked while this admission is in flight.
     *
     * <p>This is the half of the control that {@link #redeem()} cannot provide. An execution admitted
     * a microsecond before a retirement is legitimately admitted, and its source may already be inside
     * a worker; the only remaining remedy is to cancel it rather than let it run to completion
     * unnoticed.
 * @param cancellation callback to invoke when the admitted artifact is revoked.
     */
    void onRevoked(Runnable cancellation);

    /** Releases this admission's revocation registration. Idempotent. */
    @Override
    void close();
}
