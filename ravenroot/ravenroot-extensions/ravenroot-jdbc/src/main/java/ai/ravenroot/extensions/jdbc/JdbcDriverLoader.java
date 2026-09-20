package ai.ravenroot.extensions.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Driver;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

interface JdbcDriverLoader {
    int MAX_DRIVER_JAR_BYTES = 64 * 1024 * 1024;
    int MAX_DRIVER_ENTRY_BYTES = 32 * 1024 * 1024;
    int MAX_DRIVER_ENTRIES = 20_000;
    long MAX_EXPANDED_DRIVER_BYTES = 256L * 1024 * 1024;

    Driver load(JdbcProfile profile);

    @FunctionalInterface
    interface DriverOperation<T> {
        T run() throws Exception;
    }

    /** Runs driver-owned code with only that driver's private image visible through TCCL. */
    static <T> T inContext(Driver driver, DriverOperation<T> operation) throws Exception {
        Objects.requireNonNull(driver);
        return inContext(driver.getClass().getClassLoader(), operation);
    }

    static <T> T inContext(ClassLoader loader, DriverOperation<T> operation) throws Exception {
        Objects.requireNonNull(loader);
        Objects.requireNonNull(operation);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(loader);
            return operation.run();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    static JdbcDriverLoader verified() {
        return verified(JdbcNodePackage.class.getClassLoader());
    }

    static JdbcDriverLoader verified(ClassLoader loader) {
        return verified(loader, () -> { });
    }

    static JdbcDriverLoader verified(ClassLoader loader, Runnable afterVerifiedCopy) {
        Objects.requireNonNull(loader);
        Objects.requireNonNull(afterVerifiedCopy);
        return profile -> loadVerified(profile, loader, afterVerifiedCopy);
    }

    private static Driver loadVerified(JdbcProfile profile, ClassLoader loader, Runnable afterVerifiedCopy) {
        try {
            // Select the artifact by the profile's exact driverId before looking at driverClass.
            // The class name is verified only inside the immutable digest-checked private copy.
            Path artifact = driverArtifact(profile, loader);
            byte[] verifiedJar = verifiedCopy(artifact, profile.driverSha256());
            afterVerifiedCopy.run();
            ClassLoader privateLoader = new PrivateDriverClassLoader(verifiedJar, profile.driverSha256());
            return inContext(privateLoader, () -> {
                Class<?> type = Class.forName(profile.driverClass(), false, privateLoader);
                if (!Driver.class.isAssignableFrom(type) || type.getClassLoader() != privateLoader) throw refused();

                Class<?> initialized = Class.forName(profile.driverClass(), true, privateLoader);
                if (initialized != type) throw refused();
                return (Driver) initialized.getConstructor().newInstance();
            });
        } catch (JdbcFailure failure) { throw failure; }
        catch (RuntimeException failure) { throw refused(); }
        catch (ReflectiveOperationException | java.io.IOException | java.net.URISyntaxException
               | LinkageError failure) { throw refused(); }
        catch (Exception failure) { throw refused(); }
    }

    private static Path driverArtifact(JdbcProfile profile, ClassLoader loader) throws java.io.IOException,
            java.net.URISyntaxException {
        if (!(loader instanceof URLClassLoader bundleLoader)) throw refused();
        String expectedName = JdbcDriverArtifactName.fileName(profile.driverId());
        Path artifact = null;
        for (URL candidate : bundleLoader.getURLs()) {
            if (!"file".equalsIgnoreCase(candidate.getProtocol())) continue;
            Path path = Path.of(candidate.toURI()).toAbsolutePath().normalize();
            if (!expectedName.equals(path.getFileName().toString())) continue;
            if (artifact != null) throw refused();
            artifact = path;
        }
        if (artifact == null) throw refused();
        if (Files.isSymbolicLink(artifact)
                || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                || !JdbcDriverArtifactName.matches(profile.driverId(), artifact.getFileName().toString())) throw refused();
        return artifact;
    }

    private static byte[] verifiedCopy(Path artifact, String expected) throws java.io.IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] copy;
            try (InputStream input = Files.newInputStream(artifact, LinkOption.NOFOLLOW_LINKS)) {
                copy = input.readNBytes(MAX_DRIVER_JAR_BYTES + 1);
            }
            if (copy.length > MAX_DRIVER_JAR_BYTES) throw refused();
            digest.update(copy);
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) throw refused();
            return copy;
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * Child loader backed only by the already verified immutable in-memory copy.
     *
     * <p>A multi-release jar is resolved here, once, into one flat image: for each name the entry of
     * the highest release not above {@link #TARGET_RELEASE}, else the base entry. The running JVM's
     * version plays no part, so the bytes a class is defined from are fixed by the verified digest
     * alone.
     *
     * <p><b>What ambiguity means here.</b> A jar is ambiguous when <em>this</em> resolution is not
     * well defined: when the jar admits more than one reading of what its image contains. It is not
     * ambiguous merely because a reader at another release would select other entries, since a
     * classpath JVM at any release above the target always would; that disagreement is the reason
     * the resolution is pinned rather than a reason to refuse. Nor is a versioned entry that
     * differs from what the pinned digest covered ambiguous: the digest already refuses it as
     * {@link JdbcFailure.Code#DRIVER_REFUSED}, because substituted bytes are tampering, not a
     * second reading.
     */
    final class PrivateDriverClassLoader extends ClassLoader {
        /** The product's Java release (maven.compiler.release), never the release of the running JVM. */
        static final int TARGET_RELEASE = 21;
        private static final String VERSIONS = "META-INF/versions/";
        private static final int FIRST_VERSIONED_RELEASE = 9;

        private final Map<String, byte[]> entries;
        private final String digest;

        PrivateDriverClassLoader(byte[] jar, String digest) throws java.io.IOException {
            super(ClassLoader.getPlatformClassLoader());
            this.digest = digest;
            this.entries = flatImage(jar);
        }

        private record Versioned(int release, String name, byte[] value) { }

        private static Map<String, byte[]> flatImage(byte[] jar) throws java.io.IOException {
            Map<String, byte[]> copied = new LinkedHashMap<>();
            java.util.List<Versioned> versioned = new java.util.ArrayList<>();
            java.util.Set<String> names = new java.util.HashSet<>();
            boolean manifestEntry = false;
            long expanded = 0;
            Attributes mainAttributes;
            try (var input = new JarInputStream(new ByteArrayInputStream(jar))) {
                mainAttributes = input.getManifest() == null ? null : input.getManifest().getMainAttributes();
                for (JarEntry entry; (entry = input.getNextJarEntry()) != null;) {
                    if (entry.isDirectory()) continue;
                    if (names.size() >= MAX_DRIVER_ENTRIES) throw refused();
                    byte[] value = input.readNBytes(MAX_DRIVER_ENTRY_BYTES + 1);
                    if (value.length > MAX_DRIVER_ENTRY_BYTES) throw refused();
                    expanded = Math.addExact(expanded, value.length);
                    if (expanded > MAX_EXPANDED_DRIVER_BYTES || !names.add(entry.getName())) throw refused();

                    String folded = entry.getName().replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
                    if (folded.startsWith("meta-inf/versions/")) {
                        versioned.add(versionedEntry(entry.getName(), value));
                    } else {
                        // JarInputStream consumes a leading manifest; one reaching this loop is a
                        // second or misplaced manifest that other readers could take as the real one.
                        manifestEntry |= folded.equals("meta-inf/manifest.mf");
                        copied.put(entry.getName(), value);
                    }
                }
            } catch (ArithmeticException invalid) {
                throw refused();
            }
            if (versioned.isEmpty()) return Map.copyOf(copied);

            // Versioned entries only mean something under one unambiguous Multi-Release: true
            // manifest; otherwise some readers apply them and others do not.
            if (mainAttributes == null || manifestEntry
                    || !"true".equalsIgnoreCase(mainAttributes.getValue(Attributes.Name.MULTI_RELEASE))) {
                throw ambiguous();
            }
            // Two versioned entries cannot collide at one release: a surviving entry name is
            // exactly VERSIONS + release + '/' + name in one canonical spelling, so equal release
            // and name mean an equal entry name, which the duplicate-name bound above already
            // refused. Higher releases simply win, up to the target.
            Map<String, Integer> selectedRelease = new java.util.HashMap<>();
            for (Versioned entry : versioned) {
                if (entry.release() > TARGET_RELEASE) continue;
                Integer selected = selectedRelease.get(entry.name());
                if (selected == null || entry.release() > selected) {
                    selectedRelease.put(entry.name(), entry.release());
                    copied.put(entry.name(), entry.value());
                }
            }
            return Map.copyOf(copied);
        }

        /**
         * Accepts only {@code META-INF/versions/<release>/<name>} spelled exactly as the JDK reads
         * it: a canonical decimal release of at least 9 and a normalized name outside META-INF.
         *
         * <p>Every other spelling is content that some readers incorporate into the image and
         * others discard, so the jar admits more than one reading of what its image contains. That
         * holds for a release below 9 and for a versioned {@code META-INF/} entry as much as for a
         * miscased namespace: the JDK discards all three, other tooling does not, and in a jar that
         * declares {@code Multi-Release: true} they are entries meant to be selected. Copying them
         * in under their literal names instead would ship an image whose content depends on who
         * read the jar, which is what the pinned digest exists to rule out.
         */
        private static Versioned versionedEntry(String entryName, byte[] value) {
            if (!entryName.startsWith(VERSIONS) || entryName.indexOf('\\') >= 0) throw ambiguous();
            String rest = entryName.substring(VERSIONS.length());
            int slash = rest.indexOf('/');
            if (slash < 1 || slash > 4) throw ambiguous();
            String release = rest.substring(0, slash);
            if (release.charAt(0) == '0' || !release.chars().allMatch(c -> c >= '0' && c <= '9')) throw ambiguous();
            int feature = Integer.parseInt(release);
            String name = rest.substring(slash + 1);
            if (feature < FIRST_VERSIONED_RELEASE || name.regionMatches(true, 0, "META-INF/", 0, 9)) {
                throw ambiguous();
            }
            for (String segment : name.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw ambiguous();
            }
            return new Versioned(feature, name, value);
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytecode = entries.get(name.replace('.', '/') + ".class");
            if (bytecode == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytecode, 0, bytecode.length);
        }

        @Override protected URL findResource(String name) {
            byte[] value = entries.get(name);
            if (value == null) return null;
            try {
                String resourceId = HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8));
                return URL.of(URI.create("ravenroot-jdbc:" + digest + "/" + resourceId), new URLStreamHandler() {
                    @Override protected URLConnection openConnection(URL target) {
                        return new URLConnection(target) {
                            @Override public void connect() { connected = true; }
                            @Override public InputStream getInputStream() {
                                connect();
                                return new ByteArrayInputStream(value);
                            }
                        };
                    }
                });
            } catch (java.net.MalformedURLException impossible) {
                throw new IllegalStateException(impossible);
            }
        }

        @Override protected Enumeration<URL> findResources(String name) {
            URL resource = findResource(name);
            return resource == null ? Collections.emptyEnumeration()
                    : Collections.enumeration(java.util.List.of(resource));
        }

        @Override public InputStream getResourceAsStream(String name) {
            byte[] value = entries.get(name);
            return value == null ? super.getResourceAsStream(name) : new ByteArrayInputStream(value);
        }
    }

    private static JdbcFailure refused() { return new JdbcFailure(JdbcFailure.Code.DRIVER_REFUSED); }

    private static JdbcFailure ambiguous() { return new JdbcFailure(JdbcFailure.Code.DRIVER_AMBIGUOUS); }
}
