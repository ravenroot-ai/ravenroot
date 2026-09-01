package ai.ravenroot.extensions.spel;

import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpression;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpelDependencyIsolationTest {
    @Test
    void springIsExactApacheLicensedAndCompatibleWithTheJdk21BytecodeCeiling() throws Exception {
        Path expressionJar = jarOf(SpelExpression.class);
        Path coreJar = jarOf(org.springframework.core.SpringVersion.class);
        assertEquals("spring-expression-7.0.9.jar", expressionJar.getFileName().toString());
        assertEquals("spring-core-7.0.9.jar", coreJar.getFileName().toString());
        assertEquals("046434c40f43819729b9b1db0e6c659dfa68184c1c2c2efa5f7b3a5b27c4e2e2",
                sha256(expressionJar));
        assertEquals("5195f4722699b39878d99a832549fe65df2890b159d063b88fff31b1ca65ae36",
                sha256(coreJar));
        assertApacheLicense(expressionJar);
        assertApacheLicense(coreJar);
        Path loggingJar = jarOf(org.apache.commons.logging.Log.class);
        Path jspecifyJar = jarOf(org.jspecify.annotations.Nullable.class);
        assertEquals("commons-logging-1.3.5.jar", loggingJar.getFileName().toString());
        assertEquals("jspecify-1.0.0.jar", jspecifyJar.getFileName().toString());
        assertEquals("6d7a744e4027649fbb50895df9497d109f98c766a637062fe8d2eabbb3140ba4",
                sha256(loggingJar));
        assertEquals("1fad6e6be7557781e4d33729d49ae1cdc8fdda6fe477bb0cc68ce351eafdfbab",
                sha256(jspecifyJar));
        assertApacheLicense(loggingJar);
        assertTrue(classMajor(expressionJar, "org/springframework/expression/Expression.class") <= 65,
                "Spring Expression must remain loadable by the JDK 21 runtime baseline");
    }

    @Test
    void springRemainsAPluginDependencyAbsentFromTheDefaultDistribution() throws Exception {
        Path module = Path.of(System.getProperty("user.dir"));
        String pom = Files.readString(module.resolve("pom.xml"));
        assertTrue(pom.contains("<spring.framework.version>7.0.9</spring.framework.version>"));
        assertTrue(pom.contains("<artifactId>spring-expression</artifactId>"));
        assertTrue(pom.contains("<artifactId>spring-core</artifactId>"));

        String distribution = Files.readString(module.resolve("../../ravenroot-distribution/pom.xml"));
        assertFalse(distribution.contains("ravenroot-spel"));
        assertFalse(distribution.contains("spring-expression"));
    }

    private static Path jarOf(Class<?> type) throws Exception {
        URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
        return Path.of(location);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void assertApacheLicense(Path jar) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            var entry = archive.getJarEntry("META-INF/license.txt");
            if (entry == null) entry = archive.getJarEntry("META-INF/LICENSE.txt");
            assertTrue(entry != null, "dependency jar must carry its license");
            try (InputStream license = archive.getInputStream(entry)) {
                String text = new String(license.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(text.contains("Apache License"));
                assertTrue(text.contains("Version 2.0"));
            }
        }
    }

    private static int classMajor(Path jar, String entry) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile());
             InputStream input = archive.getInputStream(archive.getJarEntry(entry))) {
            byte[] header = input.readNBytes(8);
            return ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        }
    }
}
