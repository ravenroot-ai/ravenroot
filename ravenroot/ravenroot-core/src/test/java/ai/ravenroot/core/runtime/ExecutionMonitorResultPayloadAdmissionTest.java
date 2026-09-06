package ai.ravenroot.core.runtime;

import ai.ravenroot.api.persistence.ResultPayloadState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionMonitorResultPayloadAdmissionTest {

    @Test
    void theChannelCarriesOnlyTheTwoIdentifierFreeRefusalStatesAndNoExecutionEvent() {
        var monitor = new ExecutionMonitor();
        var observed = new ArrayList<ResultPayloadState>();
        monitor.subscribeResultPayloadAdmissions(observed::add);

        monitor.resultPayloadAdmissionRejected(ResultPayloadState.WITHHELD);
        monitor.resultPayloadAdmissionRejected(ResultPayloadState.UNCONVERTIBLE);

        assertEquals(List.of(ResultPayloadState.WITHHELD, ResultPayloadState.UNCONVERTIBLE), observed);
        assertTrue(monitor.eventsAfter(0).isEmpty(),
                "an aggregate payload-admission observation must not enter the identified event history");
    }

    @Test
    void everyNonRefusalStateIsRejectedBeforeObserversRun() {
        var monitor = new ExecutionMonitor();
        var observed = new ArrayList<ResultPayloadState>();
        monitor.subscribeResultPayloadAdmissions(observed::add);

        for (ResultPayloadState state : List.of(ResultPayloadState.NONE, ResultPayloadState.RETAINED,
                ResultPayloadState.EXPIRED)) {
            assertThrows(IllegalArgumentException.class,
                    () -> monitor.resultPayloadAdmissionRejected(state), state.name());
        }
        assertThrows(IllegalArgumentException.class,
                () -> monitor.resultPayloadAdmissionRejected(null), "null");
        assertTrue(observed.isEmpty());
    }

    @Test
    void observerFailureIsIsolatedAndUnsubscribeStopsFutureCallbacks() throws Exception {
        var monitor = new ExecutionMonitor();
        var observed = new ArrayList<ResultPayloadState>();
        monitor.subscribeResultPayloadAdmissions(ignored -> {
            throw new IllegalStateException("observer-owned diagnostic must not escape");
        });
        AutoCloseable subscription = monitor.subscribeResultPayloadAdmissions(observed::add);

        monitor.resultPayloadAdmissionRejected(ResultPayloadState.WITHHELD);
        subscription.close();
        monitor.resultPayloadAdmissionRejected(ResultPayloadState.UNCONVERTIBLE);

        assertEquals(List.of(ResultPayloadState.WITHHELD), observed,
                "one defective observer must not block another, and a closed subscription must stay closed");
    }
}
