import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;


/**
 * Executes the exact values emitted by the standalone configurator against every first-party
 * extension resolver. The fixture is generated from the TypeScript registry immediately before
 * this probe, so this is a cross-language compatibility boundary rather than a
 * second handwritten example catalog.
 */
public final class ConfiguratorContractCompatibilityProbe {
    private Properties fixtures;
    private FixtureEnvironment fixtureEnvironment;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("fixture path is required");
        var probe = new ConfiguratorContractCompatibilityProbe();
        probe.fixtures = fixtures(Path.of(arguments[0]));
        Path scratch;
        try (FixtureEnvironment environment = FixtureEnvironment.create()) {
            probe.fixtureEnvironment = environment;
            scratch = environment.root();
            for (String id : probe.required("ids").split(",")) probe.verify(id);
        }
        require(!Files.exists(scratch, LinkOption.NOFOLLOW_LINKS),
                "compatibility fixture environment was not removed");
        System.out.println("Configurator Java resolver compatibility passed: "
                + probe.required("ids").split(",").length
                + " contracts; 4 Git path-policy refusals; isolated fixture environment removed");
    }

    private void verify(String id) throws Exception {
        String className = required(id + ".class");
        String mode = required(id + ".mode");
        String environmentKey = required(id + ".environmentKey");
        String valid = required(id + ".valid");
        String[] arguments = required(id + ".args").isEmpty()
                ? new String[0] : required(id + ".args").split("\u001f", -1);
        Class<?> type = Class.forName(className);
        if ("configuration".equals(mode)) {
            Method factory = type.getDeclaredMethod(required(id + ".method"), Map.class);
            factory.setAccessible(true);
            require(factory.invoke(null, environment(id, environmentKey, valid)) != null, id + " rejected configurator output");
            for (int index = 0; index < Integer.parseInt(required(id + ".positiveCount")); index++) {
                Object accepted = factory.invoke(null, environment(id, environmentKey,
                        required(id + ".positive." + index + ".value")));
                require(accepted != null, id + " rejected valid vector " + required(id + ".positive." + index + ".label"));
                verifyAssertion(required(id + ".positive." + index + ".assertion"), accepted, id);
            }
            for (int index = 0; index < Integer.parseInt(required(id + ".negativeCount")); index++) {
                String invalid = required(id + ".negative." + index + ".value");
                try { factory.invoke(null, environment(id, environmentKey, invalid)); throw new AssertionError(id + " accepted negative vector " + index + " (" + required(id + ".negative." + index + ".label") + ")"); }
                catch (InvocationTargetException expected) { require(expected.getCause() != null, id + " rejection lost its cause"); }
            }
            return;
        }
        Constructor<?> constructor = type.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        Method resolve = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(required(id + ".method"))
                        && candidate.getParameterCount() == arguments.length)
                .findFirst().orElseThrow();
        resolve.setAccessible(true);
        Object accepted = resolve.invoke(constructor.newInstance(environment(id, environmentKey, valid)), (Object[]) arguments);
        require(accepted instanceof Optional<?> && ((Optional<?>) accepted).isPresent(), id + " rejected configurator output");
        if ("git-workspace.profile".equals(id)) {
            verifyGitWorkspacePathRefusals(constructor, resolve, arguments, environmentKey, valid);
        }
        String alternate = required(id + ".alternateValid");
        if (!alternate.isEmpty()) {
            Object optionalAccepted = resolve.invoke(constructor.newInstance(environment(id, environmentKey, alternate)), (Object[]) arguments);
            require(optionalAccepted instanceof Optional<?> && ((Optional<?>) optionalAccepted).isPresent(), id + " rejected valid omitted optional fields");
        }
        for (int index = 0; index < Integer.parseInt(required(id + ".positiveCount")); index++) {
            String rawArguments = required(id + ".positive." + index + ".args");
            String[] positiveArguments = rawArguments.isEmpty() ? new String[0] : rawArguments.split("\u001f", -1);
            Object result = resolve.invoke(constructor.newInstance(environment(id, environmentKey,
                            required(id + ".positive." + index + ".value"))),
                    (Object[]) positiveArguments);
            require(result instanceof Optional<?> && ((Optional<?>) result).isPresent(),
                    id + " rejected valid vector " + required(id + ".positive." + index + ".label"));
            verifyAssertion(required(id + ".positive." + index + ".assertion"), ((Optional<?>) result).orElseThrow(), id);
        }
        for (int index = 0; index < Integer.parseInt(required(id + ".negativeCount")); index++) {
            String invalid = required(id + ".negative." + index + ".value");
            String rawArguments = required(id + ".negative." + index + ".args");
            String[] invalidArguments = rawArguments.isEmpty() ? new String[0] : rawArguments.split("\u001f", -1);
            String expected = required(id + ".negative." + index + ".expected");
            final Object refused;
            try {
                refused = resolve.invoke(constructor.newInstance(environment(id, environmentKey, invalid)),
                        (Object[]) invalidArguments);
            } catch (InvocationTargetException rejected) {
                require("security-exception".equals(expected) && rejected.getCause() instanceof SecurityException,
                        id + " rejected negative vector " + index + " through an unexpected boundary");
                continue;
            }
            require("empty".equals(expected), id + " did not enforce the expected security refusal for negative vector " + index);
            require(refused instanceof Optional<?> && ((Optional<?>) refused).isEmpty(), id + " accepted negative vector " + index + " (" + required(id + ".negative." + index + ".label") + ")");
        }
    }

    private Map<String, String> environment(String id, String primaryKey, String primaryValue) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put(primaryKey, fixtureEnvironment.materialize(id, primaryValue));
        int additional = Integer.parseInt(required(id + ".additionalEnvironmentCount"));
        for (int index = 0; index < additional; index++) {
            String key = required(id + ".additionalEnvironment." + index + ".key");
            String value = required(id + ".additionalEnvironment." + index + ".value");
            require(environment.putIfAbsent(key, value) == null,
                    id + " fixture declares a duplicate environment key");
        }
        return Map.copyOf(environment);
    }

    private void verifyGitWorkspacePathRefusals(Constructor<?> constructor, Method resolve,
                                                  String[] arguments, String environmentKey,
                                                  String valid) throws Exception {
        for (Map.Entry<String, String> refusal : fixtureEnvironment.gitPathRefusals(valid).entrySet()) {
            Object result = resolve.invoke(
                    constructor.newInstance(environment("git-workspace.profile", environmentKey, refusal.getValue())),
                    (Object[]) arguments);
            require(result instanceof Optional<?> && ((Optional<?>) result).isEmpty(),
                    "git-workspace.profile accepted " + refusal.getKey());
        }
    }

    private static void verifyAssertion(String assertion, Object accepted, String id) throws Exception {
        if (assertion.isEmpty()) return;
        if ("openapi-spec-2mib".equals(assertion)) {
            Method specification = accepted.getClass().getMethod("specification");
            require(((byte[]) specification.invoke(accepted)).length == 2_097_152,
                    id + " did not preserve the exact 2 MiB decoded specification");
            return;
        }
        if ("openapi-server-spec-2mib".equals(assertion)) {
            Method profiles = accepted.getClass().getMethod("profiles");
            Object profile = ((Map<?, ?>) profiles.invoke(accepted)).values().iterator().next();
            Method specification = profile.getClass().getMethod("specification");
            require(((byte[]) specification.invoke(profile)).length == 2_097_152,
                    id + " did not preserve the exact 2 MiB decoded specification");
            return;
        }
        if (!"openapi-normalized".equals(assertion)) throw new AssertionError(id + " has unknown assertion " + assertion);
        Method origin = accepted.getClass().getMethod("origin");
        Method fixedHeaders = accepted.getClass().getMethod("fixedHeaders");
        Method inputHeaders = accepted.getClass().getMethod("allowedInputHeaders");
        Method responseHeaders = accepted.getClass().getMethod("projectedResponseHeaders");
        require("https://api.example.test".equals(origin.invoke(accepted).toString()), id + " did not normalize HTTPS origin");
        require(((Map<?, ?>) fixedHeaders.invoke(accepted)).containsKey("x-mixed-case"), id + " did not normalize fixed header name");
        require(((java.util.Set<?>) inputHeaders.invoke(accepted)).contains("x-request-id"), id + " did not normalize input header name");
        require(((java.util.Set<?>) responseHeaders.invoke(accepted)).contains("etag"), id + " did not normalize response header name");
    }

    private static Properties fixtures(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            Properties result = new Properties();
            result.load(input);
            return result;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private String required(String key) {
        String value = fixtures.getProperty(key);
        if (value == null) throw new IllegalStateException("missing fixture property " + key);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * Supplies only the local filesystem prerequisites that otherwise make generated fixtures
     * host-dependent. Generated values and negative vectors remain authoritative; exact template
     * paths are mapped into this probe-owned tree before resolver invocation.
     */
    private static final class FixtureEnvironment implements AutoCloseable {
        private static final String FILESYSTEM_TEMPLATE_ROOT = "/";
        private static final String GIT_TEMPLATE_ROOT = "/";
        private static final String GIT_TEMPLATE_EXECUTABLE = "/usr/bin/git";
        private static final String GIT_TEMPLATE_SHELL = "/bin/sh";

        private final Path root;
        private final Path filesystemRoot;
        private final Path gitRoot;
        private final Path gitExecutable;
        private final Path shellExecutable;
        private final Path missingRoot;
        private final Path rootLink;
        private final Path gitLink;
        private final Path shellLink;

        private FixtureEnvironment(Path root, Path filesystemRoot, Path gitRoot,
                                   Path gitExecutable, Path shellExecutable,
                                   Path missingRoot, Path rootLink, Path gitLink,
                                   Path shellLink) {
            this.root = root;
            this.filesystemRoot = filesystemRoot;
            this.gitRoot = gitRoot;
            this.gitExecutable = gitExecutable;
            this.shellExecutable = shellExecutable;
            this.missingRoot = missingRoot;
            this.rootLink = rootLink;
            this.gitLink = gitLink;
            this.shellLink = shellLink;
        }

        static FixtureEnvironment create() throws IOException {
            Path root = Files.createTempDirectory("ravenroot-configurator-contract-");
            try {
                Path filesystemRoot = Files.createDirectory(root.resolve("filesystem-root"));
                Path gitRoot = Files.createDirectory(root.resolve("git-root"));
                Path bin = Files.createDirectory(root.resolve("bin"));
                Path gitExecutable = executable(bin.resolve("git"));
                Path shellExecutable = executable(bin.resolve("shell"));
                Path missingRoot = root.resolve("missing-root");
                Path rootLink = Files.createSymbolicLink(root.resolve("root-link"), gitRoot);
                Path gitLink = Files.createSymbolicLink(root.resolve("git-link"), gitExecutable);
                Path shellLink = Files.createSymbolicLink(root.resolve("shell-link"), shellExecutable);
                return new FixtureEnvironment(root, filesystemRoot, gitRoot, gitExecutable,
                        shellExecutable, missingRoot, rootLink, gitLink, shellLink);
            } catch (IOException | RuntimeException failure) {
                deleteOwnedTree(root);
                throw failure;
            }
        }

        Path root() {
            return root;
        }

        String materialize(String id, String value) {
            if ("filesystem.profile".equals(id)) {
                if (value.equals(FILESYSTEM_TEMPLATE_ROOT)) return filesystemRoot.toString();
                if (value.startsWith(FILESYSTEM_TEMPLATE_ROOT + ";")) {
                    return filesystemRoot + value.substring(FILESYSTEM_TEMPLATE_ROOT.length());
                }
                return value;
            }
            if (!"git-workspace.profile".equals(id)) return value;
            String document = decode(value);
            document = replaceJsonStringIfPresent(document, "root", GIT_TEMPLATE_ROOT, gitRoot.toString());
            document = replaceJsonStringIfPresent(document, "gitExecutable", GIT_TEMPLATE_EXECUTABLE,
                    gitExecutable.toString());
            document = replaceJsonStringIfPresent(document, "processShellExecutable", GIT_TEMPLATE_SHELL,
                    shellExecutable.toString());
            return encode(document);
        }

        Map<String, String> gitPathRefusals(String generatedValid) {
            String valid = materialize("git-workspace.profile", generatedValid);
            Map<String, String> refusals = new LinkedHashMap<>();
            refusals.put("a missing root", replaceGitPath(valid, "root", gitRoot, missingRoot));
            refusals.put("a symlink root", replaceGitPath(valid, "root", gitRoot, rootLink));
            refusals.put("a symlink Git executable",
                    replaceGitPath(valid, "gitExecutable", gitExecutable, gitLink));
            refusals.put("a symlink process shell executable",
                    replaceGitPath(valid, "processShellExecutable", shellExecutable, shellLink));
            return Map.copyOf(refusals);
        }

        private static Path executable(Path path) throws IOException {
            Files.writeString(path, "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(path),
                    "could not create an executable compatibility fixture");
            return path;
        }

        private static String replaceGitPath(String encoded, String field, Path current, Path replacement) {
            String document = decode(encoded);
            String changed = replaceJsonStringIfPresent(document, field, current.toString(), replacement.toString());
            require(!changed.equals(document), "Git compatibility fixture is missing " + field);
            return encode(changed);
        }

        private static String replaceJsonStringIfPresent(String document, String field,
                                                         String current, String replacement) {
            String token = "\"" + field + "\":\"" + escapeJson(current) + "\"";
            String changed = "\"" + field + "\":\"" + escapeJson(replacement) + "\"";
            return document.replace(token, changed);
        }

        private static String escapeJson(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String decode(String value) {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        }

        private static String encode(String value) {
            return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() throws IOException {
            deleteOwnedTree(root);
        }

        private static void deleteOwnedTree(Path root) throws IOException {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
