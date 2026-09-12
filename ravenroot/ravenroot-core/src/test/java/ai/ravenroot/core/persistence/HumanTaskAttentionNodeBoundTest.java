package ai.ravenroot.core.persistence;

import ai.ravenroot.api.persistence.HumanTaskPolicy;
import ai.ravenroot.core.graph.GraphMlLimits;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HumanTaskAttentionNodeBoundTest {
    @Test
    void completeNodeCountsCoverEveryGraphAcceptedBySupportedAdmissionLimits() {
        assertEquals(GraphMlLimits.HARD_MAX_NODES,
                HumanTaskPolicy.Confirmation.HARD_MAX_ATTENTION_NODE_COUNTS,
                "attention must never truncate counts for a graph the parser can admit");
    }
}
