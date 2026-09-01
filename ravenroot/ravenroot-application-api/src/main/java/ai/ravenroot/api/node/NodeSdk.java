package ai.ravenroot.api.node;

import java.util.Objects;

/**
 * The versioned contract a third-party node package is built against (CORE-06).
 *
 * <h2>Why a contract string rather than a Maven version</h2>
 * <p>This follows the idiom the repository already uses for every other public contract —
 * {@code ravenroot.payload/1}, {@code ravenroot.provenance/1}, {@code ravenroot.error/1},
 * {@code ravenroot.execution/1} — and it follows the rule those established: <strong>an unknown
 * contract is rejected; an unknown member inside a known contract is ignored.</strong> A Maven
 * version says what a jar was resolved as, which is a build fact. A contract string says what shape
 * of SDK a package was written against, which is the thing the runtime has to agree with.</p>
 *
 * <h2>The constant is inlined, and that is the mechanism, not an accident</h2>
 * <p>{@link #CONTRACT} is a compile-time {@code String} constant, so a package that returns it from
 * {@link NodePackage#sdkContract()} has the <em>value it compiled against</em> baked into its own
 * class file. Run that package against a future runtime whose {@code CONTRACT} has moved on and it
 * still reports the old value, which is exactly what makes the skew detectable. A package that
 * computed its contract at runtime — by reading this field reflectively, say — would report whatever
 * the host runtime says and would always look compatible, which is the failure this check exists to
 * prevent. Node authors should write {@code NodeSdk.CONTRACT}, not the literal, so that recompiling
 * is what moves them forward.</p>
 *
 * <h2>What this does not do</h2>
 * <p>It does not express a compatibility <em>promise</em>. Ravenroot has never published an SDK
 * artifact, so there is nothing yet to promise about. This is the mechanism that would let a future
 * compatibility policy be stated and enforced.</p>
 */
public final class NodeSdk {

    /**
     * The node-author contract this runtime implements.
     *
     * <p>Deliberately a constant expression so that it is inlined into every package compiled
     * against it. See the class documentation.</p>
     */
    public static final String CONTRACT = "ravenroot.node-sdk/2";

    /** Last contract before package-scoped services were added; retained for binary compatibility. */
    public static final String LEGACY_CONTRACT = "ravenroot.node-sdk/1";

    /**
     * The property namespace reserved for the runtime's own operative state, which a node package
     * may not declare and a graph may not write.
     *
     * <p>Published here because it is part of what a node author has to know: a descriptor declaring
     * a property under this prefix describes a property no graph can ever set. The runtime's own
     * enforcement of the namespace is authoritative and lives with the graph model; a test pins the
     * two to the same value so they cannot drift apart.</p>
     */
    public static final String RESERVED_PROPERTY_PREFIX = "ravenroot.";

    private NodeSdk() {
    }

/**
 * Tests a proposed property name against the namespace reserved to Ravenroot runtime state.
 *
 * @param name a candidate descriptor or graph-property name; {@code null} is not reserved
 * @return {@code true} when trimming and case-folding put the name under
 *         {@link #RESERVED_PROPERTY_PREFIX}
 */
    public static boolean isReservedProperty(String name) {
        return name != null
                && name.trim().toLowerCase(java.util.Locale.ROOT).startsWith(RESERVED_PROPERTY_PREFIX);
    }

    /**
     * Whether {@code contract} is a contract this runtime can host.
     *
     * <p>Explicit enumeration, deliberately. A range check would require this version to know which
     * future versions will turn out to be compatible with it. SDK /1 is enumerated because the /2
     * runtime deliberately retains its binary bridge; unknown past and future contracts remain
     * refused.</p>
     * @param contract a package-declared SDK contract, possibly {@code null}
     * @return whether this runtime deliberately retains a compatibility bridge for that exact contract
     */
    public static boolean supports(String contract) {
        return CONTRACT.equals(contract) || LEGACY_CONTRACT.equals(contract);
    }

    /**
     * Verifies that {@code nodePackage} can be hosted, throwing a diagnostic failure when it cannot.
     *
     * <p>Fails closed: a package whose contract this runtime does not recognise is refused rather
     * than loaded optimistically. The alternative — loading it and discovering the mismatch when a
     * traversal reaches one of its nodes — converts a deployment error into a runtime one, at the
     * point where it is most expensive and least attributable.</p>
     *
     * @param nodePackage the package about to be registered; its identifier and declared contract are
     *                    used only to make a refusal actionable
     * @throws NullPointerException when {@code nodePackage} is {@code null}
     * @throws IncompatibleNodePackageException when the declared contract is absent or unsupported
     */
    public static void requireSupported(NodePackage nodePackage) {
        Objects.requireNonNull(nodePackage, "nodePackage");
        String declared = nodePackage.sdkContract();
        if (declared == null || declared.isBlank()) {
            throw new IncompatibleNodePackageException(nodePackage.id(), declared,
                    "declares no Node SDK contract. A node package must return NodeSdk.CONTRACT from "
                            + "sdkContract() so the runtime can tell which SDK shape it was built against.");
        }
        if (!supports(declared)) {
            throw new IncompatibleNodePackageException(nodePackage.id(), declared,
                    "was built against Node SDK contract '" + declared + "', which this runtime does not "
                            + "host. This runtime hosts '" + CONTRACT + "' and '" + LEGACY_CONTRACT
                            + "'. Rebuild the package against "
                            + "this runtime's SDK, or deploy a runtime that hosts '" + declared + "'.");
        }
    }

    /** A node package this runtime cannot host, identified before any of its behaviors is registered. */
    public static final class IncompatibleNodePackageException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        /** Identifier supplied by the refused package, retained for diagnostics only. */
        private final String packageId;
        /** The incompatible contract string, or {@code null} when the package supplied none. */
        private final String declaredContract;

        IncompatibleNodePackageException(String packageId, String declaredContract, String problem) {
            super("Node package '" + packageId + "' " + problem);
            this.packageId = packageId;
            this.declaredContract = declaredContract;
        }

/**
 * The offending package's own identifier, for diagnostics. Never an authority.
         * @return the identifier reported by the package that was refused
 */
        public String packageId() {
            return packageId;
        }

/**
 * The contract the package declared, or {@code null} when it declared none.
         * @return the contract value that the runtime did not accept, possibly {@code null}
 */
        public String declaredContract() {
            return declaredContract;
        }
    }
}
