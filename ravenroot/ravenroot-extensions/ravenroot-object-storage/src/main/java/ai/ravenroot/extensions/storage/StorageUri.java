package ai.ravenroot.extensions.storage;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class StorageUri {
    private static final int MAX_KEY_BYTES = 1024;

    static String validatePrefix(String prefix) {
        if (prefix.isEmpty()) return "";
        validateRelative(prefix, "prefix");
        return prefix;
    }

    static URI destination(StorageProfile profile, String key) {
        validateRelative(key, "key");
        String relative = profile.keyPrefix().isEmpty() ? key : profile.keyPrefix() + "/" + key;
        validateRelative(relative, "key");
        String path = profile.addressingStyle() == StorageProfile.AddressingStyle.PATH
                ? "/" + profile.bucket() + "/" + encodePath(relative)
                : "/" + encodePath(relative);
        return URI.create(profile.origin().toASCIIString() + path);
    }

    private static void validateRelative(String value, String field) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.endsWith("/")
                || value.indexOf('\\') >= 0 || value.indexOf('%') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
        byte[] bytes = strictUtf8(value);
        if (bytes.length > MAX_KEY_BYTES) throw StorageException.of(StorageException.Code.INVALID_INPUT);
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
        }
        value.codePoints().forEach(code -> {
            if (code <= 0x20 || code == 0x7f) throw StorageException.of(StorageException.Code.INVALID_INPUT);
        });
    }

    private static byte[] strictUtf8(String value) {
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] copied = new byte[bytes.remaining()];
            bytes.get(copied);
            return copied;
        } catch (CharacterCodingException invalid) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
    }

    private static String encodePath(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte current : strictUtf8(value)) {
            int octet = current & 0xff;
            if ((octet >= 'a' && octet <= 'z') || (octet >= 'A' && octet <= 'Z')
                    || (octet >= '0' && octet <= '9') || octet == '-' || octet == '_' || octet == '.'
                    || octet == '~' || octet == '/') {
                out.append((char) octet);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(octet >>> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(octet & 15, 16)));
            }
        }
        return out.toString();
    }

    private StorageUri() { }
}
