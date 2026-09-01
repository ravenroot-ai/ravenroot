package ai.ravenroot.core.security.nodepackage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Core-private AWS Signature Version 4 implementation over the final managed request values. */
final class AwsSigV4Signer {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String TERMINATOR = "aws4_request";
    private static final int MAX_CREDENTIAL_CHARACTERS = 16 * 1024;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyyMMdd", Locale.ROOT).withZone(ZoneOffset.UTC);

    private AwsSigV4Signer() { }

    /**
     * Refuses request targets that JDK 21 {@code HttpClient} rewrites while serializing HTTP/1.1.
     *
     * <p>The transport preserves an all-ASCII raw path/query byte-for-byte. If either contains a
     * literal non-ASCII character it NFC-normalizes and UTF-8-percent-encodes the combined target.
     * Signing that source URI would therefore authenticate different octets. Call this before
     * resolving signing credentials; callers can represent the same UTF-8 octets explicitly with
     * percent escapes.</p>
     */
    static void requireTransportStableTarget(URI destination) {
        requireAscii(destination.getRawPath());
        requireAscii(destination.getRawQuery());
    }

    static Signed sign(String method, URI destination, Map<String, List<String>> submittedHeaders,
                       byte[] body, Instant now, char[] encodedCredential, String region, String service) {
        if (!"s3".equals(service)) {
            throw new IllegalArgumentException("unsupported SigV4 service profile");
        }
        Credential credential = Credential.parse(encodedCredential);
        try {
            String timestamp = TIMESTAMP.format(now);
            String date = DATE.format(now);
            String payloadHash = hex(sha256(body));

            Map<String, List<String>> outgoing = new LinkedHashMap<>(submittedHeaders);
            outgoing.put("x-amz-date", List.of(timestamp));
            outgoing.put("x-amz-content-sha256", List.of(payloadHash));
            if (credential.sessionToken() != null) {
                outgoing.put("x-amz-security-token", List.of(credential.sessionToken()));
            }

            TreeMap<String, String> canonicalHeaders = new TreeMap<>();
            canonicalHeaders.put("host", canonicalHost(destination));
            outgoing.forEach((name, values) -> canonicalHeaders.put(name.toLowerCase(Locale.ROOT),
                    values.stream().map(AwsSigV4Signer::normalizeHeaderValue)
                            .reduce((left, right) -> left + "," + right).orElse("")));
            StringBuilder headerBlock = new StringBuilder();
            canonicalHeaders.forEach((name, value) -> headerBlock.append(name).append(':')
                    .append(value).append('\n'));
            String signedHeaderNames = String.join(";", canonicalHeaders.keySet());
            String canonicalRequest = method + '\n' + canonicalPath(destination) + '\n'
                    + canonicalQuery(destination) + '\n' + headerBlock + '\n' + signedHeaderNames + '\n'
                    + payloadHash;
            String scope = date + '/' + region + '/' + service + '/' + TERMINATOR;
            String stringToSign = ALGORITHM + '\n' + timestamp + '\n' + scope + '\n'
                    + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

            byte[] signingKey = deriveKey(credential.secretAccessKey(), date, region, service);
            String signature;
            try {
                signature = hex(hmac(signingKey, stringToSign));
            } finally {
                Arrays.fill(signingKey, (byte) 0);
            }
            outgoing.put("authorization", List.of(ALGORITHM + " Credential=" + credential.accessKeyId()
                    + '/' + scope + ", SignedHeaders=" + signedHeaderNames + ", Signature=" + signature));
            return new Signed(Map.copyOf(outgoing), canonicalRequest, stringToSign);
        } finally {
            credential.close();
        }
    }

    private static byte[] deriveKey(char[] secret, String date, String region, String service) {
        byte[] secretBytes = ascii(secret);
        byte[] initial = new byte[4 + secretBytes.length];
        initial[0] = 'A'; initial[1] = 'W'; initial[2] = 'S'; initial[3] = '4';
        System.arraycopy(secretBytes, 0, initial, 4, secretBytes.length);
        Arrays.fill(secretBytes, (byte) 0);
        byte[] dateKey = hmac(initial, date);
        Arrays.fill(initial, (byte) 0);
        byte[] regionKey = hmac(dateKey, region);
        Arrays.fill(dateKey, (byte) 0);
        byte[] serviceKey = hmac(regionKey, service);
        Arrays.fill(regionKey, (byte) 0);
        byte[] signingKey = hmac(serviceKey, TERMINATOR);
        Arrays.fill(serviceKey, (byte) 0);
        return signingKey;
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SigV4 primitive unavailable", unavailable);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static String canonicalHost(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.indexOf(':') >= 0 && !(host.startsWith("[") && host.endsWith("]"))) {
            host = '[' + host + ']';
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()));
        return defaultPort ? host : host + ':' + port;
    }

    private static String canonicalPath(URI uri) {
        String raw = uri.getRawPath();
        if (raw == null || raw.isEmpty()) return "/";
        return Arrays.stream(raw.split("/", -1)).map(segment -> encode(decode(segment)))
                .reduce((left, right) -> left + '/' + right).orElse("");
    }

    private static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) return "";
        List<QueryPart> parts = new ArrayList<>();
        for (String pair : raw.split("&", -1)) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parts.add(new QueryPart(encode(decode(name)), encode(decode(value))));
        }
        parts.sort(Comparator.comparing(QueryPart::name).thenComparing(QueryPart::value));
        return parts.stream().map(part -> part.name() + '=' + part.value())
                .reduce((left, right) -> left + '&' + right).orElse("");
    }

    private static byte[] decode(String raw) {
        var bytes = new java.io.ByteArrayOutputStream(raw.length());
        for (int index = 0; index < raw.length();) {
            char current = raw.charAt(index);
            if (current == '%') {
                if (index + 2 >= raw.length()) throw new IllegalArgumentException("invalid URI encoding");
                int high = Character.digit(raw.charAt(index + 1), 16);
                int low = Character.digit(raw.charAt(index + 2), 16);
                if (high < 0 || low < 0) throw new IllegalArgumentException("invalid URI encoding");
                bytes.write((high << 4) | low);
                index += 3;
            } else {
                int codePoint = raw.codePointAt(index);
                byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
                bytes.writeBytes(encoded);
                index += Character.charCount(codePoint);
            }
        }
        byte[] result = bytes.toByteArray();
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(result));
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("invalid UTF-8 URI encoding", invalid);
        }
        return result;
    }

    private static String encode(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int value = raw & 0xff;
            if ((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9') || value == '-' || value == '_'
                    || value == '.' || value == '~') {
                result.append((char) value);
            } else {
                result.append('%').append(HEX[value >>> 4]).append(HEX[value & 0xf]);
            }
        }
        return result.toString();
    }

    private static String normalizeHeaderValue(String value) {
        return value.strip().replaceAll("[ \\t]+", " ");
    }

    private static byte[] ascii(char[] value) {
        byte[] result = new byte[value.length];
        for (int i = 0; i < value.length; i++) {
            char current = value[i];
            if (current < 0x21 || current > 0x7e) {
                Arrays.fill(result, (byte) 0);
                throw new IllegalArgumentException("invalid SigV4 credential");
            }
            result[i] = (byte) current;
        }
        return result;
    }

    private static void requireAscii(String component) {
        if (component == null) return;
        for (int index = 0; index < component.length(); index++) {
            if (component.charAt(index) >= 0x80) {
                throw new IllegalArgumentException("signed request target must use ASCII or percent escapes");
            }
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    record Signed(Map<String, List<String>> headers, String canonicalRequest, String stringToSign) { }
    private record QueryPart(String name, String value) { }

    private record Credential(String accessKeyId, char[] secretAccessKey, String sessionToken)
            implements AutoCloseable {
        static Credential parse(char[] encoded) {
            if (encoded == null || encoded.length == 0 || encoded.length > MAX_CREDENTIAL_CHARACTERS) {
                throw new IllegalArgumentException("invalid SigV4 credential");
            }
            int first = -1;
            int second = -1;
            for (int i = 0; i < encoded.length; i++) {
                if (encoded[i] == '\r' || encoded[i] == '\0') {
                    throw new IllegalArgumentException("invalid SigV4 credential");
                }
                if (encoded[i] == '\n') {
                    if (first < 0) first = i;
                    else if (second < 0) second = i;
                    else throw new IllegalArgumentException("invalid SigV4 credential");
                }
            }
            int secretEnd = second < 0 ? encoded.length : second;
            if (first <= 0 || secretEnd <= first + 1 || second == encoded.length - 1) {
                throw new IllegalArgumentException("invalid SigV4 credential");
            }
            char[] access = Arrays.copyOfRange(encoded, 0, first);
            char[] secret = Arrays.copyOfRange(encoded, first + 1, secretEnd);
            char[] token = second < 0 ? null : Arrays.copyOfRange(encoded, second + 1, encoded.length);
            boolean accepted = false;
            try {
                validateAscii(access);
                validateAscii(secret);
                if (token != null) validateAscii(token);
                Credential credential = new Credential(new String(access), secret,
                        token == null ? null : new String(token));
                accepted = true;
                return credential;
            } finally {
                Arrays.fill(access, '\0');
                if (token != null) Arrays.fill(token, '\0');
                if (!accepted) Arrays.fill(secret, '\0');
            }
        }

        @Override public void close() { Arrays.fill(secretAccessKey, '\0'); }
    }

    private static void validateAscii(char[] value) {
        for (char current : value) {
            if (current < 0x21 || current > 0x7e) {
                throw new IllegalArgumentException("invalid SigV4 credential");
            }
        }
    }
}
