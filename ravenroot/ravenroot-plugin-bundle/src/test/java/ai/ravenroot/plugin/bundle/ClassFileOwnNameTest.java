package ai.ravenroot.plugin.bundle;

import ai.ravenroot.core.plugin.fixture.ReservedFixtureBehavior;
import ai.ravenroot.extensions.fixture.AllowedFixtureBehavior;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rejection-first: garbage bytes and a truncated class file are shown rejected before a well-formed
 * class file is shown to parse correctly. This is the check {@code Class.forName} never has to pass
 * because it is never called — see {@link ClassFileOwnName}'s class javadoc.
 */
class ClassFileOwnNameTest {

    @Test
    void rejectsBytesWithTheWrongMagicNumber() {
        byte[] garbage = {0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07};
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> ClassFileOwnName.read(garbage, "garbage.class"));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
    }

    @Test
    void rejectsATruncatedClassFile() throws IOException {
        byte[] real = readClassBytes(AllowedFixtureBehavior.class);
        byte[] truncated = new byte[10]; // magic + partial version/constant-pool-count only
        System.arraycopy(real, 0, truncated, 0, truncated.length);
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> ClassFileOwnName.read(truncated, "truncated.class"));
        assertEquals(PluginBundleException.Reason.MALFORMED_MANIFEST, rejection.reason());
    }

    @Test
    void rejectsAClassFileLargerThanTheCeiling() {
        byte[] oversized = new byte[ClassFileOwnName.MAX_CLASS_FILE_BYTES + 1];
        PluginBundleException rejection = assertThrows(PluginBundleException.class,
                () -> ClassFileOwnName.read(oversized, "oversized.class"));
        assertEquals(PluginBundleException.Reason.BUNDLE_TOO_LARGE, rejection.reason());
    }

    @Test
    void readsItsOwnBinaryNameFromARealCompiledReservedPackageClass() throws IOException {
        byte[] bytes = readClassBytes(ReservedFixtureBehavior.class);
        String binaryName = ClassFileOwnName.read(bytes, "ReservedFixtureBehavior.class");
        assertEquals("ai.ravenroot.core.plugin.fixture.ReservedFixtureBehavior", binaryName);
        assertTrue(ReservedPluginPackages.isReserved(binaryName));
    }

    @Test
    void readsItsOwnBinaryNameFromARealCompiledAllowedPackageClass() throws IOException {
        byte[] bytes = readClassBytes(AllowedFixtureBehavior.class);
        String binaryName = ClassFileOwnName.read(bytes, "AllowedFixtureBehavior.class");
        assertEquals("ai.ravenroot.extensions.fixture.AllowedFixtureBehavior", binaryName);
        assertFalse(ReservedPluginPackages.isReserved(binaryName));
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resourceName = type.getSimpleName() + ".class";
        try (InputStream in = type.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalStateException("Test fixture not compiled: " + resourceName);
            }
            return in.readAllBytes();
        }
    }
}
