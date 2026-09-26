package ai.ravenroot.extensions.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcDriverLoaderTest {
    private static final String INITIALIZED = "ravenroot.jdbc.fixture.initialized";

    @Test
    void verifiesIsolatedArtifactIdentityAndDigestBeforeDriverInitialization(@TempDir Path workspace)
            throws Exception {
        Path jar = compileDriver(workspace, "pinned-driver.jar");
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            JdbcProfile mismatched = profile("pinned-driver", "0".repeat(64));

            JdbcFailure refused = assertThrows(JdbcFailure.class,
                    () -> JdbcDriverLoader.verified(loader).load(mismatched));

            assertEquals(JdbcFailure.Code.DRIVER_REFUSED, refused.code());
            assertNull(System.getProperty(INITIALIZED), "a mismatched artifact must execute no static initializer");

            Driver accepted = JdbcDriverLoader.verified(loader).load(profile("pinned-driver", sha256(jar)));
            assertInstanceOf(Driver.class, accepted);
            assertEquals("true", System.getProperty(INITIALIZED));
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void refusesTamperedOrUnexpectedlyNamedDriverJars(@TempDir Path workspace) throws Exception {
        Path tampered = compileDriver(workspace.resolve("tampered"), "expected-driver.jar");
        String original = sha256(tampered);
        Files.writeString(tampered, "tamper", java.nio.file.StandardOpenOption.APPEND);
        try (var loader = new URLClassLoader(new java.net.URL[]{tampered.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            assertThrows(JdbcFailure.class,
                    () -> JdbcDriverLoader.verified(loader).load(profile("expected-driver", original)));
            assertNull(System.getProperty(INITIALIZED));
        }

        Path wrongName = compileDriver(workspace.resolve("wrong-name"), "actual-driver.jar");
        try (var loader = new URLClassLoader(new java.net.URL[]{wrongName.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            assertThrows(JdbcFailure.class,
                    () -> JdbcDriverLoader.verified(loader).load(profile("expected-driver", sha256(wrongName))));
            assertNull(System.getProperty(INITIALIZED));
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void replacementAfterVerificationCannotChangeTheBytesThatDefineTheDriver(@TempDir Path workspace)
            throws Exception {
        Path installed = compileDriver(workspace.resolve("installed"), "pinned-driver.jar", "original");
        Path alternate = compileDriver(workspace.resolve("alternate"), "alternate.jar", "alternate");
        String pinned = sha256(installed);
        try (var loader = new URLClassLoader(new java.net.URL[]{installed.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            System.clearProperty(INITIALIZED);
            JdbcDriverLoader verified = JdbcDriverLoader.verified(loader, () -> {
                try {
                    Files.copy(alternate, installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });

            Driver driver = verified.load(profile("pinned-driver", pinned));

            assertInstanceOf(Driver.class, driver);
            assertEquals("original", System.getProperty(INITIALIZED),
                    "class definition must use the verified private copy, not the replaced installation path");
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void resolvesMultiReleaseJarOnceForTheTargetReleaseNotForTheRunningJvm(@TempDir Path workspace)
            throws Exception {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("fixture/PinnedDriver.class", variant(workspace, "base"));
        entries.put("META-INF/versions/9/fixture/PinnedDriver.class", variant(workspace, "release-9"));
        entries.put("META-INF/versions/21/fixture/PinnedDriver.class", variant(workspace, "release-21"));
        entries.put("META-INF/versions/22/fixture/PinnedDriver.class", variant(workspace, "release-22"));
        Path jar = writeJar(workspace.resolve("resolved/pinned-driver.jar"), manifest("true"), entries);
        try (JarFile release22 = new JarFile(jar.toFile(), true, java.util.zip.ZipFile.OPEN_READ,
                Runtime.Version.parse("22"))) {
            org.junit.jupiter.api.Assertions.assertArrayEquals(
                    entries.get("META-INF/versions/22/fixture/PinnedDriver.class"),
                    release22.getInputStream(release22.getJarEntry("fixture/PinnedDriver.class")).readAllBytes(),
                    "a release-22 runtime would select the release-22 variant");
        }
        try {
            assertEquals("release-21", loadedMarker(jar),
                    "the image is resolved for the product's release, whatever the JVM running it");

            Path flatWithAttribute = writeJar(workspace.resolve("flat-attribute/pinned-driver.jar"), manifest("true"),
                    Map.of("fixture/PinnedDriver.class", entries.get("fixture/PinnedDriver.class")));
            assertEquals("base", loadedMarker(flatWithAttribute), "no versioned entry leaves one flat image");

            Path flat = workspace.resolve("flat/pinned-driver.jar");
            Files.createDirectories(flat.getParent());
            Files.copy(compileDriver(workspace.resolve("flat-source"), "base.jar", "flat"), flat);
            assertEquals("flat", loadedMarker(flat));
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void refusesEveryVersionedLayoutThatAdmitsMoreThanOneReadingAsAmbiguous(@TempDir Path workspace)
            throws Exception {
        byte[] base = variant(workspace, "base");
        byte[] versioned = variant(workspace, "versioned");
        String entry = "fixture/PinnedDriver.class";
        byte[] manifestBytes = "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        record Layout(String name, Manifest manifest, Map<String, byte[]> entries) { }
        Map<String, byte[]> lateManifest = new java.util.LinkedHashMap<>();
        lateManifest.put(entry, base);
        lateManifest.put("META-INF/MANIFEST.MF", manifestBytes);
        lateManifest.put("META-INF/versions/21/" + entry, versioned);
        List<Layout> layouts = List.of(
                new Layout("no Multi-Release attribute", manifest(null),
                        Map.of(entry, base, "META-INF/versions/21/" + entry, versioned)),
                new Layout("Multi-Release false", manifest("false"),
                        Map.of(entry, base, "META-INF/versions/21/" + entry, versioned)),
                new Layout("non-numeric release", manifest("true"),
                        Map.of(entry, base, "META-INF/versions/twenty-one/" + entry, versioned)),
                new Layout("non-canonical release", manifest("true"),
                        Map.of(entry, base, "META-INF/versions/021/" + entry, versioned)),
                new Layout("release below 9", manifest("true"),
                        Map.of(entry, base, "META-INF/versions/8/" + entry, versioned)),
                new Layout("namespace in another case", manifest("true"),
                        Map.of(entry, base, "meta-inf/versions/21/" + entry, versioned)),
                new Layout("namespace with backslashes", manifest("true"),
                        Map.of(entry, base, "META-INF\\versions\\21\\fixture\\PinnedDriver.class", versioned)),
                new Layout("dot segments", manifest("true"),
                        Map.of(entry, base, "META-INF/versions/21/fixture/../" + entry, versioned)),
                new Layout("versioned META-INF", manifest("true"), Map.of(entry, base,
                        "META-INF/versions/21/META-INF/services/java.sql.Driver",
                        "fixture.PinnedDriver\n".getBytes(StandardCharsets.UTF_8))),
                new Layout("second manifest", manifest("true"), Map.of(entry, base,
                        "meta-inf/manifest.mf", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8),
                        "META-INF/versions/21/" + entry, versioned)),
                new Layout("manifest a streaming reader does not see", null, lateManifest));

        for (int index = 0; index < layouts.size(); index++) {
            Layout layout = layouts.get(index);
            Path jar = writeJar(workspace.resolve("layout-" + index + "/pinned-driver.jar"), layout.manifest(),
                    layout.entries());
            System.clearProperty(INITIALIZED);
            try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                JdbcFailure refused = assertThrows(JdbcFailure.class,
                        () -> JdbcDriverLoader.verified(loader).load(profile("pinned-driver", sha256(jar))),
                        layout.name());
                assertEquals(JdbcFailure.Code.DRIVER_AMBIGUOUS, refused.code(), layout.name());
                assertEquals("JDBC_DRIVER_AMBIGUOUS", refused.getMessage(), layout.name());
                assertNull(System.getProperty(INITIALIZED), layout.name() + ": no initializer may run");
            }
        }
    }

    @Test
    void versionedBytesOutsideTheVerifiedCopyCanNeitherLoadNorReplaceIt(@TempDir Path workspace)
            throws Exception {
        byte[] base = variant(workspace, "base");
        Path installed = writeJar(workspace.resolve("installed/pinned-driver.jar"), manifest("true"), Map.of(
                "fixture/PinnedDriver.class", base,
                "META-INF/versions/21/fixture/PinnedDriver.class", variant(workspace, "original")));
        Path alternate = writeJar(workspace.resolve("alternate/alternate.jar"), manifest("true"), Map.of(
                "fixture/PinnedDriver.class", base,
                "META-INF/versions/21/fixture/PinnedDriver.class", variant(workspace, "alternate")));
        String pinned = sha256(installed);
        try {
            // Identical base entries: only the versioned entry differs from what the digest covers.
            Path swapped = workspace.resolve("swapped/pinned-driver.jar");
            Files.createDirectories(swapped.getParent());
            Files.copy(alternate, swapped);
            try (var loader = new URLClassLoader(new java.net.URL[]{swapped.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                System.clearProperty(INITIALIZED);
                JdbcFailure refused = assertThrows(JdbcFailure.class,
                        () -> JdbcDriverLoader.verified(loader).load(profile("pinned-driver", pinned)));
                assertEquals(JdbcFailure.Code.DRIVER_REFUSED, refused.code());
                assertNull(System.getProperty(INITIALIZED));
            }

            try (var loader = new URLClassLoader(new java.net.URL[]{installed.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                JdbcDriverLoader verified = JdbcDriverLoader.verified(loader, () -> {
                    try {
                        Files.copy(alternate, installed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.io.IOException failure) {
                        throw new java.io.UncheckedIOException(failure);
                    }
                });
                assertInstanceOf(Driver.class, verified.load(profile("pinned-driver", pinned)));
                assertEquals("original", System.getProperty(INITIALIZED),
                        "the versioned variant is resolved from the verified copy, not the replaced path");
            }
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    @Test
    void privateLoaderTcclCoversDependenciesResourcesServicesAndConnectAndAlwaysRestores(@TempDir Path workspace)
            throws Exception {
        String marker = "one";
        Path jar = compileContextDriver(workspace, "pinned-driver.jar", marker);
        try (var discovery = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             var sentinel = new URLClassLoader(new java.net.URL[0], ClassLoader.getPlatformClassLoader())) {
            Thread thread = Thread.currentThread();
            ClassLoader original = thread.getContextClassLoader();
            thread.setContextClassLoader(sentinel);
            try {
                Driver driver = JdbcDriverLoader.verified(discovery)
                        .load(profile("pinned-driver", sha256(jar)));
                assertEquals(marker + ":dependency:service", System.getProperty(INITIALIZED));
                assertSame(sentinel, thread.getContextClassLoader(), "load/initialization must restore caller TCCL");

                JdbcDriverLoader.inContext(driver, () -> driver.connect("jdbc:fixture:test", new java.util.Properties()));
                assertEquals(marker + ":dependency:service", System.getProperty(INITIALIZED + ".connect"));
                assertSame(sentinel, thread.getContextClassLoader(), "driver calls must restore caller TCCL");

                assertThrows(IllegalStateException.class,
                        () -> JdbcDriverLoader.inContext(driver, () -> { throw new IllegalStateException("fixture"); }));
                assertSame(sentinel, thread.getContextClassLoader(), "exceptional driver calls must restore TCCL");
            } finally {
                thread.setContextClassLoader(original);
                System.clearProperty(INITIALIZED);
                System.clearProperty(INITIALIZED + ".connect");
            }
        }
    }

    @Test
    void concurrentPrivateDriverContextsRemainIsolated(@TempDir Path workspace) throws Exception {
        Path firstJar = compileContextDriver(workspace.resolve("first"), "first-driver.jar", "first");
        Path secondJar = compileContextDriver(workspace.resolve("second"), "second-driver.jar", "second");
        try (var firstDiscovery = new URLClassLoader(new java.net.URL[]{firstJar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
             var secondDiscovery = new URLClassLoader(new java.net.URL[]{secondJar.toUri().toURL()},
                     ClassLoader.getPlatformClassLoader())) {
            Driver first = JdbcDriverLoader.verified(firstDiscovery)
                    .load(profile("first-driver", sha256(firstJar)));
            Driver second = JdbcDriverLoader.verified(secondDiscovery)
                    .load(profile("second-driver", sha256(secondJar)));

            CompletableFuture<String> one = CompletableFuture.supplyAsync(() -> contextMarker(first));
            CompletableFuture<String> two = CompletableFuture.supplyAsync(() -> contextMarker(second));
            assertEquals(List.of("first", "second"), List.of(one.join(), two.join()));
        } finally {
            System.clearProperty(INITIALIZED);
            System.clearProperty(INITIALIZED + ".connect");
        }
    }

    @Test
    void exactDriverIdSelectsAmongSameNamedClassesWithoutClasspathOrderOrCrossVisibility(@TempDir Path workspace)
            throws Exception {
        Path postgresql = compileDriver(workspace.resolve("postgresql"), "postgresql-42.7.7.jar", "postgresql");
        Path mysql = compileDriver(workspace.resolve("mysql"), "mysql-connector-j-9.5.0.jar", "mysql");
        try (var bundle = new URLClassLoader(new java.net.URL[]{mysql.toUri().toURL(), postgresql.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Driver selectedPostgresql = JdbcDriverLoader.verified(bundle)
                    .load(profile("postgresql-42.7.7", sha256(postgresql)));
            assertEquals("postgresql", System.getProperty(INITIALIZED));

            Driver selectedMysql = JdbcDriverLoader.verified(bundle)
                    .load(profile("mysql-connector-j-9.5.0", sha256(mysql)));
            assertEquals("mysql", System.getProperty(INITIALIZED));
            org.junit.jupiter.api.Assertions.assertNotSame(
                    selectedPostgresql.getClass().getClassLoader(), selectedMysql.getClass().getClassLoader());
            assertEquals(selectedPostgresql.getClass().getName(), selectedMysql.getClass().getName(),
                    "same driverClass name in two jars must remain isolated by exact driverId");
        } finally {
            System.clearProperty(INITIALIZED);
        }
    }

    private static JdbcProfile profile(String driverId, String digest) {
        JdbcProfile base = JdbcTestSupport.profile(JdbcTestSupport.query("SELECT id FROM users"));
        return new JdbcProfile(base.tenant(), base.name(), driverId, "fixture.PinnedDriver", digest,
                base.url(), base.username(), base.credentialRef(), base.isolation(), base.deadlineMs(),
                base.maxConcurrency(), base.maxParameters(), base.maxParameterBytes(), base.maxRows(),
                base.maxColumns(), base.maxCellBytes(), base.maxTotalBytes(), base.maxGeneratedKeyRows(),
                base.statements());
    }

    private static Path compileDriver(Path workspace, String jarName) throws Exception {
        return compileDriver(workspace, jarName, "true");
    }

    private static Path compileDriver(Path workspace, String jarName, String initializationMarker) throws Exception {
        Files.createDirectories(workspace);
        Path sources = Files.createDirectories(workspace.resolve("src/fixture"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Path source = sources.resolve("PinnedDriver.java");
        Files.writeString(source, """
                package fixture;
                public final class PinnedDriver implements java.sql.Driver {
                    static { System.setProperty("%s", "%s"); }
                    public java.sql.Connection connect(String u, java.util.Properties p) { return null; }
                    public boolean acceptsURL(String u) { return false; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
                        return new java.sql.DriverPropertyInfo[0];
                    }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
                }
                """.formatted(INITIALIZED, initializationMarker));
        int compiled = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", classes.toString(), source.toString());
        assertEquals(0, compiled);
        Path jar = workspace.resolve(jarName);
        try (OutputStream file = Files.newOutputStream(jar); var zip = new JarOutputStream(file);
             InputStream bytecode = Files.newInputStream(classes.resolve("fixture/PinnedDriver.class"))) {
            zip.putNextEntry(new JarEntry("fixture/PinnedDriver.class"));
            bytecode.transferTo(zip);
            zip.closeEntry();
        }
        return jar;
    }

    private static Path compileContextDriver(Path workspace, String jarName, String marker) throws Exception {
        Files.createDirectories(workspace);
        Path sources = Files.createDirectories(workspace.resolve("src/fixture"));
        Path classes = Files.createDirectories(workspace.resolve("classes"));
        Files.writeString(sources.resolve("Marker.java"),
                "package fixture; public interface Marker { String value(); }");
        Files.writeString(sources.resolve("MarkerImpl.java"),
                "package fixture; public final class MarkerImpl implements Marker { public String value() { return \"service\"; } }");
        Files.writeString(sources.resolve("Dependency.java"),
                "package fixture; final class Dependency { static String value() { return \"dependency\"; } }");
        Files.writeString(sources.resolve("PinnedDriver.java"), """
                package fixture;
                public final class PinnedDriver implements java.sql.Driver {
                    static { observe("%s"); }
                    private static void observe(String property) {
                        try {
                            ClassLoader loader = Thread.currentThread().getContextClassLoader();
                            String resource;
                            try (java.io.InputStream input = loader.getResourceAsStream("fixture/marker.txt")) {
                                resource = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            }
                            String service = java.util.ServiceLoader.load(Marker.class, loader).findFirst().orElseThrow().value();
                            System.setProperty(property, resource + ":" + Dependency.value() + ":" + service);
                        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
                    }
                    public java.sql.Connection connect(String u, java.util.Properties p) {
                        observe("%s.connect"); return null;
                    }
                    public boolean acceptsURL(String u) { return false; }
                    public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) {
                        return new java.sql.DriverPropertyInfo[0];
                    }
                    public int getMajorVersion() { return 1; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
                }
                """.formatted(INITIALIZED, INITIALIZED));
        List<String> arguments = new ArrayList<>(List.of("--release", "21", "-d", classes.toString()));
        try (var files = Files.list(sources)) {
            files.map(Path::toString).sorted().forEach(arguments::add);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                arguments.toArray(String[]::new)));

        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        try (var files = Files.walk(classes)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try { entries.put(classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path)); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        }
        entries.put("fixture/marker.txt", marker.getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/services/fixture.Marker", "fixture.MarkerImpl\n".getBytes(StandardCharsets.UTF_8));
        return writeJar(workspace.resolve(jarName), false, entries);
    }

    private static Path writeJar(Path jar, boolean multiRelease, Map<String, byte[]> entries) throws Exception {
        return writeJar(jar, manifest(multiRelease ? "true" : null), entries);
    }

    /** A leading manifest with the given Multi-Release value (none when null); a null manifest writes none. */
    private static Path writeJar(Path jar, Manifest manifest, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(jar.getParent());
        try (OutputStream file = Files.newOutputStream(jar);
             var zip = manifest == null ? new JarOutputStream(file) : new JarOutputStream(file, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new JarEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] jarEntry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile());
             InputStream input = archive.getInputStream(archive.getJarEntry(name))) {
            return input.readAllBytes();
        }
    }

    private static Manifest manifest(String multiRelease) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease != null) manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, multiRelease);
        return manifest;
    }

    /** Bytecode of fixture.PinnedDriver whose static initializer records {@code marker}. */
    private static byte[] variant(Path workspace, String marker) throws Exception {
        Path jar = compileDriver(workspace.resolve("variant-" + marker), "variant.jar", marker);
        return jarEntry(jar, "fixture/PinnedDriver.class");
    }

    private static String loadedMarker(Path jar) throws Exception {
        System.clearProperty(INITIALIZED);
        try (var loader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Driver driver = JdbcDriverLoader.verified(loader).load(profile("pinned-driver", sha256(jar)));
            assertInstanceOf(JdbcDriverLoader.PrivateDriverClassLoader.class, driver.getClass().getClassLoader());
            return System.getProperty(INITIALIZED);
        }
    }

    private static String contextMarker(Driver driver) {
        try {
            return JdbcDriverLoader.inContext(driver, () -> {
                ClassLoader before = Thread.currentThread().getContextClassLoader();
                try (InputStream input = before.getResourceAsStream("fixture/marker.txt")) {
                    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            });
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
