package ai.ravenroot.extensions.filesystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemPathsAndEncodingTest {
    @TempDir Path root;

    @Test void pathSyntaxRejectsTraversalAbsoluteWindowsEmptyDotAndControls() {
        for (String path : new String[]{"", "/etc/passwd", "../escape", "a/../b", "a//b", "a/./b",
                "C:/secret", "C:\\secret", "\\\\server\\share", "a\\b", "a\u0000b", "a/\u0085b"}) {
            FilesystemNodeException failure = assertThrows(FilesystemNodeException.class,
                    () -> FilesystemPaths.parse(root, path), path);
            assertEquals(FilesystemNodeException.Reason.OUTSIDE_ROOT, failure.reason());
            assertEquals("OUTSIDE_ROOT", failure.getMessage());
        }
    }

    @Test void unicodeCodePointsArePreservedAndMatchedWithoutNormalization() {
        FilesystemProfile profile = new FilesystemProfile("p", root, true, false, Set.of("data/**"),
                100, 1, Duration.ofSeconds(1));
        var parsed = FilesystemPaths.parse(root, "data/unicode-東京.txt");
        assertEquals("data/unicode-東京.txt", parsed.display());
        assertEquals("unicode-東京.txt", parsed.leaf().toString());
        assertEquals(true, profile.permits(parsed.relative()));
    }

    @Test void privateTempNamespaceIsReservedWithoutCapturingTheFormerPrefix() {
        var legitimate = FilesystemPaths.parse(root, ".ravenroot-fs-report");
        assertEquals(".ravenroot-fs-report", legitimate.leaf().toString());

        for (String reserved : new String[]{FilesystemTempNames.PREFIX + "anything",
                "folder/" + FilesystemTempNames.PREFIX + "anything"}) {
            FilesystemNodeException failure = assertThrows(FilesystemNodeException.class,
                    () -> FilesystemPaths.parse(root, reserved));
            assertEquals(FilesystemNodeException.Reason.AUTHORITY_REFUSED, failure.reason());
        }

        assertThrows(IllegalArgumentException.class, () -> new FilesystemProfile("reserved", root,
                true, true, Set.of(FilesystemTempNames.PREFIX + "*"), 100, 1, Duration.ofSeconds(1)));
        assertTrue(new FilesystemProfile("broad", root, true, true, Set.of("**"),
                100, 1, Duration.ofSeconds(1)).permits(legitimate.relative()));
    }

    @Test void privateTempGrammarIsExactAndBoundToProfileAndCanonicalRoot() {
        FilesystemProfile active = new FilesystemProfile("active", root, true, true, Set.of("**"),
                100, 1, Duration.ofSeconds(1));
        FilesystemProfile other = new FilesystemProfile("other", root, true, true, Set.of("**"),
                100, 1, Duration.ofSeconds(1));
        Path exact = FilesystemTempNames.create(active, root.getFileSystem().getPath("target.txt"),
                "0".repeat(32), "1".repeat(32));

        assertTrue(FilesystemTempNames.isOwnedBy(active, exact));
        assertFalse(FilesystemTempNames.isOwnedBy(other, exact));
        for (String malformed : new String[]{FilesystemTempNames.PREFIX + "stale.tmp",
                exact + ".extra", exact.toString().replace(".tmp", ".TMP"),
                exact.toString().replaceFirst("-0", "-G")}) {
            assertFalse(FilesystemTempNames.isOwnedBy(active, root.getFileSystem().getPath(malformed)), malformed);
        }
    }

    @Test void utf8IsStrictAndBoundedAtExactEncodedBytes() {
        assertArrayEquals(new byte[]{(byte) 0xc3, (byte) 0xb1}, FilesystemEncoding.encodeText("ñ", 2));
        assertEquals(FilesystemNodeException.Reason.TOO_LARGE,
                assertThrows(FilesystemNodeException.class, () -> FilesystemEncoding.encodeText("ñ", 1)).reason());
        assertEquals(FilesystemNodeException.Reason.INVALID_ENCODING,
                assertThrows(FilesystemNodeException.class, () -> FilesystemEncoding.encodeText("\ud800", 20)).reason());
        assertEquals(FilesystemNodeException.Reason.INVALID_ENCODING,
                assertThrows(FilesystemNodeException.class, () ->
                        FilesystemEncoding.decodeText(new byte[]{(byte) 0xc3, 0x28})).reason());
    }

    @Test void base64AcceptsOnlyCanonicalBasicFormAndBoundsBeforeDecode() {
        assertArrayEquals("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                FilesystemEncoding.decodeBase64("aGVsbG8=", 5));
        for (String invalid : new String[]{"aGVsbG8", "aGVsbG8===", "aGVs bG8=", "aGVsbG8_", "YR=="}) {
            assertEquals(FilesystemNodeException.Reason.INVALID_ENCODING,
                    assertThrows(FilesystemNodeException.class, () ->
                            FilesystemEncoding.decodeBase64(invalid, 64), invalid).reason());
        }
        assertEquals(FilesystemNodeException.Reason.TOO_LARGE,
                assertThrows(FilesystemNodeException.class, () ->
                        FilesystemEncoding.decodeBase64("aGVsbG8=", 4)).reason());
    }

    @Test void typedFailuresNeverRetainRawIoCauses() {
        FilesystemNodeException failure = FilesystemNodeException.of(
                FilesystemNodeException.Reason.TEMPORARY_IO,
                new java.io.IOException(root.resolve("secret.txt").toString()));
        assertEquals("TEMPORARY_IO", failure.getMessage());
        assertNull(failure.getCause());
    }
}
