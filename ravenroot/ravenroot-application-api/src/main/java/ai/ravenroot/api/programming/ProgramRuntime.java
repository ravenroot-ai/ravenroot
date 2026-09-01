package ai.ravenroot.api.programming;

import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Optional sandbox adapter. Implementations must reject artifacts that are not ACTIVE.
 *
 * <h2>Implementations must be safe for concurrent use (ADR 0024 §3)</h2>
 * <p>One runtime serves every program node, and several invocations of the same node run at the same time. Any evaluation context, interpreter or sandbox handle that is not safe for concurrent use must be created per execution rather than held on the runtime.</p>
 *
 * <p>This is stated rather than newly imposed. Nothing here was ever documented as single-threaded;
 * it was true by accident, because one logical graph node was backed by one actor and an actor
 * handles one message at a time. ADR 0024 removes that serialisation deliberately, so an
 * implementation that relied on it is now racy. The implementations in this repository are already
 * safe; a third-party one never had a rule to follow, so here it is.</p>
 */
public interface ProgramRuntime {
/**
 * Identifies this runtime adapter for artifact selection and diagnostics.
 * @return stable adapter identifier.
 */
    String id();

    /**
 * Stable, non-secret identity of the runtime and sandbox contract whose evidence is reusable.
 * A changed value revalidates the same source without pretending its content changed.
* @return stable identity of the runtime and sandbox compatibility contract
 */
    default String compatibilityFingerprint() {
        return id();
    }

    /**
 * The languages this runtime accepts as {@code createProgramArtifact}'s {@code language}
 * parameter, each with a starter source an editor can offer before the author writes anything.
 *
 * <p><b>Default is empty, and that is a refusal to guess, not an omission.</b> A runtime that has
 * not implemented this declares no languages rather than silently implying the one this
 * repository shipped first; a caller populating a selector from an empty list correctly shows no
 * choices rather than a plausible but invented one. This is a {@code default} method — not an
 * abstract one — so every existing implementation, including {@code DisabledProgramRuntime} and
 * any third-party adapter compiled against an earlier version of this interface, keeps compiling
 * without change.</p>
 *
 * <p>An editor must read this list rather than hard-code language identifiers. A third language
 * must become selectable by an adapter implementing this method alone, with no change to the editor.</p>
 * @return immutable language descriptors accepted by this adapter, or empty when it declares none.
 */
    default List<ProgramLanguageDescriptor> supportedLanguages() {
        return List.of();
    }

    /**
 * Parses and checks an artifact without executing user logic.
 *
 * <p><b>How a refusal says what it is.</b> An implementation that establishes the source
 * itself is at fault — it does not parse, or it parses to something that cannot be called — fails
 * the returned stage with {@link ProgramSourceRejectedException}, carrying the runtime's own
 * diagnostic and, where the runtime supplies them, the line and column. Every other failure keeps
 * whatever type it already had. The HTTP surface reports the first as a validation <em>result</em>
 * and the second as an error, so an implementation that widens the first type to cover
 * infrastructure failures makes the product tell an author their source does not compile when it
 * does.</p>
 *
 * <p>This is a stated obligation, not a new one imposed: an implementation that throws something
 * else keeps working exactly as before, and its refusals stay as legible — or as illegible — as
 * they were.</p>
 * @param artifact artifact whose source must be parsed and checked without running it.
 * @return stage that completes when validation succeeds or fails with a typed source rejection.
 */
    default CompletionStage<Void> validate(GeneratedArtifact artifact) {
        // Typed rather than UnsupportedOperationException, because this is the DEFAULT state of
        // an install -- DisabledProgramRuntime relies on exactly this default -- and it used to reach
        // the author as "the request was rejected as invalid", sending them to read a source that
        // nothing had looked at.
        return java.util.concurrent.CompletableFuture.failedFuture(new ProgramRuntimeUnavailableException(
                ProgramRuntimeUnavailableException.Reason.RUNTIME_NOT_INSTALLED,
                "Runtime " + id() + " does not support validation"));
    }

/**
 * Executes a validated artifact with test data before human approval.
 * @param artifact validated artifact to evaluate in a test context.
 * @param request payload and attributes supplied to that test invocation.
 * @return stage yielding the test result.
 */
    default CompletionStage<Object> test(GeneratedArtifact artifact, ProgramRequest request) {
        return java.util.concurrent.CompletableFuture.failedFuture(new ProgramRuntimeUnavailableException(
                ProgramRuntimeUnavailableException.Reason.RUNTIME_NOT_INSTALLED,
                "Runtime " + id() + " does not support artifact tests"));
    }

    /**
 * Executes an admitted artifact.
 *
 * <p><b>Takes a {@link ProgramAdmission}, not a {@link GeneratedArtifact}, and that is the
 * control</b>. Implementations must call {@link ProgramAdmission#redeem()}
 * immediately before the source reaches the sandbox and use only what it returns; a snapshot read
 * any earlier is stale by an interval measured on this path at ~671 ms idle and ~5.4 s at 8x load,
 * which is long enough for a retirement to complete in full while the sandbox is still starting.
 * Implementations should also register a cancellation via {@link ProgramAdmission#onRevoked} and
 * {@link ProgramAdmission#close()} the admission when the returned stage completes.
 * @param admission revocable authorization to redeem immediately before sandbox execution.
 * @param request payload and attributes supplied to the executing artifact.
 * @return stage yielding the artifact result.
 */
    CompletionStage<Object> execute(ProgramAdmission admission, ProgramRequest request);
}
