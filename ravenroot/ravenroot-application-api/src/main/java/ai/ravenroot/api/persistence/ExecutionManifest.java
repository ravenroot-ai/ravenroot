package ai.ravenroot.api.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The immutable, tenant-scoped record of every dependency one accepted execution was resolved
 * against.
 *
 * <h2>Why graph bytes are not enough</h2>
 * <p>{@link GraphDefinitionStore} makes the exact document recoverable, and that is necessary. It is
 * not sufficient: the same document run against a different execution policy, a different unknown
 * behavior stance, a different set of node packages or different execution limits is a different
 * execution. Recovery that resolves today's environment and replays yesterday's document is not
 * reproducing the accepted work, it is running new work under an old identifier. This record is what
 * a recovery compares against so that it can tell the two apart.</p>
 *
 * <h2>One manifest per process instance, write-once</h2>
 * <p>The manifest is keyed by {@link ExecutionKey}, the same granularity at which
 * {@link GraphVersionPin} is already write-once, because the dependencies a traversal resolves are
 * the ones its process instance was admitted with. Pinning per traversal would allow one instance's
 * traversals to disagree about what the instance is, which is not a state any recovery could act on.
 * A second pin carrying different content is refused with
 * {@link ExecutionManifestStoreFailure.ManifestConflict}; a byte-identical repeat converges, so a
 * long-lived deployment's thousandth traversal pays a lookup rather than a thousandth row.</p>
 *
 * <h2>What is pinned by reference rather than by field</h2>
 * <p>{@code graphContentId} is the address {@link GraphDefinitionStore} already files the document
 * under, and it is byte-identical to the reference {@link GraphVersionPin} records. The manifest
 * therefore extends that pin instead of forking a second graph identity, and a program node's source
 * digest — which is derived from properties inside the document — is pinned transitively by pinning
 * the document.</p>
 *
 * <h2>No secrets, by shape</h2>
 * <p>Every field of this record and of {@link ResolvedRuntimeProfile} is an integer, an instant, a
 * constrained identifier or a hexadecimal digest. There is no free-form value channel, so a
 * credential, a bearer token or a mutable authorization snapshot cannot be represented, let alone
 * persisted. That is a structural property rather than a redaction rule, which is why it also holds
 * for every projection of a manifest.</p>
 *
 * @param formatVersion version of this manifest's own field layout.
 * @param key tenant-scoped process instance this manifest was pinned for.
 * @param graphContentId address of the canonical document the execution was accepted against.
 * @param graphIdentity logical graph and version identity that document was bound under.
 * @param runtime semantic dependencies resolved for this execution other than the document.
 * @param nodePackages node packages resolved for this execution, sorted and free of duplicates.
 * @param pinnedAt instant at which the runtime resolved these dependencies.
 */
public record ExecutionManifest(int formatVersion, ExecutionKey key, GraphContentId graphContentId,
                                GraphDefinitionIdentity graphIdentity, ResolvedRuntimeProfile runtime,
                                List<PinnedNodePackage> nodePackages, Instant pinnedAt) {

    /** The layout this build writes. A stored manifest may carry an older one; see the store port. */
    public static final int CURRENT_FORMAT_VERSION = 1;

    /**
     * The largest number of node packages one manifest may pin.
     *
     * <p>A bound rather than a limit discovered in production: the manifest is read on every
     * recovery, and an unbounded list would make a recovery's cost a function of how many packages a
     * deployment happened to install. The value is far above any plausible installation.
     */
    public static final int MAX_NODE_PACKAGES = 1_024;

    /** Rejects a manifest that could not stably identify what an execution was admitted against. */
    public ExecutionManifest {
        if (formatVersion <= 0) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(graphContentId, "graphContentId");
        Objects.requireNonNull(graphIdentity, "graphIdentity");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(pinnedAt, "pinnedAt");
        Objects.requireNonNull(nodePackages, "nodePackages");
        if (nodePackages.size() > MAX_NODE_PACKAGES) {
            throw new IllegalArgumentException("a manifest may pin at most " + MAX_NODE_PACKAGES
                    + " node packages; got " + nodePackages.size());
        }
        var sorted = new java.util.ArrayList<>(nodePackages);
        sorted.forEach(pinned -> Objects.requireNonNull(pinned, "nodePackage"));
        java.util.Collections.sort(sorted);
        var seen = new java.util.HashSet<String>();
        for (PinnedNodePackage pinned : sorted) {
            if (!seen.add(pinned.packageId())) {
                throw new IllegalArgumentException("node package '" + pinned.packageId()
                        + "' is pinned more than once; one package resolves to one identity");
            }
        }
        nodePackages = List.copyOf(sorted);
    }

    /**
     * Derives this manifest's integrity address from its fields.
     *
     * @return the canonical digest of this manifest.
     */
    public ExecutionManifestDigest digest() {
        return ExecutionManifestDigest.of(this);
    }

    /**
     * The definition-store address this manifest pins the execution's document at.
     *
     * @return tenant-scoped key of the canonical definition this execution was accepted against.
     */
    public GraphDefinitionKey definitionKey() {
        return new GraphDefinitionKey(key.tenantId(), graphContentId);
    }
}
