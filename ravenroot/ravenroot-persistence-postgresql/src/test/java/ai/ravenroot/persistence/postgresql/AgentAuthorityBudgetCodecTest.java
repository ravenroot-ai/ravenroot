package ai.ravenroot.persistence.postgresql;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentAuthorityBudgetCodecTest {
    @Test
    void writerRefusesACollectionTheReaderCouldNeverMaterialize() throws Exception {
        try (var output = new DataOutputStream(new ByteArrayOutputStream())) {
            assertThrows(IllegalArgumentException.class,
                    () -> AgentAuthorityBudgetCodec.writeCount(output, 100_001));
        }
    }
}
