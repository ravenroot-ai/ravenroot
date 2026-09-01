package ai.ravenroot.api.payload;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The JSON encoding of the payload type model (API-01).
 *
 * <h2>Why this is hand-written</h2>
 * <p>The product has no JSON library and adding one for this would be a large, transitive dependency
 * on the deployment surface in exchange for a grammar that fits in one file. More importantly, a
 * general-purpose parser is configured to be safe, and a configuration is something a later edit can
 * quietly drop; a parser that <em>cannot</em> exceed a budget because it counts as it goes has no such
 * setting to lose. The same reasoning already produced {@code SecureGraphMlParser}.</p>
 *
 * <h2>What the grammar admits</h2>
 * <p>Strict RFC 8259 and nothing else: no comments, no trailing commas, no unquoted keys, no NaN or
 * Infinity, no single quotes, no leading plus or leading zeros, no unescaped control characters, and
 * no duplicate object keys. Duplicate keys in particular are a rejection rather than a last-wins
 * merge: two parsers that disagree about which value wins is a classic way for a validating front end
 * and an executing back end to see different documents.</p>
 *
 * <h2>Canonical output</h2>
 * <p>{@link #write(PayloadValue)} sorts map keys and emits no insignificant whitespace, so the same
 * value always encodes to the same bytes. That is what makes an encoded payload comparable, hashable
 * and diffable. Reading is order-preserving and writing is order-normalising, which means a
 * round trip preserves <em>content</em> and not member order — JSON objects are unordered, so
 * promising order would be promising something the format does not carry.</p>
 */
public final class PayloadJson {

    private PayloadJson() {
    }

/**
 * Canonical encoding: sorted map keys, no insignificant whitespace.
 * @param value payload type-model value to encode
 * @return canonical JSON with deterministic map-key ordering
 */
    public static String write(PayloadValue value) {
        var out = new StringBuilder();
        encode(value, out);
        return out.toString();
    }

/**
 * Canonical encoding of a full envelope, contract and schema declaration included.
 * @param envelope contract-bearing payload envelope to encode
 * @return canonical JSON containing the envelope declaration and payload value
 */
    public static String writeEnvelope(PayloadEnvelope envelope) {
        var out = new StringBuilder();
        out.append("{\"contract\":");
        encodeString(envelope.contract(), out);
        out.append(",\"kind\":\"").append(envelope.kind().name()).append('"');
        out.append(",\"schema\":");
        encodeString(envelope.schema(), out);
        out.append(",\"schemaVersion\":");
        encodeString(envelope.schemaVersion(), out);
        out.append(",\"value\":");
        encode(envelope.value(), out);
        out.append('}');
        return out.toString();
    }

    /**
     * Decodes UTF-8 bytes, refusing anything over {@code limits.maxEncodedBytes()} before decoding.
     *
     * <p>The byte check comes first on purpose: it is the only budget that can be enforced without
     * touching the content, so it is the one that must bound the work every other check does.</p>
     *
     * <h4>Why this is the only overload</h4>
     * <p>Previously there was also a {@code read(String, PayloadLimits)} overload. It shared every
     * internal budget with this one — depth, elements per collection, value count, text length, key
     * length — except {@code maxEncodedBytes}, which it silently skipped: a {@code String} has already
     * paid the allocation cost decoding buys, so there was nothing left to check before touching the
     * content, and the check was simply never written. The gap was not theoretical: a document of a
     * thousand 1&nbsp;KiB strings satisfies every one of those per-element budgets individually — 1001
     * values against a 10&nbsp;000 ceiling, a 1000-element collection against a 1000 ceiling, each
     * string against a 32&nbsp;KiB ceiling — while its canonical encoding runs to roughly 1.03&nbsp;MB,
     * nearly four times {@link PayloadLimits#DEFAULTS}'s 256&nbsp;KiB. That document passed the
     * {@code String} overload and was refused by this one, and the choice of overload was the security
     * decision nothing at the call site named.</p>
     *
     * <p><b>The census, because the number is the argument.</b> There were <b>15</b> production call
     * sites of {@code read} across {@code src/main} — 13 inside the {@code ravenroot} reactor and 2 in
     * the out-of-reactor model adapters, which a reactor-only search does not see. Twelve already held
     * bytes. <b>Three</b> passed a {@code String}, and they were not equally exposed:</p>
     * <ul>
     *   <li>{@code AssistantDeviceAuthorization} was the one that was actually unbounded. It read its
     *       OAuth response through {@code HttpResponse.BodyHandlers.ofString()}, which buffers the whole
     *       response before {@code RESPONSE_LIMITS}, or any budget at all, is consulted — so by the time
     *       a {@code String} existed to measure, the allocation the budget exists to prevent had already
     *       happened. The call site now enforces the bound at the source rather than teaching this class to measure a
     *       {@code String}'s encoded length after the fact.</li>
     *   <li>{@code AnthropicModelProvider} and {@code OpenAiCompatibleModelProvider} were <b>already
     *       bounded, and bounded more strongly than this method can be</b>: both read through
     *       {@code BoundedBodyHandlers}, which refuses a declared {@code Content-Length} over the
     *       ceiling before a body byte is read and cancels the subscription mid-stream when the length
     *       is understated or absent. Their {@code String} was a decoded copy of bytes that had already
     *       passed a ceiling. They now use {@code ofByteArray}, which removes a decoding step and
     *       keeps the streaming refusal exactly as it was.</li>
     * </ul>
     * <p>That asymmetry is why removing the overload is a simplification rather than a weakening: it
     * takes away a call-site choice that was load-bearing in exactly one place and redundant in the
     * other two, and leaves one way to read untrusted JSON in this codebase — the bounded one. See
     * {@code PayloadJsonTest} for the measured case above, fixed as a regression test.</p>
     * @param utf8 untrusted encoded JSON bytes
     * @param limits non-null byte and structural budgets enforced while parsing
     * @return decoded value after every parser budget and grammar check succeeds
     */
    public static PayloadValue read(byte[] utf8, PayloadLimits limits) {
        if (utf8.length > limits.maxEncodedBytes()) {
            throw PayloadRejection.tooLarge(utf8.length, limits.maxEncodedBytes());
        }
        return new Reader(new String(utf8, StandardCharsets.UTF_8), limits).readDocument();
    }

    /**
     * Decodes an envelope, checking the declared contract version and the declared kind.
     *
     * <p>An unknown contract version is <b>refused</b> rather than best-effort interpreted. A payload
     * this build cannot claim to understand must not reach a node under a version label that says it
     * was understood. Unknown <em>members</em> inside a known contract are ignored, which is the other
     * half of the rule and the half that lets the envelope grow additively.</p>
     * @param utf8 untrusted encoded envelope bytes
     * @param limits non-null byte and structural budgets enforced while parsing
     * @return decoded envelope whose contract and declared shape match its value
     */
    public static PayloadEnvelope readEnvelope(byte[] utf8, PayloadLimits limits) {
        PayloadValue parsed = read(utf8, limits);
        if (!(parsed instanceof PayloadValue.MapValue envelope)) {
            throw PayloadRejection.malformed(0, null);
        }
        return PayloadEnvelope.fromMembers(envelope.entries(), limits);
    }

    private static void encode(PayloadValue value, StringBuilder out) {
        switch (value) {
            case PayloadValue.NullValue ignored -> out.append("null");
            case PayloadValue.BooleanValue flag -> out.append(flag.value() ? "true" : "false");
            case PayloadValue.IntegerValue number -> out.append(number.value());
            case PayloadValue.DecimalValue number -> out.append(number.value());
            case PayloadValue.TextValue text -> encodeString(text.value(), out);
            case PayloadValue.ListValue list -> {
                out.append('[');
                boolean first = true;
                for (PayloadValue element : list.values()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    encode(element, out);
                }
                out.append(']');
            }
            case PayloadValue.MapValue map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, PayloadValue> entry : new TreeMap<>(map.entries()).entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    encodeString(entry.getKey(), out);
                    out.append(':');
                    encode(entry.getValue(), out);
                }
                out.append('}');
            }
        }
    }

    private static void encodeString(String value, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20 || isUnpairedSurrogate(value, index)) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Whether the char at {@code index} is a surrogate with no partner.
     *
     * <p>{@code readEscape} accepts any four hex digits, so {@code "\ud800"} decodes to a lone
     * surrogate; {@code fromJava} can receive one from an embedded caller just as easily. Emitting it
     * raw produced a string that is not valid UTF-16 and therefore not encodable as UTF-8 — the
     * writer, whose whole job is to be strict, was the one component that could emit a document its
     * own reader would reject.</p>
     *
     * <p>Only <em>unpaired</em> surrogates are escaped. A well-formed pair is left raw so that every
     * document that was already valid still encodes to exactly the bytes it did before: this closes a
     * hole without moving the canonical form of any legitimate value.</p>
     */
    private static boolean isUnpairedSurrogate(String value, int index) {
        char character = value.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        if (Character.isLowSurrogate(character)) {
            return index == 0 || !Character.isHighSurrogate(value.charAt(index - 1));
        }
        return false;
    }

    /** Counts while it descends, so a hostile document is refused before its tree exists. */
    private static final class Reader {
        private final String text;
        private final PayloadLimits limits;
        private int index;
        private int values;

        Reader(String text, PayloadLimits limits) {
            this.text = text;
            this.limits = limits;
        }

        PayloadValue readDocument() {
            skipWhitespace();
            PayloadValue value = readValue(1);
            skipWhitespace();
            if (index != text.length()) {
                throw PayloadRejection.malformed(index, snippet());
            }
            return value;
        }

        private PayloadValue readValue(int depth) {
            if (depth > limits.maxDepth()) {
                throw PayloadRejection.depthExceeded(limits.maxDepth());
            }
            if (++values > limits.maxValueCount()) {
                throw PayloadRejection.valueCountExceeded(limits.maxValueCount());
            }
            char character = peek();
            return switch (character) {
                case '{' -> readObject(depth);
                case '[' -> readArray(depth);
                case '"' -> new PayloadValue.TextValue(readString(limits.maxTextLength()));
                case 't' -> {
                    expect("true");
                    yield PayloadValue.of(true);
                }
                case 'f' -> {
                    expect("false");
                    yield PayloadValue.of(false);
                }
                case 'n' -> {
                    expect("null");
                    yield PayloadValue.NULL;
                }
                default -> readNumber();
            };
        }

        private PayloadValue readObject(int depth) {
            index++;
            var entries = new LinkedHashMap<String, PayloadValue>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return new PayloadValue.MapValue(entries);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw PayloadRejection.malformed(index, snippet());
                }
                String key = readString(limits.maxKeyLength());
                if (key.length() > limits.maxKeyLength()) {
                    throw PayloadRejection.keyTooLong(limits.maxKeyLength());
                }
                skipWhitespace();
                if (peek() != ':') {
                    throw PayloadRejection.malformed(index, snippet());
                }
                index++;
                skipWhitespace();
                PayloadValue value = readValue(depth + 1);
                if (entries.putIfAbsent(key, value) != null) {
                    throw PayloadRejection.duplicateKey(key);
                }
                if (entries.size() > limits.maxCollectionSize()) {
                    throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                }
                skipWhitespace();
                char next = peek();
                index++;
                if (next == '}') {
                    return new PayloadValue.MapValue(entries);
                }
                if (next != ',') {
                    throw PayloadRejection.malformed(index - 1, snippet());
                }
            }
        }

        private PayloadValue readArray(int depth) {
            index++;
            var values = new ArrayList<PayloadValue>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return new PayloadValue.ListValue(values);
            }
            while (true) {
                skipWhitespace();
                values.add(readValue(depth + 1));
                if (values.size() > limits.maxCollectionSize()) {
                    throw PayloadRejection.collectionExceeded(limits.maxCollectionSize());
                }
                skipWhitespace();
                char next = peek();
                index++;
                if (next == ']') {
                    return new PayloadValue.ListValue(values);
                }
                if (next != ',') {
                    throw PayloadRejection.malformed(index - 1, snippet());
                }
            }
        }

        private String readString(int maxLength) {
            index++;
            var out = new StringBuilder();
            while (true) {
                if (index >= text.length()) {
                    throw PayloadRejection.malformed(index, null);
                }
                char character = text.charAt(index++);
                if (character == '"') {
                    return out.toString();
                }
                if (character == '\\') {
                    out.append(readEscape());
                } else if (character < 0x20) {
                    throw PayloadRejection.malformed(index - 1, null);
                } else {
                    out.append(character);
                }
                if (out.length() > maxLength) {
                    throw maxLength == limits.maxKeyLength()
                            ? PayloadRejection.keyTooLong(limits.maxKeyLength())
                            : PayloadRejection.textTooLong(limits.maxTextLength());
                }
            }
        }

        private char readEscape() {
            if (index >= text.length()) {
                throw PayloadRejection.malformed(index, null);
            }
            char marker = text.charAt(index++);
            return switch (marker) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> {
                    if (index + 4 > text.length()) {
                        throw PayloadRejection.malformed(index, null);
                    }
                    String hex = text.substring(index, index + 4);
                    for (int offset = 0; offset < 4; offset++) {
                        if (Character.digit(hex.charAt(offset), 16) < 0) {
                            throw PayloadRejection.malformed(index + offset, null);
                        }
                    }
                    index += 4;
                    yield (char) Integer.parseInt(hex, 16);
                }
                default -> throw PayloadRejection.malformed(index - 1, null);
            };
        }

        private PayloadValue readNumber() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            int digitsStart = index;
            while (index < text.length() && isDigit(text.charAt(index))) {
                index++;
            }
            if (index == digitsStart) {
                throw PayloadRejection.malformed(start, snippet());
            }
            if (text.charAt(digitsStart) == '0' && index - digitsStart > 1) {
                throw PayloadRejection.malformed(digitsStart, snippet());
            }
            boolean integral = true;
            if (index < text.length() && text.charAt(index) == '.') {
                integral = false;
                index++;
                int fractionStart = index;
                while (index < text.length() && isDigit(text.charAt(index))) {
                    index++;
                }
                if (index == fractionStart) {
                    throw PayloadRejection.malformed(index, snippet());
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                integral = false;
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                int exponentStart = index;
                while (index < text.length() && isDigit(text.charAt(index))) {
                    index++;
                }
                if (index == exponentStart) {
                    throw PayloadRejection.malformed(index, snippet());
                }
            }
            String literal = text.substring(start, index);
            if (integral) {
                try {
                    return PayloadValue.of(Long.parseLong(literal));
                } catch (NumberFormatException outOfRange) {
                    // An integral literal outside 64-bit range is refused rather than downgraded to a
                    // double: silently losing precision at the boundary is worse than a clear refusal.
                    throw PayloadRejection.unsupportedType();
                }
            }
            double parsed = Double.parseDouble(literal);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                throw PayloadRejection.unsupportedType();
            }
            return PayloadValue.of(parsed);
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw PayloadRejection.malformed(index, snippet());
            }
            index += literal.length();
        }

        private char peek() {
            if (index >= text.length()) {
                throw PayloadRejection.malformed(index, null);
            }
            return text.charAt(index);
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char character = text.charAt(index);
                if (character == ' ' || character == '\t' || character == '\n' || character == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }

        /** A short window around the failure, for the server-side record only. */
        private String snippet() {
            int end = Math.min(text.length(), index + 16);
            return index >= text.length() ? null : text.substring(index, end);
        }
    }
}
