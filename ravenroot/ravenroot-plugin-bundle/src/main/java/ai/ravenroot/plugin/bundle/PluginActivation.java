package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodePackage;

import java.io.IOException;
import java.util.List;

/**
 * The result of {@link PluginBundleLoader#load}: every activated package, and ownership of the
 * classloaders that produced them (PLAT-12).
 *
 * <h2>Ownership and shutdown</h2>
 * <p>This provides defined ownership and closure in place of relying on {@code java -jar}:
 * {@link #close()} closes every per-bundle classloader this activation created.
 * Bundles are immutable, read-only build material with no hot-reload story, so the only lifecycle
 * event that matters is orderly shutdown -- wired into the same shutdown hook that already closes the
 * server, the engine and the audit trail in {@code RavenrootServerMain}.</p>
 */
public final class PluginActivation implements AutoCloseable {

    static final PluginActivation EMPTY = new PluginActivation(List.of(), List.of(), List.of());

    private final List<NodePackage> packages;
    private final List<PluginManifest> manifests;
    private final List<PluginClassLoader> classLoaders;

    PluginActivation(List<NodePackage> packages, List<PluginManifest> manifests, List<PluginClassLoader> classLoaders) {
        this.packages = List.copyOf(packages);
        this.manifests = List.copyOf(manifests);
        this.classLoaders = List.copyOf(classLoaders);
    }

    /** Every activated {@code NodePackage}, ready to merge with {@code NodePackageLoader}'s own list. */
    public List<NodePackage> packages() {
        return packages;
    }

    /**
     * The manifest of every bundle that was activated, one per enabled id -- not one per activated
     * package, since a single bundle may declare more than one {@code nodePackageClasses} entry. For
     * the security-sensitive startup inventory (id, version, digest, no secrets).
     */
    public List<PluginManifest> manifests() {
        return manifests;
    }

    /** Closes every per-bundle classloader this activation created. Never throws; best-effort. */
    @Override
    public void close() {
        for (PluginClassLoader loader : classLoaders) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // A bundle jar failing to close cleanly during shutdown is not actionable; the process
                // is exiting either way.
            }
        }
    }
}
