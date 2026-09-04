package ai.ravenroot.api.persistence;

/**
 * One node package as it was resolved for an accepted execution.
 *
 * <h2>What this pins, and what it cannot</h2>
 * <p>A node package declares three facts about itself: a stable id, its own build version, and the
 * Node SDK contract it was compiled against. All three are recorded here, so a package rebuilt under
 * a new version, or recompiled against a different SDK contract, is a detectable difference rather
 * than an invisible one.</p>
 *
 * <p>There is deliberately <strong>no content digest</strong>. A digest of the installed bundle does
 * exist and is verified against the bytes on disk when the bundle is loaded, but it is held by the
 * bundle loader in the composition root and is not carried to the engine, so nothing on an admission
 * path can observe it. Recording a field that no admission path can fill would produce a manifest
 * whose most load-bearing entry was always empty, which is worse than not claiming it: this record
 * pins the identity the runtime actually resolves. A package republished under an unchanged version
 * with changed content is therefore not detected by this manifest.</p>
 *
 * @param packageId stable identifier of the node package that supplied behaviors to this execution.
 * @param version the package's own build version as it declared at registration.
 * @param sdkContract the Node SDK contract the package was compiled against.
 */
public record PinnedNodePackage(String packageId, String version, String sdkContract)
        implements Comparable<PinnedNodePackage> {

    /** Rejects a package identity that cannot be stably compared across a restart. */
    public PinnedNodePackage {
        packageId = ManifestTokens.requireToken(packageId, "packageId");
        version = ManifestTokens.requireToken(version, "version");
        sdkContract = ManifestTokens.requireToken(sdkContract, "sdkContract");
    }

    /**
     * Orders packages by id, then version, then SDK contract.
     *
     * <p>A manifest sorts its packages with this order before it is digested, so two runtimes that
     * resolved the same packages in a different registration order produce the same manifest digest
     * rather than two that disagree for no semantic reason.</p>
     *
     * @param other package to compare this one against.
     * @return standard comparison result over id, version and SDK contract in that order.
     */
    @Override
    public int compareTo(PinnedNodePackage other) {
        int byId = packageId.compareTo(other.packageId);
        if (byId != 0) {
            return byId;
        }
        int byVersion = version.compareTo(other.version);
        return byVersion != 0 ? byVersion : sdkContract.compareTo(other.sdkContract);
    }
}
