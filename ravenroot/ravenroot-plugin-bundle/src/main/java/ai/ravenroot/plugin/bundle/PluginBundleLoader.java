package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodePackage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns {@code RAVENROOT_ENABLED_PLUGINS} and the installed bundles under a plugins directory into
 * activated {@link NodePackage} instances (PLAT-12) -- the first and only place in this module
 * that loads a class.
 *
 * <h2>Presence must still never execute</h2>
 * <p>{@link #load(Path, Set)} with an empty {@code enabledIds} does no I/O and touches no bundle at
 * all: not the plugins directory, not a single manifest, nothing. A bundle can be installed, baked
 * into the image, checksummed and structurally perfect, and this method will not so much as list the
 * directory it lives in unless its id is named in the allowlist. The security tests demonstrate
 * that removing the allowlist check would activate an unnamed bundle.</p>
 *
 * <h2>What runs when something IS enabled</h2>
 * <p>Every installed bundle is re-validated (not just the enabled ones) to build the id-to-directory
 * index this class needs, because a directory's name is not required to equal its manifest's {@code
 * id} -- so there is no cheaper way to find "which directory declares id X" than reading every
 * manifest. That scan is pure data inspection by the validator, never a
 * classloader; only the bundles actually named in the allowlist go on to {@link #activate}, which is
 * the one method in this file that calls {@code Class.forName}.</p>
 *
 * <h2>Ordering</h2>
 * <p>Unknown id (a name/directory-listing check) before duplicate id (a string comparison over
 * already-parsed manifests) before classloading (missing class / missing dependency) -- cheapest and
 * safest first, classloading last, because it is the only step that touches a classloader at all. See
 * {@code DESIGN.md}'s "Cross-cutting rules".</p>
 *
 * <h2>What this class deliberately does not do</h2>
 * <p>It never calls an audit sink and never prints anything. Every failure here is a thrown {@link
 * PluginBundleException}; the caller (a single method in {@code RavenrootServerMain}) is the only
 * place that decides what reaches the console and what reaches the audit trail, and in what order --
 * console first, unconditionally, audit attempted afterward in its own try/catch that can never
 * suppress the original diagnostic. Splitting that ordering across two classes would have meant two
 * places that had to agree on it instead of one.</p>
 */
public final class PluginBundleLoader {

    /** Comma-separated bundle ids to activate. Unset or blank means none -- the default posture. */
    public static final String ENVIRONMENT_VARIABLE = "RAVENROOT_ENABLED_PLUGINS";

    private PluginBundleLoader() {
    }

    /** The ids named by {@link #ENVIRONMENT_VARIABLE} in {@code environment}, in the order given. */
    public static Set<String> enabledIdsFromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return parseEnabledIds(environment.get(ENVIRONMENT_VARIABLE));
    }

    static Set<String> parseEnabledIds(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Activates every bundle named in {@code enabledIds}.
     *
     * @throws PluginBundleException on the first unknown id, duplicate id, invalid/tampered/
     *                               incompatible installed bundle, missing class, class that is not
     *                               a {@code NodePackage}, uninstantiable class, or missing
     *                               dependency. Nothing partially activates: a caller only ever
     *                               receives a fully activated {@link PluginActivation} or an
     *                               exception, never a mix.
     */
    public static PluginActivation load(Path pluginsDir, Set<String> enabledIds) {
        Objects.requireNonNull(pluginsDir, "pluginsDir");
        Objects.requireNonNull(enabledIds, "enabledIds");
        if (enabledIds.isEmpty()) {
            return PluginActivation.EMPTY;
        }

        Map<String, InstalledBundle> installed = scanInstalled(pluginsDir);

        for (String id : enabledIds) {
            if (!installed.containsKey(id)) {
                throw PluginBundleRejection.unknownPluginId(id);
            }
        }

        var packages = new ArrayList<NodePackage>();
        var manifests = new ArrayList<PluginManifest>();
        var classLoaders = new ArrayList<PluginClassLoader>();
        try {
            for (String id : enabledIds) {
                InstalledBundle bundle = installed.get(id);
                PluginClassLoader loader = new PluginClassLoader(id, bundleClasspath(bundle), classLoaderParent());
                // Added to the close-list before any class is touched: a failure partway through this
                // bundle's own classes, or a later bundle's, must still leave every classloader
                // created so far closeable -- see the catch below, which is what actually closes them.
                classLoaders.add(loader);
                for (String className : bundle.manifest().nodePackageClasses()) {
                    packages.add(instantiate(id, className, loader));
                }
                manifests.add(bundle.manifest());
            }
        } catch (RuntimeException activationFailed) {
            // This method never returns a partially-built PluginActivation for the caller to close --
            // it either returns a complete one or throws before returning anything at all, which means
            // the classloaders created before the failure would otherwise never be reachable by
            // anyone. Closing them here, not leaving it to PluginActivationOrchestrator, is what makes
            // that true: that caller's own try/catch around NodePackages.registerAll only ever sees a
            // PluginActivation that already exists, never one still under construction.
            for (PluginClassLoader loader : classLoaders) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                    // Best-effort: the process is already failing for a different, more important reason.
                }
            }
            throw activationFailed;
        }

        return new PluginActivation(packages, manifests, classLoaders);
    }

    private static ClassLoader classLoaderParent() {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : PluginBundleLoader.class.getClassLoader();
    }

    /**
     * Every installed bundle, validated, keyed by its manifest's own {@code id} -- not by directory
     * name, which is not required to match it. A duplicate id among ANY installed bundles fails here,
     * even if only one of the two is ever enabled: two bundles claiming the same identity is a real
     * integrity problem in the image, not merely a runtime activation concern.
     */
    private static Map<String, InstalledBundle> scanInstalled(Path pluginsDir) {
        if (!Files.isDirectory(pluginsDir)) {
            return Map.of();
        }
        List<Path> candidates;
        try (var listing = Files.list(pluginsDir)) {
            candidates = listing.filter(Files::isDirectory).sorted().toList();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
        var byId = new LinkedHashMap<String, InstalledBundle>();
        for (Path directory : candidates) {
            PluginManifest manifest = PluginBundleValidator.validate(directory);
            if (byId.put(manifest.id(), new InstalledBundle(manifest, directory)) != null) {
                throw PluginBundleRejection.duplicatePluginId(manifest.id());
            }
        }
        return byId;
    }

    private static URL[] bundleClasspath(InstalledBundle bundle) {
        var artifacts = new ArrayList<PluginArtifact>();
        artifacts.add(bundle.manifest().mainArtifact());
        artifacts.addAll(bundle.manifest().dependencyArtifacts());
        var urls = new ArrayList<URL>(artifacts.size());
        for (PluginArtifact artifact : artifacts) {
            try {
                urls.add(bundle.directory().resolve(artifact.fileName()).toUri().toURL());
            } catch (MalformedURLException impossible) {
                // A Path already resolved on this filesystem always converts to a valid file: URL.
                throw new IllegalStateException(impossible);
            }
        }
        return urls.toArray(URL[]::new);
    }

    /**
     * Loads, verifies and constructs one declared {@code NodePackage} class -- the only method in
     * this module that calls {@code Class.forName}, and it is reached only for a bundle whose id was
     * named in the allowlist.
     */
    private static NodePackage instantiate(String pluginId, String className, PluginClassLoader loader) {
        Class<?> type;
        try {
            type = Class.forName(className, true, loader);
        } catch (ClassNotFoundException notFound) {
            throw PluginBundleRejection.missingClass(pluginId, className);
        } catch (LinkageError linkageFailure) {
            // A class that IS present but references something absent from the bundle's own
            // classpath fails to link/verify before ClassNotFoundException would ever apply.
            throw PluginBundleRejection.missingDependency(pluginId, className);
        }
        if (!NodePackage.class.isAssignableFrom(type)) {
            throw PluginBundleRejection.notANodePackage(pluginId, className);
        }
        NodePackage instance;
        try {
            instance = (NodePackage) type.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException noPublicNoArgConstructor) {
            throw PluginBundleRejection.instantiationFailed(pluginId, className);
        } catch (LinkageError linkageFailure) {
            throw PluginBundleRejection.missingDependency(pluginId, className);
        } catch (ReflectiveOperationException | RuntimeException constructionFailed) {
            throw PluginBundleRejection.instantiationFailed(pluginId, className);
        }
        try {
            // Forces additional linking now, at activation, rather than deferring a bundle's first
            // real contact with its own dependencies to a later graph execution -- "missing
            // dependency" must prevent startup, not surface as a mid-traversal failure.
            instance.behaviors();
        } catch (LinkageError linkageFailure) {
            throw PluginBundleRejection.missingDependency(pluginId, className);
        }
        return instance;
    }

    private record InstalledBundle(PluginManifest manifest, Path directory) {
    }
}
