package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.provenance.SyntheticProvenance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The one implementation of "does this plugin bundle declare a generative capability?".
 *
 * <h2>The gap this closes</h2>
 * <p>{@code ReleaseArtifactBoundaryChecks}'s existing predicates all key on the {@code ModelProvider}
 * / {@code AgentRuntime} SPI: P1/P2 look for concrete implementations, P6 for a {@code
 * META-INF/services} registration, P3 for a registry call reachable from a composition root. Once the
 * AI nodes leave the core and become a plugin bundle carrying its own adapter, a bundle node that
 * reaches a model endpoint through its <em>own</em> HTTP client implements none of that SPI and is
 * loaded reflectively by {@code PluginClassLoader}, so it is reachable from no {@code main} either.
 * Every existing predicate is silent on it. This class is the predicate that is not: it reads what
 * the bundle declares about its node types, and is indifferent to how those node types are wired.</p>
 *
 * <h2>Why a manifest field rather than bytecode</h2>
 * <p>A node type's capabilities exist in a {@code NodeTypeDescriptor} built in code, so there is
 * nothing structural to detect: no interface to look for, no annotation, no service file. The two
 * alternatives to reading {@link PluginManifest#NODE_CAPABILITIES_KEY} were scanning bundle bytecode
 * for the string constants {@code "ai"}/{@code "agentic"} — which matches any two-letter string
 * constant anywhere in the jar and cannot tell a capability from a map key, so it produces both false
 * positives and (for a capability assembled at runtime) false negatives — and loading the bundle's
 * classes inside the check, which would execute untrusted third-party code inside a release gate, the
 * one place with the least business doing so. Declaring it at generation time, from the descriptor
 * the toolchain has already constructed, is the only option where the value comes from the authority
 * ({@code NodeTypeDescriptor}) and the check stays inert data inspection.</p>
 *
 * <h2>What a self-declared field can and cannot carry</h2>
 * <p>A manifest can lie. That matters for a consumer drawing a <em>trust</em> conclusion about a
 * third-party bundle, and it does not matter for the consumers here, which is why they are the only
 * ones wired up: this scan runs over bundles <b>this project itself builds and publishes</b> — the
 * release gate over the repository's own convention directory, and CI over what {@code
 * PUBLISHED_PLUGINS} just built through {@code PluginCli generate-manifest}. In that setting the
 * declaring party and the checking party are the same toolchain, and the failure mode being guarded
 * is a bundle arriving in the shipped set by mistake, not one forging its own description.</p>
 *
 * <p>Nothing here is load-bearing for provenance marking, which is the property that would actually
 * suffer from a lying manifest: {@code SyntheticProvenance.mint(...)} reads the live {@code
 * NodeTypeDescriptor} from the registered behavior factory and has never consulted a manifest, so a
 * bundle that under-declares here is still marked correctly at runtime.</p>
 *
 * <p>A runtime cross-check — comparing declared against actual capabilities at activation — is
 * therefore an integrity check on the manifest rather than a hole in any current guarantee, and it
 * is not implemented for that reason alone: <b>no reader today draws a trust conclusion from this
 * field</b>, so there is nothing yet for such a check to protect. It is not left out on
 * cost. It would cost nothing at runtime: {@code PluginBundleLoader.instantiate(...)} already calls
 * {@code behaviors()} to force linking, and {@code NodePackages.registerAll} already calls
 * {@code behaviors()} and {@code descriptor()} on each one. Its natural home is
 * {@code PluginBundleLoader.load}, which holds both the manifest and the constructed package. When
 * a consumer that does draw a trust conclusion arrives, the cross-check lands in that same change —
 * the rule {@link PluginManifest}'s javadoc already states for every optional key. See
 * {@code docs/qa/release-gate-and-shipped-plugin-bundles.md} for the one real trade involved (a
 * stale manifest would refuse an otherwise working bundle, which is a policy choice).</p>
 *
 * <h2>Unable to conclude is a failure, not a pass</h2>
 * <p>A bundle whose manifest is missing, unreadable, invalid, or simply silent on {@link
 * PluginManifest#NODE_CAPABILITIES_KEY} produces a violation. The alternative — treating an absent
 * field as "declares nothing generative" — would make every bundle built before this field existed
 * silently exempt from the only check that can see it, which is the precise shape of false green
 * this predicate exists to avoid.</p>
 */
public final class GenerativeCapabilityScan {

    /**
     * One scan's outcome. {@code bundlesInspected} is reported by callers even when it is zero and
     * {@code violations} is empty, for the reason P4 reports {@code entriesScanned}: "found nothing
     * wrong" and "found nothing to look at" must never be allowed to look the same.
     *
     * @param violations        human-readable violations, each naming manifest file, bundle and reason
     * @param bundlesInspected  how many bundle candidates were actually opened
     * @param directoriesFound  scanned directories that existed
     * @param directoriesAbsent requested directories that did not exist — sound (a directory that is
     *                          not there holds no shipped bundle), but never silent
     */
    public record Result(List<String> violations, int bundlesInspected,
                         List<Path> directoriesFound, List<Path> directoriesAbsent) {

        public Result {
            violations = List.copyOf(violations);
            directoriesFound = List.copyOf(directoriesFound);
            directoriesAbsent = List.copyOf(directoriesAbsent);
        }

        /** A one-line, always-printed account of what this scan actually looked at. */
        public String report() {
            return "Generative-capability scan inspected " + bundlesInspected
                    + " plugin bundle(s) across " + directoriesFound.size() + " existing directory(ies) "
                    + directoriesFound + (directoriesAbsent.isEmpty() ? ""
                            : "; absent (nothing shipped from them): " + directoriesAbsent);
        }
    }

    private GenerativeCapabilityScan() {
    }

    /**
     * Scans every immediate subdirectory of each of {@code pluginsDirs} that claims to be a bundle.
     *
     * <p>"Claims to be a bundle" is exactly {@link PluginBundleBuildCopy}'s rule — an immediate
     * subdirectory directly containing {@value PluginBundleValidator#MANIFEST_FILE_NAME} — so this
     * scan and the image build agree on what a bundle is without a second definition. A loose file
     * (a {@code README.md}, a {@code .gitkeep}, CI's {@code .keep}) never claimed to be one and is
     * not a finding.
     *
     * @param pluginsDirs convention directories holding installed/staged bundles; absent ones are
     *                    recorded rather than treated as an error
     * @return the violations, and the counts that make an empty result readable
     */
    public static Result scanDirectories(List<Path> pluginsDirs) {
        var violations = new ArrayList<String>();
        var found = new ArrayList<Path>();
        var absent = new ArrayList<Path>();
        int inspected = 0;

        for (Path pluginsDir : pluginsDirs) {
            if (!Files.isDirectory(pluginsDir)) {
                absent.add(pluginsDir);
                continue;
            }
            found.add(pluginsDir);
            List<Path> candidates;
            try (var listing = Files.list(pluginsDir)) {
                candidates = listing.filter(Files::isDirectory)
                        .filter(dir -> Files.isRegularFile(dir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME),
                                LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .toList();
            } catch (IOException | UncheckedIOException listingFailed) {
                // A directory that exists but cannot be listed is not "no bundles": it is a scan that
                // did not happen, and must fail rather than contribute a reassuring zero.
                violations.add("Release condition violated: the shipped plugin bundle "
                        + "directory " + pluginsDir + " exists but could not be listed ("
                        + listingFailed.getMessage() + "), so this scan cannot conclude that nothing "
                        + "generative ships from it.");
                continue;
            }
            for (Path candidate : candidates) {
                inspected++;
                violations.addAll(scanBundleDirectory(candidate));
            }
        }
        return new Result(violations, inspected, found, absent);
    }

    /**
     * Scans one bundle directory. Reads {@value PluginBundleValidator#MANIFEST_FILE_NAME} only; it
     * neither validates checksums nor touches a class file, so it is safe to run over material a
     * release gate has no business executing.
     *
     * @param bundleDir the bundle directory
     * @return violations for this bundle, empty when it declares no generative capability
     */
    public static List<String> scanBundleDirectory(Path bundleDir) {
        Path manifestPath = bundleDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME);
        byte[] manifestBytes;
        try {
            manifestBytes = Files.readAllBytes(manifestPath);
        } catch (IOException unreadable) {
            return List.of(unableToConclude(manifestPath.toString(), bundleDir.getFileName().toString(),
                    "its manifest could not be read (" + unreadable.getMessage() + ")"));
        }
        return inspectManifest(manifestBytes, manifestPath.toString(), bundleDir.getFileName().toString());
    }

    /**
     * The predicate itself, over raw manifest bytes so a caller reading a jar entry rather than a
     * file on disk uses this same code (see {@code ReleaseArtifactBoundaryChecks}'s scan for a bundle
     * embedded inside the released artifact).
     *
     * @param manifestBytes  the manifest document
     * @param manifestLabel  how to name the manifest in a message (a path, or {@code jar!/entry})
     * @param bundleFallback what to call the bundle when the manifest cannot supply an id
     * @return violations, empty when the manifest declares no generative capability
     */
    public static List<String> inspectManifest(byte[] manifestBytes, String manifestLabel, String bundleFallback) {
        PluginManifest manifest;
        try {
            manifest = PluginManifest.read(manifestBytes);
        } catch (PluginBundleException rejection) {
            return List.of(unableToConclude(manifestLabel, bundleFallback,
                    "its manifest is not valid (reason=" + rejection.reason() + ")"));
        }
        Optional<List<String>> declared = manifest.nodeCapabilities();
        if (declared.isEmpty()) {
            return List.of(unableToConclude(manifestLabel, manifest.id(),
                    "its manifest declares no \"" + PluginManifest.NODE_CAPABILITIES_KEY + "\" field, so "
                            + "what its node types can do cannot be established without loading them. "
                            + "Rebuild the bundle with `./plugin.sh build`, which derives the field from "
                            + "the package's own NodeTypeDescriptors"));
        }
        List<String> generative = SyntheticProvenance.generativeCapabilities(declared.get());
        if (generative.isEmpty()) {
            return List.of();
        }
        return List.of("Release condition violated (EU AI Act art. 50): "
                + "the shipped plugin bundle " + manifest.id() + " (manifest " + manifestLabel + ", behaviors "
                + manifest.behaviors() + ") declares the generative capability(ies) " + generative
                + ". Capabilities in SyntheticProvenance.GENERATIVE_CAPABILITIES mark a node type as "
                + "producing synthetic content; such a "
                + "bundle is never shipped in the default artifact or the published image: it is built "
                + "with ./plugin.sh, named in RAVENROOT_ENABLED_PLUGINS, and included only by an "
                + "operator who builds their own image. Remove it from the shipped set or the "
                + "published-plugin allowlist in the container build rather than relaxing this "
                + "check. Note that no other predicate here can see this bundle: a node calling a model "
                + "endpoint through its own HTTP client implements neither watched SPI and is loaded "
                + "reflectively, so P1/P2/P3/P6 are all silent on it.");
    }

    private static String unableToConclude(String manifestLabel, String bundle, String because) {
        return "Release condition indeterminate, therefore failed: the shipped plugin bundle "
                + bundle + " (manifest " + manifestLabel + ") cannot be shown to declare no generative "
                + "capability, because " + because + ". A bundle among the shipped set that this check "
                + "cannot conclude about fails it: treating silence as a clean result is exactly the "
                + "false green this predicate exists to prevent.";
    }
}
