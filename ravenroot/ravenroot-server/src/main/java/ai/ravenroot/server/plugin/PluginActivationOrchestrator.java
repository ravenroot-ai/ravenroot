package ai.ravenroot.server.plugin;

import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import ai.ravenroot.core.runtime.NodePackageLoader;
import ai.ravenroot.core.runtime.NodePackageServiceRegistry;
import ai.ravenroot.core.runtime.NodePackages;
import ai.ravenroot.plugin.bundle.PluginActivation;
import ai.ravenroot.plugin.bundle.PluginBundleLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The full plugin registration sequence for server startup (PLAT-12): resolve the plugins
 * directory and allowlist from environment, activate, merge with {@link NodePackageLoader}'s own
 * operator-named classes, register.
 *
 * <h2>Deliberately silent</h2>
 * <p>This class never prints and never calls an audit sink. It either returns a fully populated
 * {@link Registered} or throws the first failure it hit; the caller ({@code RavenrootServerMain})
 * decides what reaches the console and the audit trail, and in what order, using {@link
 * PluginActivationDiagnostics} on whatever this class throws. That split is what makes this class
 * unit-testable without a JVM exit or a real {@code AuditTrail}: given a real plugins directory and
 * environment map, {@link #register} either returns or throws, and a test can assert on which.</p>
 */
public final class PluginActivationOrchestrator {

    /** Where installed plugin bundles live inside the image. Matches the Dockerfile's runtime layout. */
    public static final String PLUGINS_DIR_ENVIRONMENT_VARIABLE = "RAVENROOT_PLUGINS_INSTALL_DIR";
    static final String DEFAULT_PLUGINS_DIR = "/opt/ravenroot/plugins";

    private PluginActivationOrchestrator() {
    }

    /**
     * The public result contract. Keep its two record components unchanged: adding package
     * inventory as a third component changes the canonical constructor descriptor and breaks already
     * compiled embedders even if a convenience constructor is retained.
     */
    public record Registered(BehaviorRegistry registry, PluginActivation activation) {
    }

    /** The composition-root result, with the enabled package inventory needed for ingress validation. */
    public record Registration(Registered registered, List<NodePackage> packages) {
        public Registration {
            Objects.requireNonNull(registered, "registered");
            packages = List.copyOf(packages);
        }
    }

    /**
     * @throws RuntimeException the first failure encountered -- a {@code PluginBundleException} from
     *                          {@link PluginBundleLoader}, or whatever {@link NodePackages#registerAll}
     *                          itself throws (duplicate behavior id, an incompatible SDK contract, a
     *                          malformed package). Any classloaders {@link PluginBundleLoader} already
     *                          created are closed before this method throws, in either case: a caller
     *                          that catches this exception owns nothing further to clean up.
     */
    public static Registered register(BehaviorRegistry base, Map<String, String> environment) {
        return registerWithInventory(base, environment).registered();
    }

    /** Registers packages against an explicit operator-composed service registry. */
    public static Registered register(BehaviorRegistry base, Map<String, String> environment,
                                      NodePackageServiceRegistry services) {
        return registerWithInventory(base, environment, services).registered();
    }

    /**
     * Registration variant for the server composition root. The legacy {@link #register} method and
     * {@link Registered} binary shape remain unchanged for embedders.
     */
    public static Registration registerWithInventory(BehaviorRegistry base, Map<String, String> environment) {
        return registerWithInventory(base, environment, NodePackageServiceRegistry.empty());
    }

    /**
     * The composition-root variant that also carries the operator's service grants.
     *
     * <p>This overload uses the same registration path as the two-argument method:
     * {@link #registerWithInventory(BehaviorRegistry, Map)} retains its descriptor and passes
     * {@link NodePackageServiceRegistry#empty()}, so
     * an embedder that never composes a grant is unaffected in behaviour and in binary shape.</p>
     */
    public static Registration registerWithInventory(BehaviorRegistry base, Map<String, String> environment,
                                                      NodePackageServiceRegistry services) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(services, "services");

        Path pluginsDir = Path.of(environment.getOrDefault(PLUGINS_DIR_ENVIRONMENT_VARIABLE, DEFAULT_PLUGINS_DIR));
        Set<String> enabledIds = PluginBundleLoader.enabledIdsFromEnvironment(environment);
        PluginActivation activation = PluginBundleLoader.load(pluginsDir, enabledIds);

        List<NodePackage> merged = new ArrayList<>(NodePackageLoader.fromEnvironment(environment));
        merged.addAll(activation.packages());

        BehaviorRegistry registry;
        try {
            registry = NodePackages.registerAll(base, merged, services);
        } catch (RuntimeException registrationFailed) {
            activation.close();
            throw registrationFailed;
        }
        return new Registration(new Registered(registry, activation), merged);
    }
}
