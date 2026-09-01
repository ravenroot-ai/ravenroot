package ai.ravenroot.core.runtime;

import ai.ravenroot.api.node.NodePackage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Instantiates the node packages an <em>operator</em> named, for the server and CLI distributions
 * (CORE-06).
 *
 * <h2>Why this exists at all</h2>
 * <p>An embedding application can already contribute node types: it builds its own
 * {@link BehaviorRegistry} and hands it to the application. The shipped server and CLI cannot —
 * their composition roots build the standard catalog and expose no hook — so "a third party can ship
 * a node type" was true only for embedders. This closes that gap without changing who decides.</p>
 *
 * <h2>The allowlist is the trust decision, and it is the operator's</h2>
 * <p>Class names come from the runtime's own configuration — an environment variable set by whoever
 * deployed the process. They are never read from a graph, a payload, a request, or any other content
 * that crosses a trust boundary at runtime. <strong>No graph can name a class to load.</strong> That
 * prohibition is the security core of CORE-06, and it is preserved here in the strongest available
 * form: this class is the only place in the runtime that turns a name into a class, and its only
 * input is deployment configuration.</p>
 *
 * <p>Deliberately not {@code ServiceLoader}. Classpath discovery would let every jar in the
 * dependency graph — including transitive dependencies the operator never chose — contribute node
 * types silently. Naming a package is one extra line for the operator who wanted it and an absolute
 * barrier for everyone who did not, which is the correct asymmetry for a mechanism whose whole
 * purpose is deciding what to trust. It also keeps the property that the set of node types in a
 * deployment can be read off its configuration rather than reconstructed from a dependency tree.</p>
 *
 * <h2>Fail closed, at startup</h2>
 * <p>Every failure here — an unknown class, a class that is not a {@link NodePackage}, a missing
 * public no-argument constructor, a constructor that throws — aborts startup with a message naming
 * the offending entry. An operator who asked for a node package and did not get it must be told; a
 * runtime that started anyway would serve graphs whose nodes silently do not exist, which is
 * precisely the diagnosis-resistant state this loader prevents.</p>
 */
public final class NodePackageLoader {

    /**
     * Environment variable holding a comma-separated list of {@link NodePackage} class names.
     *
     * <p>Empty or unset means no third-party node packages, which is the default posture of the
     * shipped distribution.</p>
     */
    public static final String ENVIRONMENT_VARIABLE = "RAVENROOT_NODE_PACKAGES";

    private NodePackageLoader() {
    }

    /** The packages named by {@link #ENVIRONMENT_VARIABLE} in {@code environment}. */
    public static List<NodePackage> fromEnvironment(java.util.Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return fromCommaSeparated(environment.get(ENVIRONMENT_VARIABLE));
    }

    /**
     * The packages named in {@code classNames}, in the order given.
     *
     * @param classNames comma-separated fully-qualified class names, or {@code null}/blank for none
     */
    public static List<NodePackage> fromCommaSeparated(String classNames) {
        if (classNames == null || classNames.isBlank()) {
            return List.of();
        }
        var packages = new ArrayList<NodePackage>();
        for (String entry : Arrays.stream(classNames.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .toList()) {
            packages.add(instantiate(entry));
        }
        return List.copyOf(packages);
    }

    private static NodePackage instantiate(String className) {
        Class<?> type;
        try {
            // The application class loader, so a package is visible exactly when the operator put it
            // on the classpath. No custom loader, no URL, no path: this cannot reach a class the
            // deployment did not already install.
            type = Class.forName(className, true, Thread.currentThread().getContextClassLoader() == null
                    ? NodePackageLoader.class.getClassLoader()
                    : Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException notFound) {
            throw new IllegalArgumentException(ENVIRONMENT_VARIABLE + " names node package class '"
                    + className + "', which is not on the classpath. Add the package's jar to the "
                    + "deployment, or remove the entry.", notFound);
        }
        if (!NodePackage.class.isAssignableFrom(type)) {
            throw new IllegalArgumentException(ENVIRONMENT_VARIABLE + " names class '" + className
                    + "', which does not implement " + NodePackage.class.getName() + ".");
        }
        try {
            return (NodePackage) type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException noConstructor) {
            throw new IllegalArgumentException("Node package class '" + className
                    + "' has no public no-argument constructor.", noConstructor);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            throw new IllegalArgumentException("Node package class '" + className
                    + "' could not be instantiated: " + failed, failed);
        }
    }
}
