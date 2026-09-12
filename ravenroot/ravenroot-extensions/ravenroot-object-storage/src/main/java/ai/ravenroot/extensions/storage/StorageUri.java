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

    static URI listDestination(StorageProfile profile, String prefix, int maximum, String cursor) {
        String scoped = scopedPrefix(profile, prefix);
        String path = profile.addressingStyle() == StorageProfile.AddressingStyle.PATH
                ? "/" + profile.bucket() : "/";
        StringBuilder query = new StringBuilder("list-type=2&max-keys=").append(maximum);
        if (!scoped.isEmpty()) query.append("&prefix=").append(encodeComponent(scoped));
        if (cursor != null) query.append("&continuation-token=").append(encodeComponent(cursor));
        return URI.create(profile.origin().toASCIIString() + path + "?" + query);
    }

    static URI deleteDestination(StorageProfile profile, String key, String versionId) {
        URI object = destination(profile, key);
        return versionId == null ? object : URI.create(object.toASCIIString()
                + "?versionId=" + encodeComponent(versionId));
    }

    static String scopedPrefix(StorageProfile profile, String prefix) {
        String child = prefix == null ? "" : prefix;
        if (!child.isEmpty()) validateRelative(child, "prefix");
        if (profile.keyPrefix().isEmpty()) return child;
        if (child.isEmpty()) {
            String scoped = profile.keyPrefix() + "/";
            if (strictUtf8(scoped).length > MAX_KEY_BYTES) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
            return scoped;
        }
        String scoped = profile.keyPrefix() + "/" + child;
        validateRelative(scoped, "prefix");
        return scoped;
    }

    static String relativeListedKey(StorageProfile profile, String requestedPrefix, String key) {
        validateRelative(key, "key");
        String scoped = scopedPrefix(profile, requestedPrefix);
        if (!scoped.isEmpty() && !key.startsWith(scoped)) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
        if (profile.keyPrefix().isEmpty()) return key;
        String root = profile.keyPrefix() + "/";
        if (!key.startsWith(root) || key.length() == root.length()) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
        return key.substring(root.length());
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
        return encode(value, true);
    }

    private static String encodeComponent(String value) {
        if (value == null || value.isEmpty() || value.length() > 4096
                || value.codePoints().anyMatch(code -> code < 0x20 || code == 0x7f)) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
        return encode(value, false);
    }

    private static String encode(String value, boolean preserveSlash) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte current : strictUtf8(value)) {
            int octet = current & 0xff;
            if ((octet >= 'a' && octet <= 'z') || (octet >= 'A' && octet <= 'Z')
                    || (octet >= '0' && octet <= '9') || octet == '-' || octet == '_' || octet == '.'
                    || octet == '~' || preserveSlash && octet == '/') {
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
