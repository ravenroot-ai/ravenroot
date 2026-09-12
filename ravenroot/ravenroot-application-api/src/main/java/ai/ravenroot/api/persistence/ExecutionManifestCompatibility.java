package ai.ravenroot.api.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The verdict of comparing a pinned manifest against what the current runtime resolves.
 *
 * <h2>Why every dimension is compared for equality and none for "close enough"</h2>
 * <p>A manifest exists so that recovery does not silently run different behavior. Any rule that
 * accepted a near match would be a rule about which differences are harmless, and this contract has
 * no basis for such a rule: an engine capability set that gained {@code DURABLE_TIMERS}, or an
 * execution limit that doubled, changes what the same document does. Equality is therefore the whole
 * test, and a deployment that wants to proceed across a difference resolves it deliberately rather
 * than having it absorbed.</p>
 *
 * <h2>What is pinned and what is compared are different sets</h2>
 * <p>A manifest also pins the graph content address and the logical graph identity, and this
 * comparison touches neither. That is not an omission: a caller obtains {@code current} by describing
 * the runtime <em>for the manifest it just read</em>, so the graph fields are copied from the pinned
 * manifest and could not differ here even if the stored document had been replaced. The graph is
 * enforced elsewhere and more strongly — {@link GraphDefinitionStore#load(GraphDefinitionKey)}
 * re-derives the document's address from its bytes on every read, so a document that no longer
 * matches its pin fails there rather than being reported as a difference here. Reading this report as
 * "everything the manifest pins agrees" would therefore be wrong; it says that every dimension
 * <em>this</em> comparison covers agrees, and the definition load says the rest.</p>
 *
 * <h2>Node packages are compared one way, deliberately</h2>
 * <p>A package the execution was admitted with that is now absent or resolves to a different
 * version is a difference. A package installed <em>since</em> acceptance that this execution never
 * used is not: refusing it would make every recovery fail after any unrelated installation, which
 * would push operators to stop pinning rather than to keep the pin meaningful. The document decides
 * which behaviors run, and the document is pinned exactly.</p>
 *
 * @param differences every dimension that disagrees, in the order this record's comparison visits them.
 * @param truncated whether further differences existed beyond {@link #MAX_DIFFERENCES} and were dropped.
 */
public record ExecutionManifestCompatibility(List<ExecutionManifestDifference> differences,
                                             boolean truncated) {

    /**
     * The largest number of differences one report carries.
     *
     * <p>Bounded because this report is returned from a recovery path and rendered into a
     * diagnostic. An unbounded report would let one badly skewed deployment produce a diagnostic
     * whose size is a function of how many packages it installed.
     */
    public static final int MAX_DIFFERENCES = 32;

    /** Rejects a report that is not an immutable, bounded list of differences. */
    public ExecutionManifestCompatibility {
        Objects.requireNonNull(differences, "differences");
        if (differences.size() > MAX_DIFFERENCES) {
            throw new IllegalArgumentException("a compatibility report carries at most "
                    + MAX_DIFFERENCES + " differences; got " + differences.size());
        }
        differences = List.copyOf(differences);
    }

    /**
     * Whether the current runtime resolves exactly what the execution was admitted against.
     *
     * @return whether no dimension disagrees.
     */
    public boolean compatible() {
        return differences.isEmpty();
    }

    /**
     * The dimensions that disagree, without the values that disagree on them.
     *
     * <p>Exists because those two are addressed to different readers.
     * {@link ExecutionManifestDifference#pinned()} and {@code observed()} describe the deployment —
     * an installed package's identity, a digest of the operator's execution limits — and belong in a
     * server-side diagnostic an operator reads. A tenant asking whether its own execution can still
     * be reproduced needs to know <em>that</em> the packages changed, not which packages a deployment
     * has installed. A projection crossing that boundary reports these and never the values.</p>
     *
     * @return each differing dimension, in this report's own order, with duplicates preserved.
     */
    public List<ExecutionManifestDifference.Dimension> dimensions() {
        return differences.stream().map(ExecutionManifestDifference::dimension).toList();
    }

    /**
     * Renders every difference as one bounded, operator-facing line.
     *
     * @return a stable description naming each differing dimension and both of its values.
     */
    public String describe() {
        if (compatible()) {
            return "compatible";
        }
        var text = new StringBuilder();
        for (ExecutionManifestDifference difference : differences) {
            if (text.length() > 0) {
                text.append("; ");
            }
            text.append(difference.describe());
        }
        if (truncated) {
            text.append("; further differences omitted");
        }
        return text.toString();
    }

    /**
     * Compares the manifest an execution was pinned with against one describing the current runtime.
     *
     * <p>Only the two manifests' dependency content participates. The tenant, the process instance
     * and the pinning instant do not: comparing a manifest against a description of now would
     * otherwise always differ on the timestamp, which says nothing about whether the execution can
     * be reproduced.</p>
     *
     * @param pinned the manifest committed when the execution was accepted.
     * @param current a manifest describing what this runtime resolves for the same execution.
     * @return the verdict, empty of differences when the two agree on every dimension.
     */
    public static ExecutionManifestCompatibility compare(ExecutionManifest pinned, ExecutionManifest current) {
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(current, "current");
        var found = new ArrayList<ExecutionManifestDifference>();
        add(found, ExecutionManifestDifference.Dimension.MANIFEST_FORMAT_VERSION,
                pinned.formatVersion(), current.formatVersion());
        ResolvedRuntimeProfile was = pinned.runtime();
        ResolvedRuntimeProfile now = current.runtime();
        add(found, ExecutionManifestDifference.Dimension.GRAPH_SCHEMA_VERSION,
                was.graphSchemaVersion(), now.graphSchemaVersion());
        add(found, ExecutionManifestDifference.Dimension.DEFINITION_FORMAT_VERSION,
                was.definitionFormatVersion(), now.definitionFormatVersion());
        add(found, ExecutionManifestDifference.Dimension.EXECUTION_POLICY,
                was.executionPolicy(), now.executionPolicy());
        add(found, ExecutionManifestDifference.Dimension.UNKNOWN_BEHAVIOR_MODE,
                was.unknownBehaviorMode(), now.unknownBehaviorMode());
        add(found, ExecutionManifestDifference.Dimension.ENGINE, was.engineDigest(), now.engineDigest());
        add(found, ExecutionManifestDifference.Dimension.EXECUTION_STORE,
                was.storeDigest(), now.storeDigest());
        add(found, ExecutionManifestDifference.Dimension.EXECUTION_LIMITS,
                was.executionLimitsDigest(), now.executionLimitsDigest());
        add(found, ExecutionManifestDifference.Dimension.PROGRAM_RUNTIME,
                was.programRuntimeDigest(), now.programRuntimeDigest());

        Map<String, PinnedNodePackage> observed = new LinkedHashMap<>();
        for (PinnedNodePackage pinnedPackage : current.nodePackages()) {
            observed.put(pinnedPackage.packageId(), pinnedPackage);
        }
        for (PinnedNodePackage required : pinned.nodePackages()) {
            PinnedNodePackage match = observed.get(required.packageId());
            if (match == null) {
                found.add(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.NODE_PACKAGE_MISSING,
                        identityOf(required), required.packageId() + "@absent"));
            } else if (!match.equals(required)) {
                found.add(new ExecutionManifestDifference(
                        ExecutionManifestDifference.Dimension.NODE_PACKAGE_CHANGED,
                        identityOf(required), identityOf(match)));
            }
        }
        boolean truncated = found.size() > MAX_DIFFERENCES;
        return new ExecutionManifestCompatibility(
                truncated ? found.subList(0, MAX_DIFFERENCES) : found, truncated);
    }

    private static String identityOf(PinnedNodePackage pinned) {
        return pinned.packageId() + "@" + pinned.identityDigest();
    }

    private static void add(List<ExecutionManifestDifference> found,
                            ExecutionManifestDifference.Dimension dimension, Object was, Object now) {
        if (!was.equals(now)) {
            found.add(new ExecutionManifestDifference(dimension, was.toString(), now.toString()));
        }
    }
}
