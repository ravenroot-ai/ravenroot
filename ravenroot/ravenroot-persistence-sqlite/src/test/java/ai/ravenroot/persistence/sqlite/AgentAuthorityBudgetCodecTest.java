package ai.ravenroot.persistence.sqlite;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentAuthorityBudgetCodecTest {
    @Test
    void truncatedTextIsRejectedEvenWhenAvailableBytesFormAValidToken() throws Exception {
        var encoded = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(encoded)) {
            output.writeInt(4);
            output.writeBytes("USD");
        }

        try (var input = new DataInputStream(new ByteArrayInputStream(encoded.toByteArray()))) {
            assertThrows(EOFException.class, () -> AgentAuthorityBudgetCodec.text(input));
        }
    }

    @Test
    void writerRefusesACollectionTheReaderCouldNeverMaterialize() throws Exception {
        try (var output = new DataOutputStream(new ByteArrayOutputStream())) {
            assertThrows(IllegalArgumentException.class,
                    () -> AgentAuthorityBudgetCodec.writeCount(output, 100_001));
        }
    }
}
