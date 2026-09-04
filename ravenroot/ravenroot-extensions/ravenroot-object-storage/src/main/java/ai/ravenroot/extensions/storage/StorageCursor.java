package ai.ravenroot.extensions.storage;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/** Scope-bound envelope for an opaque provider continuation token. */
final class StorageCursor {
    private static final int MAX_PROVIDER_TOKEN_BYTES = 2048;
    private static final int MAX_CURSOR_CHARS = 4096;

    static String encode(StorageProfile profile, String tenantId, String prefix, int maximum,
                         Set<String> projection, String providerToken) {
        byte[] token = StorageRuntime.strictTextBytes(providerToken);
        if (token.length == 0 || token.length > MAX_PROVIDER_TOKEN_BYTES || unsafe(providerToken)) {
            throw StorageException.of(StorageException.Code.RESPONSE_INVALID);
        }
        return "v1." + scope(profile, tenantId, prefix, maximum, projection) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    static String decode(StorageProfile profile, String tenantId, String prefix, int maximum,
                         Set<String> projection, String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_CHARS || unsafe(cursor)) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
        String[] parts = cursor.split("\\.", -1);
        if (parts.length != 3 || !parts[0].equals("v1") || parts[1].length() != 64
                || !MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.US_ASCII),
                scope(profile, tenantId, prefix, maximum, projection).getBytes(StandardCharsets.US_ASCII))) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
        try {
            byte[] token = Base64.getUrlDecoder().decode(parts[2]);
            if (token.length == 0 || token.length > MAX_PROVIDER_TOKEN_BYTES
                    || !Base64.getUrlEncoder().withoutPadding().encodeToString(token).equals(parts[2])) {
                throw StorageException.of(StorageException.Code.INVALID_INPUT);
            }
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(token)).toString();
            if (unsafe(decoded)) throw StorageException.of(StorageException.Code.INVALID_INPUT);
            return decoded;
        } catch (IllegalArgumentException | CharacterCodingException invalid) {
            throw StorageException.of(StorageException.Code.INVALID_INPUT);
        }
    }

    private static String scope(StorageProfile profile, String tenantId, String prefix, int maximum,
                                Set<String> projection) {
        String material = String.join("\u0000", profile.name(), profile.origin().toASCIIString(), profile.region(),
                profile.bucket(), profile.keyPrefix(), profile.addressingStyle().name(),
                profile.signingBindingId(), sorted(profile.allowedOperations()), sorted(profile.allowedContentTypes()),
                Boolean.toString(profile.allowIfMatch()), Boolean.toString(profile.allowIfNoneMatch()),
                Integer.toString(profile.maxObjectBytes()), Integer.toString(profile.timeoutMs()),
                Integer.toString(profile.maxConcurrency()), Integer.toString(profile.maxRequestsPerSecond()),
                tenantId, StorageUri.scopedPrefix(profile, prefix),
                Integer.toString(maximum), projection.stream().sorted().reduce((a, b) -> a + "," + b).orElse(""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String sorted(Set<?> values) {
        return values.stream().map(Object::toString).sorted().reduce((a, b) -> a + "," + b).orElse("");
    }

    private static boolean unsafe(String value) {
        return value.codePoints().anyMatch(code -> code < 0x20 || code == 0x7f);
    }

    private StorageCursor() { }
}
