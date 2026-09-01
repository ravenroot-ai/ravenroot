package ai.ravenroot.extensions.ocr;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OcrFixtureIntegrityTest {
    @Test void committedPngFixtureHasStableChecksumAndMetadata() throws Exception {
        assertFixture("png", "f373043cd3c8ca71a8d0c70f70fc3dc9943a5aedcb31d0777f2d7e8206963cd7", "PNG");
    }

    @Test void committedJpegFixtureHasStableChecksumAndMetadata() throws Exception {
        assertFixture("jpeg", "fcbaeaedd421ed52a2814cd60ac4054479104401995138a98ea6bdb6a21b928e", "JPEG");
    }

    @Test void committedTiffFixtureHasStableChecksumAndMetadata() throws Exception {
        assertFixture("tiff", "d2360bf5f0f1b29a9af933b5a48d9a197544d2019cbcaa31da7194bf62198db2", "TIFF");
    }

    @Test void liveTextFixtureHasStableChecksumAndMetadata() throws Exception {
        try (InputStream stream = OcrFixtureIntegrityTest.class.getResourceAsStream(
                "/fixtures/ravenroot-text.png.base64")) {
            byte[] bytes = Base64.getMimeDecoder().decode(java.util.Objects.requireNonNull(stream).readAllBytes());
            assertEquals("90290fd3b33a0aa3cfff9a4590bfea6df8cf424edc120993d243ccea282da616",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            OcrImage image = OcrImage.from(OcrTestSupport.payload(bytes), 4096);
            assertEquals(400, image.width());
            assertEquals(100, image.height());
        }
    }

    private static void assertFixture(String extension, String checksum, String format) throws Exception {
        String resource = "/fixtures/ocr-400x100." + extension + ".base64";
        try (InputStream stream = OcrFixtureIntegrityTest.class.getResourceAsStream(resource)) {
            byte[] encoded = java.util.Objects.requireNonNull(stream, resource).readAllBytes();
            byte[] bytes = Base64.getMimeDecoder().decode(encoded);
            assertEquals(checksum, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            OcrImage image = OcrImage.from(OcrTestSupport.payload(bytes), 1024);
            assertEquals(format, image.format());
            assertEquals(400, image.width());
            assertEquals(100, image.height());
        }
    }
}
