package ai.ravenroot.extensions.filesystem;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class FilesystemEncoding {
    private FilesystemEncoding() { }

    static byte[] encodeText(String value, long maxBytes) {
        if (value == null) throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_INPUT);
        if (value.length() > maxBytes) throw FilesystemNodeException.of(FilesystemNodeException.Reason.TOO_LARGE);
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            if (encoded.remaining() > maxBytes) throw FilesystemNodeException.of(FilesystemNodeException.Reason.TOO_LARGE);
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_ENCODING);
        }
    }

    static String decodeText(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException malformed) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_ENCODING);
        }
    }

    static byte[] decodeBase64(String value, long maxBytes) {
        if (value == null) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_ENCODING);
        }
        if (value.length() > encodedLengthLimit(maxBytes)) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.TOO_LARGE);
        }
        if (!canonicalAlphabetAndPadding(value)) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_ENCODING);
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length > maxBytes) throw FilesystemNodeException.of(FilesystemNodeException.Reason.TOO_LARGE);
            if (!Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_ENCODING);
            }
            return decoded;
        } catch (IllegalArgumentException invalid) {
            throw FilesystemNodeException.of(FilesystemNodeException.Reason.INVALID_ENCODING);
        }
    }

    static String encodeBase64(byte[] value) { return Base64.getEncoder().encodeToString(value); }

    private static long encodedLengthLimit(long maxBytes) {
        long groups = (maxBytes + 2L) / 3L;
        return groups > Integer.MAX_VALUE / 4L ? Integer.MAX_VALUE : groups * 4L;
    }

    private static boolean canonicalAlphabetAndPadding(String value) {
        if ((value.length() & 3) != 0) return false;
        int padding = value.endsWith("==") ? 2 : value.endsWith("=") ? 1 : 0;
        int alphabetEnd = value.length() - padding;
        for (int index = 0; index < alphabetEnd; index++) {
            char character = value.charAt(index);
            if (!((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9') || character == '+' || character == '/')) {
                return false;
            }
        }
        return padding == 0 || (alphabetEnd > 0 && value.indexOf('=') == alphabetEnd);
    }
}
