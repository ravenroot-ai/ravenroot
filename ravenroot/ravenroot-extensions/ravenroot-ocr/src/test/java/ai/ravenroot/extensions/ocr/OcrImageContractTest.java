package ai.ravenroot.extensions.ocr;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OcrImageContractTest {
    @Test void acceptsOnlyTheThreeDeclaredImageFormatsAndExtractsBoundedMetadata() {
        OcrImage png = OcrImage.from(OcrTestSupport.payload(OcrTestSupport.png()), 1024);
        OcrImage jpeg = OcrImage.from(OcrTestSupport.payload(OcrTestSupport.jpeg()), 1024);
        OcrImage tiff = OcrImage.from(OcrTestSupport.payload(OcrTestSupport.tiff()), 1024);
        assertEquals("PNG", png.format()); assertEquals(400, png.width()); assertEquals(100, png.height());
        assertEquals("JPEG", jpeg.format()); assertEquals(400, jpeg.width()); assertEquals(100, jpeg.height());
        assertEquals("TIFF", tiff.format()); assertEquals(1, tiff.pages());
    }

    @Test void base64MustBeCanonicalPaddedAndWithinThePredecodeCeiling() {
        byte[] png = OcrTestSupport.png();
        String canonical = Base64.getEncoder().encodeToString(OcrTestSupport.jpeg());
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(
                Map.of("version", OcrExtractNodeBehavior.CONTRACT, "imageBase64", canonical.replace("=", "")), 1024));
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(
                Map.of("version", OcrExtractNodeBehavior.CONTRACT, "imageBase64", canonical + "\n"), 1024));
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(OcrTestSupport.payload(png), png.length - 1));
    }

    @Test void payloadHasExactlyVersionAndOneInlineImage() {
        var extra = new LinkedHashMap<String, Object>(OcrTestSupport.payload(OcrTestSupport.png()));
        extra.put("url", "https://attacker.invalid/image.png");
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(extra, 1024));
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(
                Map.of("version", OcrExtractNodeBehavior.CONTRACT, "path", "/etc/passwd"), 1024));
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(
                Map.of("version", "ocr.extract.v2", "imageBase64", "AAAA"), 1024));
    }

    @Test void decodedPixelWorkHasAnAbsoluteBoundaryIndependentOfCompressedBytes() {
        byte[] atLimit = OcrTestSupport.png();
        java.nio.ByteBuffer.wrap(atLimit).order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(16, 10_000).putInt(20, 4_000);
        assertEquals(10_000, OcrImage.from(OcrTestSupport.payload(atLimit), 1024).width());

        byte[] overLimit = OcrTestSupport.png();
        java.nio.ByteBuffer.wrap(overLimit).order(java.nio.ByteOrder.BIG_ENDIAN)
                .putInt(16, 10_000).putInt(20, 4_001);
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(overLimit), 1024));
    }

    @Test void tiffChargesEveryPageAgainstOneOverflowSafePixelWorkBudget() {
        byte[] exactlyAtTotal = OcrTestSupport.tiffPages(new int[][] {{400, 100}, {9_990, 4_000}});
        OcrImage accepted = OcrImage.from(OcrTestSupport.payload(exactlyAtTotal), 1024);
        assertEquals(2, accepted.pages());
        assertEquals(400, accepted.width());
        assertEquals(100, accepted.height());

        byte[] totalPlusOne = OcrTestSupport.tiffPages(new int[][] {{400, 100}, {1, 39_960_001}});
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(totalPlusOne), 1024));
    }

    @Test void tiffRefusesAnOversizedLaterPageOverflowShapedDimensionsAndDirectoryLoops() {
        byte[] oversizedLaterPage = OcrTestSupport.tiffPages(new int[][] {{400, 100}, {10_000, 4_001}});
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(oversizedLaterPage), 1024));

        byte[] overflowShaped = OcrTestSupport.tiffPages(new int[][] {{400, 100}, {Integer.MAX_VALUE, 2}});
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(overflowShaped), 1024));

        byte[] loop = OcrTestSupport.tiffPages(new int[][] {{400, 100}, {400, 100}});
        ByteBuffer.wrap(loop).order(ByteOrder.BIG_ENDIAN).putInt(34, 8);
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(OcrTestSupport.payload(loop), 1024));
    }

    @Test void tiffRequiresDirectoriesAndCompletePerPageDimensionsInEitherByteOrder() {
        byte[] empty = new byte[] {'M', 'M', 0, 42, 0, 0, 0, 0};
        assertThrows(IllegalArgumentException.class, () -> OcrImage.from(OcrTestSupport.payload(empty), 1024));

        byte[] missingHeight = OcrTestSupport.tiffPages(new int[][] {{400, 100}});
        ByteBuffer.wrap(missingHeight).order(ByteOrder.BIG_ENDIAN).putShort(8, (short) 1);
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(missingHeight), 1024));

        OcrImage littleEndian = OcrImage.from(OcrTestSupport.payload(
                OcrTestSupport.tiffPages(new int[][] {{400, 100}}, ByteOrder.LITTLE_ENDIAN)), 1024);
        assertEquals(400, littleEndian.width());
        assertEquals(100, littleEndian.height());
    }

    @Test void tiffRefusesMalformedDirectoryOffsetsAndCountsBeforeAnyDecodeWork() {
        byte[] offsetPastEnd = OcrTestSupport.tiff();
        ByteBuffer.wrap(offsetPastEnd).order(ByteOrder.BIG_ENDIAN).putInt(4, offsetPastEnd.length - 1);
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(offsetPastEnd), 1024));

        byte[] countPastEnd = OcrTestSupport.tiff();
        ByteBuffer.wrap(countPastEnd).order(ByteOrder.BIG_ENDIAN).putShort(8, (short) 0xffff);
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(countPastEnd), 1024));
    }

    @Test void tiffAcceptsExactly1024DirectoriesAndRefuses1025() {
        int[][] atLimit = new int[1_024][2];
        for (int[] page : atLimit) { page[0] = 1; page[1] = 1; }
        assertEquals(1_024, OcrImage.from(OcrTestSupport.payload(OcrTestSupport.tiffPages(atLimit)), 64 * 1024).pages());

        int[][] overLimit = new int[1_025][2];
        for (int[] page : overLimit) { page[0] = 1; page[1] = 1; }
        assertThrows(IllegalArgumentException.class,
                () -> OcrImage.from(OcrTestSupport.payload(
                        OcrTestSupport.tiffPages(overLimit, ByteOrder.LITTLE_ENDIAN)), 64 * 1024));
    }
}
