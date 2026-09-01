package ai.ravenroot.extensions.amqp091;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** AMQP 0-9-1 wire-size checks that count encoded octets, not UTF-16 code units. */
final class AmqpWireLimits {
    static final int SHORTSTR_MAX_BYTES = 255;

    private AmqpWireLimits() { }

    static boolean isShortstr(String value) {
        if (value == null) return false;
        var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return encoder.encode(CharBuffer.wrap(value)).remaining() <= SHORTSTR_MAX_BYTES;
        } catch (CharacterCodingException malformed) {
            return false;
        }
    }
}
