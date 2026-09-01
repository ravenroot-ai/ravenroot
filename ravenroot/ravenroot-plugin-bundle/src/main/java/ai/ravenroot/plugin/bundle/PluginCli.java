package ai.ravenroot.plugin.bundle;

import ai.ravenroot.api.node.NodeBehavior;
import ai.ravenroot.api.node.NodePackage;
import ai.ravenroot.api.payload.PayloadJson;
import ai.ravenroot.api.payload.PayloadValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The Java side of {@code plugin.sh} (PLAT-12): every trust-sensitive plugin bundle operation
 * the shell script needs -- validating a bundle, listing what a directory contains, and generating a
 * manifest from a freshly built extension -- lives here rather than being reimplemented in shell, for
 * the same reason the shell script itself never parses a manifest or computes a checksum.
 *
 * <h2>Why {@code generate-manifest} may instantiate a class, and the validator never does</h2>
 * <p>This is the one place in the {@code plugin.sh} toolchain that constructs a {@code NodePackage}
 * and calls its methods -- deliberately different from {@link PluginBundleValidator}, which never
 * does. The distinction is what is being trusted: {@code generate-manifest} runs against a class this
 * repository's own {@code mvn package} just compiled, from source the developer or CI already
 * controls, as part of a build step that already executes plenty of code (Maven itself, annotation
 * processors, test suites). "Presence must never execute" is a property of an <em>installed,
 * untrusted, third-party bundle at server startup</em>; it says nothing about a developer
 * introspecting their own freshly-built extension to generate its own manifest, which is exactly the
 * same operation {@link ai.ravenroot.plugin.bundle.PluginBundleLoader} performs on an
 * <em>activated</em> bundle at runtime -- construct, call {@code behaviors()} -- just run here against
 * trusted code instead of an allowlisted one.</p>
 */
public final class PluginCli {

    private PluginCli() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        try {
            int status = switch (args[0]) {
                case "validate" -> validate(requireArg(args, 1, "validate <bundle-dir>"));
                case "list" -> list(requireArg(args, 1, "list <plugins-dir>"));
                case "generate-manifest" -> generateManifest(args);
                case "manifest-id" -> manifestId(requireArg(args, 1, "manifest-id <bundle-dir>"));
                case "check-published" ->
                        checkPublished(requireArg(args, 1, "check-published <plugins-dir>"));
                case "verify-sha256" -> verifySha256(args);
                default -> {
                    usage();
                    yield 2;
                }
            };
            System.exit(status);
        } catch (IOException failed) {
            System.err.println("I/O error: " + failed.getMessage());
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println("""
                Usage:
                  PluginCli validate <bundle-dir>
                  PluginCli list <plugins-dir>
                  PluginCli generate-manifest <output-dir> <main-artifact-jar> <node-package-class> [dependency-jar...] [--pinned-dependency jar sha256]...
                  PluginCli manifest-id <bundle-dir>
                  PluginCli check-published <plugins-dir>
                  PluginCli verify-sha256 <regular-file> <lowercase-sha256>
                """);
    }

    /** Verifies an operator-supplied artifact before plugin.sh copies it into a closed bundle. */
    private static int verifySha256(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("Usage: PluginCli verify-sha256 <regular-file> <lowercase-sha256>");
            return 2;
        }
        Path artifact = Path.of(args[1]);
        String expected = args[2];
        if (!expected.matches("[0-9a-f]{64}") || Files.isSymbolicLink(artifact)
                || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            System.err.println("Artifact verification failed");
            return 1;
        }
        if (!sha256Hex(artifact).equals(expected)) {
            System.err.println("Artifact checksum mismatch");
            return 1;
        }
        System.out.println("OK  sha256=" + expected);
        return 0;
    }

    /**
     * Validates {@code bundleDir} and, on success, prints exactly its manifest {@code id} to stdout
     * and nothing else -- for {@code plugin.sh install} to capture as the destination directory name.
     * {@code build}'s own output directory is always literally named {@code plugin-bundle} (it lives
     * at each extension module's own {@code target/plugin-bundle}, never disambiguated by extension),
     * so deriving the installed name from the source path's basename, as an earlier version of
     * {@code install} did, would install every extension under the same collided name. The manifest's
     * own {@code id} is the one identifier that is actually unique per bundle and already validated.
     */
    private static int manifestId(String bundleDirArg) {
        Path bundleDir = Path.of(bundleDirArg);
        try {
            PluginManifest manifest = PluginBundleValidator.validate(bundleDir);
            System.out.println(manifest.id());
            return 0;
        } catch (PluginBundleException rejection) {
            System.err.println("INVALID  " + bundleDir + "  reason=" + rejection.reason()
                    + "  incident=" + rejection.incidentId());
            return 1;
        }
    }

    private static String requireArg(String[] args, int index, String usageLine) {
        if (args.length <= index) {
            System.err.println("Usage: PluginCli " + usageLine);
            System.exit(2);
        }
        return args[index];
    }

    // ---- validate -----------------------------------------------------------------------------

    /**
     * A local developer CLI is its own console, run by the person who asked the question -- unlike
     * {@code RavenrootServerMain}'s startup diagnostics, which are neutralized and capped because a
     * container's logs are shared more widely than a terminal a developer is looking at directly. So
     * this prints the full diagnostic detail unneutralized, on purpose: the audience is exactly the
     * person who needs it, whether they authored the bundle or are inspecting a third party's before
     * installing it.
     */
    private static int validate(String bundleDirArg) {
        Path bundleDir = Path.of(bundleDirArg);
        try {
            PluginManifest manifest = PluginBundleValidator.validate(bundleDir);
            System.out.println("OK  " + manifest.id() + "  version=" + manifest.version()
                    + "  behaviors=" + String.join(",", manifest.behaviors()));
            return 0;
        } catch (PluginBundleException rejection) {
            System.err.println("INVALID  " + bundleDir);
            System.err.println("  reason: " + rejection.reason());
            System.err.println("  " + rejection.getMessage());
            rejection.diagnosticDetail().forEach((key, value) -> System.err.println("  " + key + ": " + value));
            System.err.println("  incident: " + rejection.incidentId());
            return 1;
        }
    }

    // ---- list -----------------------------------------------------------------------------------

    private static int list(String pluginsDirArg) throws IOException {
        Path pluginsDir = Path.of(pluginsDirArg);
        if (!Files.isDirectory(pluginsDir)) {
            System.out.println("(no such directory: " + pluginsDir + ")");
            return 0;
        }
        List<Path> candidates;
        try (var listing = Files.list(pluginsDir)) {
            candidates = listing.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME),
                            LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
        if (candidates.isEmpty()) {
            System.out.println("(no plugin bundles found under " + pluginsDir + ")");
            return 0;
        }
        boolean anyInvalid = false;
        for (Path candidate : candidates) {
            try {
                PluginManifest manifest = PluginBundleValidator.validate(candidate);
                System.out.println("OK       " + candidate.getFileName() + "  id=" + manifest.id()
                        + "  version=" + manifest.version());
            } catch (PluginBundleException rejection) {
                anyInvalid = true;
                System.out.println("INVALID  " + candidate.getFileName() + "  reason=" + rejection.reason()
                        + "  incident=" + rejection.incidentId());
            }
        }
        return anyInvalid ? 1 : 0;
    }

    // ---- generate-manifest ----------------------------------------------------------------------

    private static int generateManifest(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("Usage: PluginCli generate-manifest <output-dir> <main-artifact-jar> "
                    + "<node-package-class> [dependency-jar...] [--pinned-dependency jar sha256]...");
            return 2;
        }
        Path outputDir = Path.of(args[1]);
        Path mainArtifactPath = Path.of(args[2]);
        String nodePackageClassName = args[3];
        List<Path> dependencyPaths = new ArrayList<>();
        List<PinnedDependency> pinnedDependencies = new ArrayList<>();
        boolean pinnedSection = false;
        for (int index = 4; index < args.length;) {
            if ("--pinned-dependency".equals(args[index])) {
                pinnedSection = true;
                if (index + 2 >= args.length || "--pinned-dependency".equals(args[index + 1])
                        || "--pinned-dependency".equals(args[index + 2])) {
                    System.err.println("Each --pinned-dependency requires one jar and one sha256");
                    return 2;
                }
                pinnedDependencies.add(new PinnedDependency(Path.of(args[index + 1]), args[index + 2]));
                index += 3;
                continue;
            }
            if (pinnedSection) {
                System.err.println("Unpinned dependency jars must precede --pinned-dependency pairs");
                return 2;
            }
            dependencyPaths.add(Path.of(args[index]));
            index++;
        }

        var artifactFileNames = new java.util.LinkedHashSet<String>();
        artifactFileNames.add(mainArtifactPath.getFileName().toString());
        for (Path dependency : dependencyPaths) {
            if (!artifactFileNames.add(dependency.getFileName().toString())) {
                System.err.println("Duplicate artifact filename in generated bundle");
                return 2;
            }
        }
        for (PinnedDependency pinned : pinnedDependencies) {
            if (!artifactFileNames.add(pinned.path().getFileName().toString())) {
                System.err.println("Duplicate artifact filename in generated bundle");
                return 2;
            }
        }

        NodePackage instance;
        try {
            Class<?> type = Class.forName(nodePackageClassName);
            if (!NodePackage.class.isAssignableFrom(type)) {
                System.err.println(nodePackageClassName + " does not implement NodePackage");
                return 1;
            }
            instance = (NodePackage) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException failed) {
            System.err.println("Could not load or instantiate " + nodePackageClassName + ": " + failed);
            return 1;
        }

        List<String> behaviorNames = new ArrayList<>();
        // The union of every behavior's declared capabilities, normalised the one way
        // SyntheticProvenance normalises them, so a release check can read from the manifest what
        // otherwise exists only in a NodeTypeDescriptor constructed in code. Derived here rather
        // than written by hand for the same reason nodePackageClasses is: this method already holds
        // the authority (the freshly built package's own descriptors), and a hand-maintained copy
        // would be a second source that can disagree with it.
        var capabilityNames = new TreeSet<String>();
        for (NodeBehavior behavior : instance.behaviors()) {
            behaviorNames.add(behavior.descriptor().behavior());
            for (String capability : behavior.descriptor().capabilities()) {
                if (capability != null && !capability.isBlank()) {
                    capabilityNames.add(capability.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        if (behaviorNames.isEmpty()) {
            System.err.println(nodePackageClassName + " declares no behaviors; refusing to generate a manifest "
                    + "for a package that would contribute nothing");
            return 1;
        }

        Files.createDirectories(outputDir);
        PayloadValue mainArtifactValue = copyAndDescribe(mainArtifactPath, outputDir);
        var dependencyValues = new ArrayList<PayloadValue>();
        for (Path dependencyPath : dependencyPaths) {
            dependencyValues.add(copyAndDescribe(dependencyPath, outputDir));
        }
        for (PinnedDependency pinned : pinnedDependencies) {
            PayloadValue description = copyAndDescribePinned(pinned, outputDir);
            if (description == null) return 1;
            dependencyValues.add(description);
        }

        var manifestFields = new java.util.LinkedHashMap<String, PayloadValue>();
        manifestFields.put("schemaVersion", PayloadValue.of(PluginManifest.SCHEMA_VERSION));
        manifestFields.put("id", PayloadValue.of(instance.id()));
        manifestFields.put("version", PayloadValue.of(instance.version()));
        manifestFields.put("sdkContract", PayloadValue.of(instance.sdkContract()));
        manifestFields.put("nodePackageClasses", PayloadValue.list(List.of(PayloadValue.of(nodePackageClassName))));
        manifestFields.put("behaviors", PayloadValue.list(behaviorNames.stream().map(PayloadValue::of).toList()));
        // Always written, including as an empty list. "This bundle declares no capabilities" and
        // "this manifest says nothing about capabilities" are different answers, and only the first
        // one lets GenerativeCapabilityScan clear a bundle for shipping.
        manifestFields.put(PluginManifest.NODE_CAPABILITIES_KEY,
                PayloadValue.list(capabilityNames.stream().map(PayloadValue::of).toList()));
        manifestFields.put("mainArtifact", mainArtifactValue);
        if (!dependencyValues.isEmpty()) {
            manifestFields.put("dependencyArtifacts", PayloadValue.list(dependencyValues));
        }

        String json = PayloadJson.write(PayloadValue.map(manifestFields));
        Path manifestPath = outputDir.resolve(PluginBundleValidator.MANIFEST_FILE_NAME);
        Files.writeString(manifestPath, json + "\n");

        // The manifest this method just wrote is re-validated the same way any other bundle would be,
        // before this command reports success: generating a manifest that would not itself pass
        // PluginBundleValidator would be a defect in this class, not something to hand to the
        // developer as if it worked.
        try {
            PluginBundleValidator.validate(outputDir);
        } catch (PluginBundleException selfCheckFailed) {
            System.err.println("Generated manifest failed its own validation -- this is a bug in "
                    + "generate-manifest, not in the extension: " + selfCheckFailed.getMessage());
            selfCheckFailed.diagnosticDetail().forEach((key, value) -> System.err.println("  " + key + ": " + value));
            return 1;
        }

        System.out.println("Bundle generated: " + outputDir);
        System.out.println("  id: " + instance.id());
        System.out.println("  version: " + instance.version());
        System.out.println("  behaviors: " + String.join(",", behaviorNames));
        System.out.println("  " + PluginManifest.NODE_CAPABILITIES_KEY + ": "
                + (capabilityNames.isEmpty() ? "(none)" : String.join(",", capabilityNames)));
        return 0;
    }

    // ---- check-published --------------------------------------------------------------------

    /**
     * The publish-time refusal: fails when any bundle in {@code pluginsDir} declares a
     * capability {@code SyntheticProvenance.GENERATIVE_CAPABILITIES} treats as generative.
     *
     * <h2>Why this is not folded into {@code validate}</h2>
     * <p>A generative bundle is perfectly valid, and a developer building one locally must keep
     * getting a green {@code ./plugin.sh validate}: the documented decision is that such a bundle exists
     * and is installable, only never <em>shipped by this project</em>. Two different questions —
     * "is this bundle well-formed?" and "may this bundle go into the artifact everyone downloads?" —
     * so two commands. Folding the second into the first would make the toolchain refuse to
     * acknowledge a bundle it is supposed to support.</p>
     *
     * <p>It calls {@link GenerativeCapabilityScan}, the same class {@code
     * ReleaseArtifactBoundaryChecks} calls at {@code package}. That shared call is the point: a
     * second implementation living in the workflow could pass while the gate failed, or the reverse,
     * and nothing would notice — the reason {@code ReleaseArtifactBoundaryChecks} was extracted from
     * its two callers in the first place.</p>
     */
    private static int checkPublished(String pluginsDirArg) {
        Path pluginsDir = Path.of(pluginsDirArg);
        GenerativeCapabilityScan.Result result = GenerativeCapabilityScan.scanDirectories(List.of(pluginsDir));
        // Printed on every run, green or red: a check whose silence is indistinguishable from
        // "nothing to check" reports nothing at all.
        System.out.println(result.report());
        if (result.violations().isEmpty()) {
            return 0;
        }
        System.err.println("PUBLISH REFUSED  " + pluginsDir);
        for (String violation : result.violations()) {
            System.err.println("  " + violation);
        }
        return 1;
    }

    private static PayloadValue copyAndDescribe(Path source, Path outputDir) throws IOException {
        String fileName = source.getFileName().toString();
        Path destination = outputDir.resolve(fileName);
        Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        String sha256 = sha256Hex(destination);
        long size = Files.size(destination);
        return PayloadValue.map(Map.of(
                "fileName", PayloadValue.of(fileName),
                "sha256", PayloadValue.of(sha256),
                "sizeBytes", PayloadValue.of(size)));
    }

    /** Copies and hashes one stream, binding the manifest bytes to the operator's expected digest. */
    private static PayloadValue copyAndDescribePinned(PinnedDependency pinned, Path outputDir) throws IOException {
        if (!pinned.sha256().matches("[0-9a-f]{64}") || Files.isSymbolicLink(pinned.path())
                || !Files.isRegularFile(pinned.path(), LinkOption.NOFOLLOW_LINKS)) {
            System.err.println("Pinned dependency verification failed");
            return null;
        }
        Path destination = outputDir.resolve(pinned.path().getFileName().toString());
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
        }
        long size = 0;
        try (var channel = Files.newByteChannel(pinned.path(), StandardOpenOption.READ,
                     LinkOption.NOFOLLOW_LINKS);
             InputStream input = Channels.newInputStream(channel);
             var output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                size += read;
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equals(pinned.sha256())) {
            Files.deleteIfExists(destination);
            System.err.println("Pinned dependency checksum mismatch");
            return null;
        }
        return PayloadValue.map(Map.of(
                "fileName", PayloadValue.of(destination.getFileName().toString()),
                "sha256", PayloadValue.of(actual),
                "sizeBytes", PayloadValue.of(size)));
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
        }
    }

    private record PinnedDependency(Path path, String sha256) { }
}
