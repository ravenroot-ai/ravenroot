package ai.ravenroot.core.recovery;

import ai.ravenroot.api.application.ExecutionPolicy;
import ai.ravenroot.api.catalog.AttemptRepeatability;
import ai.ravenroot.api.persistence.ExecutionKey;
import ai.ravenroot.api.persistence.GraphContentId;
import ai.ravenroot.api.persistence.GraphDefinitionKey;
import ai.ravenroot.api.persistence.GraphDefinitionStore;
import ai.ravenroot.api.persistence.GraphDefinitionStoreException;
import ai.ravenroot.api.persistence.GraphVersionPin;
import ai.ravenroot.api.persistence.ExecutionManifestStoreException;
import ai.ravenroot.api.persistence.ExecutionStore;
import ai.ravenroot.api.persistence.Retryability;
import ai.ravenroot.api.persistence.StoredGraphDefinition;
import ai.ravenroot.api.persistence.StoredProcessInstance;
import ai.ravenroot.core.graph.GraphManager;
import ai.ravenroot.core.graph.GraphMlLimits;
import ai.ravenroot.core.manifest.ExecutionManifestIncompatibleException;
import ai.ravenroot.core.manifest.ExecutionManifestService;
import ai.ravenroot.core.runtime.GraphExecutionLimits;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Rebuilds what recovery needs from the exact document and manifest an execution was accepted
 * against, and refuses when this deployment cannot reproduce them.
 *
 * <h2>The blocker this removes</h2>
 * <p>Until graph documents were retained, a recovery sweep in a fresh process had no graph to
 * consult. {@link RepeatabilityDeclarations#fromGraph} could only be built while a graph was already
 * in memory, so after a restart there was no snapshot at all and every ambiguous attempt parked for
 * want of one — safe, but indistinguishable from an author who had declared nothing. This class is
 * the missing half: it reads the pinned document back through {@link GraphDefinitionStore}, resolves
 * each node through the same catalog the live path uses, and answers the question a restarted sweep
 * could not previously ask.</p>
 *
 * <h2>Verified before consulted, never repaired</h2>
 * <p>{@link #classify} runs the manifest check before the definition load, because the manifest is
 * the statement about whether this deployment may run the execution at all and a document that
 * rebuilds under a runtime that cannot reproduce the run is not progress. Neither check ever adopts
 * a stored value in place of what this deployment resolves; both only ever refuse.</p>
 *
 * <p>{@link ExecutionPolicy#STANDARD} is the policy compared against because it is the policy a
 * rebuilt runner actually runs under, matching the continuation executors. An execution accepted
 * under a different one is refused here rather than silently resumed as a standard run.</p>
 *
 * <h2>Failing closed costs a park that was already happening</h2>
 * <p>Every failure to read a declaration answers {@link AttemptRepeatability#UNDECLARED}, which parks
 * an ambiguous attempt. That is the same disposition a deployment composing no declaration source at
 * all already produced for every ambiguous attempt, so a transient store fault can only ever leave
 * behaviour where it already was; it can never turn a re-dispatch that used to happen into a park.
 * The direction is one-way by construction, which is why the fail-closed branch is safe to take
 * without distinguishing why the read failed.</p>
 */
public final class PinnedGraphRecoveryAuthority implements RepeatabilityDeclarations {

    private static final System.Logger LOGGER =
            System.getLogger("ai.ravenroot.core.recovery.PinnedGraphRecoveryAuthority");

    /**
     * How many parsed documents are held at once.
     *
     * <p>Bounded because the key is a content address and a busy tenant can pin arbitrarily many
     * distinct documents; an unbounded map here would be a durable growth channel driven by
     * submitted content. Least-recently-used, so a sweep working through one cohort keeps the
     * documents it is actually using.</p>
     */
    static final int MAX_CACHED_DOCUMENTS = 64;

    private final ExecutionStore executions;
    private final GraphDefinitionStore definitions;
    /** Verifies this runtime resolves what the execution was accepted against, or {@code null} to verify nothing. */
    private final ExecutionManifestService manifests;
    private final Function<String, Optional<ai.ravenroot.api.catalog.NodeTypeDescriptor>> descriptors;
    private final GraphMlLimits graphMlLimits;
    private final Map<GraphDefinitionKey, RepeatabilityDeclarations> parsed;

    /**
     * Composes the authority over the stores a rebuild reads from.
     *
     * @param executions  durable execution state, read for each instance's write-once graph pin.
     * @param definitions durable graph documents the pinned document is read back from.
     * @param manifests   manifest verification service, or {@code null} to verify nothing, which is
     *                    how a deployment that composes no manifest store behaves.
     * @param descriptors trusted catalog lookup each node's declaration is resolved through,
     *                    normally {@code BehaviorRegistry::descriptor}. The lookup rather than the
     *                    registry, because one lookup is all a declaration needs and taking the whole
     *                    registry would tie this class to a composition it does not otherwise use.
     * @param limits      operator-owned limits, whose GraphML bounds parsing is subject to.
     */
    public PinnedGraphRecoveryAuthority(ExecutionStore executions, GraphDefinitionStore definitions,
                                        ExecutionManifestService manifests,
                                        Function<String, Optional<ai.ravenroot.api.catalog.NodeTypeDescriptor>>
                                                descriptors,
                                        GraphExecutionLimits limits) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.manifests = manifests;
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.graphMlLimits = Objects.requireNonNull(limits, "limits").graphMl();
        this.parsed = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<GraphDefinitionKey, RepeatabilityDeclarations> eldest) {
                return size() > MAX_CACHED_DOCUMENTS;
            }
        };
    }

    /**
     * Classifies one instance without writing anything and without claiming anything.
     *
     * <p>Read-only on purpose. A refusal reached here leaves the instance exactly as it was found —
     * still claimable, its durable wait intact — so the cost of being wrong about a transient fault
     * is one deferred sweep rather than lost work.</p>
     *
     * @param key tenant-scoped instance to classify.
     * @return whether this deployment may rebuild the instance, and why not when it may not.
     */
    public RecoveryClassification classify(ExecutionKey key) {
        Objects.requireNonNull(key, "key");
        GraphVersionPin pin;
        try {
            pin = await(executions.load(key)).graphVersionPin();
        } catch (RuntimeException unreadable) {
            return new RecoveryClassification.Refused(key, RecoveryClassification.Reason.UNAVAILABLE,
                    "the process instance could not be read");
        }
        return classify(key, pin);
    }

    /**
     * Classifies one already-discovered inventory row, reusing the pin the row carries.
     *
     * <p>Separate from {@link #classify(ExecutionKey)} because the inventory already answers what
     * that method would re-read: loading the aggregate again to learn a pin the row is holding would
     * make a startup scan cost one extra aggregate read per instance for nothing.</p>
     *
     * @param entry durable inventory row, carrying the instance's write-once graph pin.
     * @return the row paired with this deployment's verdict about rebuilding it.
     */
    public RecoveryCandidate classify(ai.ravenroot.api.persistence.ProcessInventoryEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return new RecoveryCandidate(entry, classify(entry.key(), entry.graphVersionPin()));
    }

    private RecoveryClassification classify(ExecutionKey key, GraphVersionPin pin) {
        RecoveryClassification manifestVerdict = verifyManifest(key);
        if (manifestVerdict != null) {
            return manifestVerdict;
        }
        try {
            declarationsFor(definitionKey(key, pin));
            return new RecoveryClassification.Rehydratable(key);
        } catch (RuntimeException unresolved) {
            return refusedDefinition(key, unresolved);
        }
    }

    /** Returns the refusal, or {@code null} when the manifest is verified or no service is composed. */
    private RecoveryClassification verifyManifest(ExecutionKey key) {
        if (manifests == null) {
            return null;
        }
        try {
            manifests.verify(key, ExecutionPolicy.STANDARD);
            return null;
        } catch (ExecutionManifestIncompatibleException incompatible) {
            return new RecoveryClassification.Refused(key,
                    RecoveryClassification.Reason.MANIFEST_INCOMPATIBLE, incompatible.report().describe());
        } catch (RuntimeException failure) {
            ExecutionManifestStoreException classified = ExecutionManifestStoreException.unwrap(failure);
            if (classified == null) {
                return new RecoveryClassification.Refused(key,
                        RecoveryClassification.Reason.UNAVAILABLE, "the manifest could not be verified");
            }
            return new RecoveryClassification.Refused(key,
                    classified.retryability() == Retryability.DETERMINISTIC_REJECT
                            ? RecoveryClassification.Reason.MANIFEST_UNRESOLVED
                            : RecoveryClassification.Reason.UNAVAILABLE,
                    classified.failure().describe());
        }
    }

    /**
     * Converts a definition failure into a verdict.
     *
     * <p>A classified store failure carries its own content-safe diagnosis. Anything else — most
     * commonly a document that loaded but did not parse as a legal graph — is reported with a fixed
     * string rather than the exception's message, because that message is derived from authored
     * document content and this text reaches operator logs.</p>
     */
    private static RecoveryClassification refusedDefinition(ExecutionKey key, RuntimeException failure) {
        GraphDefinitionStoreException classified = GraphDefinitionStoreException.unwrap(failure);
        if (classified == null) {
            return new RecoveryClassification.Refused(key,
                    RecoveryClassification.Reason.DEFINITION_UNRESOLVED,
                    "the pinned graph definition did not rebuild into a usable graph");
        }
        return new RecoveryClassification.Refused(key,
                classified.retryability() == Retryability.DETERMINISTIC_REJECT
                        ? RecoveryClassification.Reason.DEFINITION_UNRESOLVED
                        : RecoveryClassification.Reason.UNAVAILABLE,
                classified.failure().describe());
    }

    /**
     * Answers {@link AttemptRepeatability#UNDECLARED} for every node.
     *
     * <p>Not an omission: without an instance there is no pin, without a pin there is no document,
     * and a declaration invented without one would authorise repeating a real effect on the strength
     * of nothing. Callers that hold an instance reach {@link #declaredFor(ExecutionKey, String)},
     * which is the method this class exists to implement.</p>
     *
     * @param nodeId graph node whose declaration is requested.
     * @return always {@link AttemptRepeatability#UNDECLARED}.
     */
    @Override
    public AttemptRepeatability declaredFor(String nodeId) {
        return AttemptRepeatability.UNDECLARED;
    }

    /**
     * What {@code nodeId} declared in the document {@code key} is pinned to.
     *
     * <p>Never throws, as the contract requires. The declaration is read only from a document this
     * deployment has already classified {@link RecoveryClassification.Rehydratable}, so a node cannot
     * be found repeatable on a runtime that would refuse to rebuild the execution anyway.</p>
     *
     * @param key    tenant-scoped instance whose pinned document is consulted.
     * @param nodeId graph node whose declaration is requested.
     * @return the exact parsed declaration, or {@link AttemptRepeatability#UNDECLARED}.
     */
    @Override
    public AttemptRepeatability declaredFor(ExecutionKey key, String nodeId) {
        if (key == null || nodeId == null || nodeId.isBlank()) {
            return AttemptRepeatability.UNDECLARED;
        }
        try {
            GraphVersionPin pin = await(executions.load(key)).graphVersionPin();
            if (!classify(key, pin).rehydratable()) {
                return AttemptRepeatability.UNDECLARED;
            }
            return declarationsFor(definitionKey(key, pin)).declaredFor(nodeId);
        } catch (RuntimeException unreadable) {
            return AttemptRepeatability.UNDECLARED;
        }
    }

    private static GraphDefinitionKey definitionKey(ExecutionKey key, GraphVersionPin pin) {
        return new GraphDefinitionKey(key.tenantId(), new GraphContentId(pin.reference()));
    }

    /**
     * The declaration snapshot of one document, parsed at most once per content address.
     *
     * <p>Cached by content address rather than by instance: the address is the document's own digest,
     * so two instances pinned to the same document share one snapshot and a snapshot can never be
     * stale for the address it is filed under. The parsed graph itself is closed as soon as the
     * snapshot is taken — {@link RepeatabilityDeclarations#fromGraph} copies what it needs — so
     * nothing here retains a live graph between sweeps.</p>
     */
    private RepeatabilityDeclarations declarationsFor(GraphDefinitionKey definitionKey) {
        synchronized (parsed) {
            RepeatabilityDeclarations cached = parsed.get(definitionKey);
            if (cached != null) {
                return cached;
            }
        }
        StoredGraphDefinition stored = await(definitions.load(definitionKey));
        RepeatabilityDeclarations snapshot;
        try (GraphManager manager = GraphManager.readGraphMl(
                new ByteArrayInputStream(stored.canonical().bytes()), graphMlLimits)) {
            snapshot = RepeatabilityDeclarations.fromGraph(manager.definition().nodes(), descriptors);
        }
        synchronized (parsed) {
            parsed.putIfAbsent(definitionKey, snapshot);
            return parsed.get(definitionKey);
        }
    }

    /** Reports a refusal once, at the boundary that decided it, so it is not lost inside a boolean. */
    void report(RecoveryClassification.Refused refused) {
        LOGGER.log(System.Logger.Level.WARNING,
                "ravenroot_recovery_refused tenant={0} process_instance={1} reason={2} detail={3}",
                refused.key().tenantId(), refused.key().processInstanceId(), refused.reason(),
                refused.detail());
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            throw wrapped;
        }
    }
}
