package ai.ravenroot.extensions.ocr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Strict canonical Base64 input plus bounded header-only image metadata. */
record OcrImage(byte[] bytes, String format, String extension, int width, int height, int pages) {
    private static final Set<String> INPUT_KEYS = Set.of("version", "imageBase64");
    private static final long MAXIMUM_PIXEL_WORK = 40_000_000L;
    private static final int MAXIMUM_TIFF_PAGES = 1_024;

    static OcrImage from(Object payload, int maximumBytes) {
        if (payload instanceof ai.ravenroot.api.payload.PayloadValue value) payload = value.toJava();
        if (!(payload instanceof Map<?, ?> map) || map.size() != 2
                || map.keySet().stream().anyMatch(key -> !(key instanceof String text) || !INPUT_KEYS.contains(text))
                || !"ocr.extract.v1".equals(map.get("version"))
                || !(map.get("imageBase64") instanceof String encoded)) {
            throw new IllegalArgumentException("invalid OCR payload");
        }
        if (encoded.isEmpty() || (encoded.length() & 3) != 0
                || encoded.length() > ((long) maximumBytes + 2L) / 3L * 4L) {
            throw new IllegalArgumentException("invalid OCR image encoding");
        }
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException malformed) { throw new IllegalArgumentException("invalid OCR image encoding"); }
        if (bytes.length == 0 || bytes.length > maximumBytes
                || !Base64.getEncoder().encodeToString(bytes).equals(encoded)) {
            throw new IllegalArgumentException("invalid OCR image encoding");
        }
        if (png(bytes)) return pngMetadata(bytes);
        if (jpeg(bytes)) return jpegMetadata(bytes);
        if (tiff(bytes)) return tiffMetadata(bytes);
        throw new IllegalArgumentException("unsupported OCR image");
    }

    private static boolean png(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < 24) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        return true;
    }

    private static OcrImage pngMetadata(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt(8) != 13 || buffer.getInt(12) != 0x49484452) throw new IllegalArgumentException("invalid PNG");
        int width = buffer.getInt(16), height = buffer.getInt(20);
        dimensions(width, height);
        return new OcrImage(bytes, "PNG", "png", width, height, 1);
    }

    private static boolean jpeg(byte[] bytes) {
        return bytes.length >= 4 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[bytes.length - 2] & 0xff) == 0xff && (bytes[bytes.length - 1] & 0xff) == 0xd9;
    }

    private static OcrImage jpegMetadata(byte[] bytes) {
        int offset = 2;
        while (offset + 4 <= bytes.length - 2) {
            if ((bytes[offset++] & 0xff) != 0xff) throw new IllegalArgumentException("invalid JPEG");
            int marker;
            do { marker = bytes[offset++] & 0xff; } while (marker == 0xff && offset < bytes.length);
            if (marker == 0xd9 || marker == 0xda) break;
            if (marker == 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
            if (offset + 2 > bytes.length) break;
            int length = unsignedShort(bytes, offset, ByteOrder.BIG_ENDIAN);
            if (length < 2 || offset + length > bytes.length) throw new IllegalArgumentException("invalid JPEG");
            if (isStartOfFrame(marker)) {
                if (length < 7) throw new IllegalArgumentException("invalid JPEG");
                int height = unsignedShort(bytes, offset + 3, ByteOrder.BIG_ENDIAN);
                int width = unsignedShort(bytes, offset + 5, ByteOrder.BIG_ENDIAN);
                dimensions(width, height);
                return new OcrImage(bytes, "JPEG", "jpg", width, height, 1);
            }
            offset += length;
        }
        throw new IllegalArgumentException("JPEG has no bounded frame metadata");
    }

    private static boolean isStartOfFrame(int marker) {
        return marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
    }

    private static boolean tiff(byte[] bytes) {
        return bytes.length >= 8 && ((bytes[0] == 'I' && bytes[1] == 'I' && bytes[2] == 42 && bytes[3] == 0)
                || (bytes[0] == 'M' && bytes[1] == 'M' && bytes[2] == 0 && bytes[3] == 42));
    }

    private static OcrImage tiffMetadata(byte[] bytes) {
        ByteOrder order = bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(order);
        long ifd = Integer.toUnsignedLong(buffer.getInt(4));
        int width = -1, height = -1, pages = 0;
        long cumulativePixels = 0;
        Set<Long> visitedIfds = new HashSet<>();
        while (ifd != 0 && pages < MAXIMUM_TIFF_PAGES) {
            if (!visitedIfds.add(ifd)) throw new IllegalArgumentException("invalid TIFF directory chain");
            if (ifd > bytes.length - 2L) throw new IllegalArgumentException("invalid TIFF");
            int count = Short.toUnsignedInt(buffer.getShort((int) ifd));
            long entriesEnd = ifd + 2L + (long) count * 12L;
            if (entriesEnd + 4L > bytes.length) throw new IllegalArgumentException("invalid TIFF");
            int pageWidth = -1, pageHeight = -1;
            for (int index = 0; index < count; index++) {
                int entry = (int) ifd + 2 + index * 12;
                int tag = Short.toUnsignedInt(buffer.getShort(entry));
                int type = Short.toUnsignedInt(buffer.getShort(entry + 2));
                long amount = Integer.toUnsignedLong(buffer.getInt(entry + 4));
                if ((tag == 256 || tag == 257) && amount == 1 && (type == 3 || type == 4)) {
                    int value = type == 3 ? Short.toUnsignedInt(buffer.getShort(entry + 8)) : buffer.getInt(entry + 8);
                    if (tag == 256) pageWidth = value;
                    if (tag == 257) pageHeight = value;
                }
            }
            long pagePixels = dimensions(pageWidth, pageHeight);
            try { cumulativePixels = Math.addExact(cumulativePixels, pagePixels); }
            catch (ArithmeticException overflow) { throw new IllegalArgumentException("invalid or excessive image dimensions"); }
            if (cumulativePixels > MAXIMUM_PIXEL_WORK) {
                throw new IllegalArgumentException("invalid or excessive image dimensions");
            }
            if (pages == 0) { width = pageWidth; height = pageHeight; }
            pages++;
            ifd = Integer.toUnsignedLong(buffer.getInt((int) entriesEnd));
        }
        if (pages == 0) throw new IllegalArgumentException("TIFF has no image directories");
        if (ifd != 0) throw new IllegalArgumentException("TIFF page count exceeds the supported bound");
        return new OcrImage(bytes, "TIFF", "tif", width, height, pages);
    }

    private static int unsignedShort(byte[] bytes, int offset, ByteOrder order) {
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(order).getShort());
    }

    private static long dimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid or excessive image dimensions");
        }
        final long pixels;
        try { pixels = Math.multiplyExact((long) width, (long) height); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("invalid or excessive image dimensions"); }
        if (pixels > MAXIMUM_PIXEL_WORK) throw new IllegalArgumentException("invalid or excessive image dimensions");
        return pixels;
    }
}
