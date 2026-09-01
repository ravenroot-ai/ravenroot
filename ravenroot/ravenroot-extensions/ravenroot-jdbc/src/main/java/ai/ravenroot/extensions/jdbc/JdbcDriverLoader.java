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

    /** Child loader backed only by the already verified immutable in-memory copy. */
    final class PrivateDriverClassLoader extends ClassLoader {
        private final Map<String, byte[]> entries;
        private final String digest;

        PrivateDriverClassLoader(byte[] jar, String digest) throws java.io.IOException {
            super(ClassLoader.getPlatformClassLoader());
            this.digest = digest;
            Map<String, byte[]> copied = new LinkedHashMap<>();
            long expanded = 0;
            try (var input = new JarInputStream(new ByteArrayInputStream(jar))) {
                if (input.getManifest() != null
                        && input.getManifest().getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE) != null) {
                    throw refused();
                }
                for (JarEntry entry; (entry = input.getNextJarEntry()) != null;) {
                    if (entry.isDirectory()) continue;
                    String lowerName = entry.getName().toLowerCase(java.util.Locale.ROOT);
                    if (lowerName.startsWith("meta-inf/versions/")
                            || lowerName.startsWith("meta-inf\\versions\\")) throw refused();
                    if (copied.size() >= MAX_DRIVER_ENTRIES) throw refused();
                    byte[] value = input.readNBytes(MAX_DRIVER_ENTRY_BYTES + 1);
                    if (value.length > MAX_DRIVER_ENTRY_BYTES) throw refused();
                    expanded = Math.addExact(expanded, value.length);
                    if (expanded > MAX_EXPANDED_DRIVER_BYTES || copied.putIfAbsent(entry.getName(), value) != null) {
                        throw refused();
                    }
                }
            } catch (ArithmeticException invalid) {
                throw refused();
            }
            entries = Map.copyOf(copied);
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
}
