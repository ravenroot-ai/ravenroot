package ai.ravenroot.api.persistence;

import java.util.List;

/**
 * One node package as it was resolved for an accepted execution.
 *
 * <h2>The package's own strings are digested, not recorded</h2>
 * <p>{@link ai.ravenroot.api.node.NodePackage#version()} and
 * {@link ai.ravenroot.api.node.NodePackage#sdkContract()} are declared by the package itself. That
 * contract has never constrained their shape and describes the version as being for diagnostics, so
 * a package is entitled to return {@code "1.0 beta"}, an empty string, or nothing at all. A manifest
 * that validated them would refuse a package the runtime has always accepted — and would do it
 * inside plugin activation at start-up, turning a working deployment into one that will not boot.
 * Digesting is what lets this record be exact about change without imposing a rule the SDK never
 * made: any difference in either string produces a different digest, and no string a package can
 * return is rejected.</p>
 *
 * <p>{@code packageId} is kept readable because it is <em>already</em> constrained, by the node
 * package registration path, to a lowercase safe token — a stricter shape than this record's own.
 * Recording it costs nothing new and is what makes a difference report name the package an operator
 * has to look at.</p>
 *
 * <h2>What this cannot pin</h2>
 * <p>There is deliberately <strong>no content digest</strong>. A digest of the installed bundle does
 * exist and is verified against the bytes on disk when the bundle is loaded, but it is held by the
 * bundle loader in the composition root and is not carried to the engine, so nothing on an admission
 * path can observe it. Recording a field that no admission path can fill would produce a manifest
 * whose most load-bearing entry was always empty, which is worse than not claiming it. A package
 * republished under an unchanged version and SDK contract is therefore not detected.</p>
 *
 * @param packageId stable identifier of the node package that supplied behaviors to this execution.
 * @param identityDigest digest of the package's declared version and SDK contract.
 */
public record PinnedNodePackage(String packageId, String identityDigest)
        implements Comparable<PinnedNodePackage> {

    /** Domain tag separating a package identity digest from every other SHA-256 use. */
    public static final String IDENTITY_DOMAIN = "ravenroot.execution-manifest.node-package.v1";

    /** Rejects a package identity that cannot be stably compared across a restart. */
    public PinnedNodePackage {
        packageId = ManifestTokens.requireToken(packageId, "packageId");
        identityDigest = ManifestTokens.requireSha256Hex(identityDigest, "identityDigest");
    }

    /**
     * Pins one package from the two strings it declares about itself.
     *
     * <p>Accepts any value either string can take, {@code null} included: a {@code null} is encoded
     * as an empty string, which is a distinct input from every non-empty one and therefore a distinct
     * digest. Nothing here validates, because there is nothing here entitled to.</p>
     *
     * @param packageId stable identifier of the node package; already a validated token at registration.
     * @param version the package's own declared build version, or {@code null} if it declares none.
     * @param sdkContract the Node SDK contract the package declares it was built against, or {@code null}.
     * @return the pinned package identity.
     */
    public static PinnedNodePackage of(String packageId, String version, String sdkContract) {
        return new PinnedNodePackage(packageId, ExecutionManifestDigest.component(IDENTITY_DOMAIN,
                List.of(version == null ? "" : version, sdkContract == null ? "" : sdkContract)));
    }

    /**
     * Orders packages by id, then by identity digest.
     *
     * <p>A manifest sorts its packages with this order before it is digested, so two runtimes that
     * resolved the same packages in a different registration order produce the same manifest digest
     * rather than two that disagree for no semantic reason.</p>
     *
     * @param other package to compare this one against.
     * @return standard comparison result over id and then identity digest.
     */
    @Override
    public int compareTo(PinnedNodePackage other) {
        int byId = packageId.compareTo(other.packageId);
        return byId != 0 ? byId : identityDigest.compareTo(other.identityDigest);
    }
}
