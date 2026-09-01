package ai.ravenroot.api.node;

import java.util.List;

/**
 * A deployable unit of third-party node types, and the thing whose compatibility is checked
 * (CORE-06).
 *
 * <h2>The package is the versioned unit, not the individual behavior</h2>
 * <p>Versioning spans the node context, the result, the property schemas, the
 * capabilities and the package metadata. Those are not five independent versions; they are one SDK
 * shape. Versioning them separately would be five promises to keep in step instead of one, and the
 * first time two of them disagreed the runtime would have no way to say which was authoritative.
 * So exactly one contract is declared, once, per package.</p>
 *
 * <h2>How a package is trusted</h2>
 * <p>Trust comes from the deployment, never from graph content. A package becomes available because
 * an embedding application registered it in code, or because an operator named it in the runtime's
 * own configuration. <strong>No graph can name a package, a class or a behavior implementation to
 * load.</strong> A graph names a {@code behavior}, which is matched against what the deployment
 * already decided to trust; if nothing matches, nothing is loaded and nothing is searched for.</p>
 *
 * <p>Discovery is deliberately not {@code ServiceLoader}-based. An engine adapter is one provider
 * chosen by the operator; node behaviors are many and ambient, and classpath discovery would grant
 * every jar in the dependency graph — including transitive dependencies nobody chose — the standing
 * right to contribute a node type. That is a trust expansion rather than a packaging convenience.</p>
 *
 * <h2>Implementations must have a public no-argument constructor</h2>
 * <p>Only so that an operator-named package can be instantiated by the runtime at startup. The
 * class name comes from the deployment's own configuration and from nowhere else.</p>
 */
public interface NodePackage {

    /**
     * Stable identifier for this package, used in diagnostics.
     *
     * <p>Reverse-DNS is conventional ({@code com.example.ravenroot.nodes}). It identifies the
     * package to an operator reading a startup failure; it is never an authority and never
     * namespaces the behaviors themselves.</p>
     * @return package identifier for diagnostics and registration inventory, not an authority
     */
    String id();

    /**
     * This package's own version, for diagnostics.
     *
     * <p>The package's version, not the SDK's. It answers "which build of this package is
     * deployed?" and has no bearing on whether the runtime can host it — that is
     * {@link #sdkContract()}'s question alone.</p>
     * @return package build version for operator diagnostics; it is distinct from the SDK contract
     */
    String version();

    /**
     * The Node SDK contract this package was built against.
     *
     * <p>Implementations must return {@link NodeSdk#CONTRACT} rather than a string literal. The
     * constant is inlined at compile time, so returning it records the SDK the package was actually
     * compiled against and lets the runtime detect skew; a literal that happens to match today
     * silently stops being a statement about the build the moment it is copied forward.</p>
     * @return compile-time SDK contract used for compatibility admission
     */
    String sdkContract();

    /**
     * The node types this package contributes.
     *
     * <p>Behavior names must be unique within the package. A name already registered by another
     * package or by the built-in catalog is refused at registration rather than silently replacing
     * it: a node type changing meaning because of jar ordering is not a diagnosable condition.</p>
     * @return all behavior implementations contributed by this trusted package
     */
    List<NodeBehavior> behaviors();
}
