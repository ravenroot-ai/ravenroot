package ai.ravenroot.api.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeCommandTest {

    @Test
    void reservedSpellingsHaveStableFrameworkMeaning() {
        assertEquals(NodeCommand.PROCESS, NodeCommand.parse("process"));
        assertEquals(NodeCommand.PROCESS, NodeCommand.parse("continue"));
        assertEquals(NodeCommand.PASSTHROUGH, NodeCommand.parse("passthrough"));
    }

    @Test
    void namedCommandsAreNormalizedAndBounded() {
        assertEquals("correggi", NodeCommand.application(" Correggi ").name());
        assertThrows(IllegalArgumentException.class, () -> NodeCommand.application("continue"));
        assertThrows(IllegalArgumentException.class, () -> NodeCommand.application("1invalid"));
        assertThrows(IllegalArgumentException.class,
                () -> NodeCommand.application("a".repeat(NodeCommand.MAX_NAME_LENGTH + 1)));
    }
}
