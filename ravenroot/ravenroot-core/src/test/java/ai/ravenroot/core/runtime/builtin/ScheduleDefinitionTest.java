package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.graph.NodeKind;
import ai.ravenroot.core.runtime.BehaviorValidationException;
import ai.ravenroot.core.runtime.BehaviorRegistry;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleDefinitionTest {
    @Test void coreSourceCapabilityCannotBeClaimedByApplicationRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> new BehaviorRegistry().registerFactory(new TimerNodeBehaviorFactory()));
    }

    @Test void timerSkipsDstGapAndEmitsBothOverlapInstants() {
        var timer = new ScheduleDefinition.Timer("Europe/Rome", "02:30", "SUNDAY");

        var afterGap = timer.nextAfter(Instant.parse("2026-03-28T23:00:00Z"));
        assertEquals(LocalDate.of(2026, 4, 5), afterGap.local().toLocalDate(),
                "02:30 does not exist on the spring transition day");

        var first = timer.nextAfter(Instant.parse("2026-10-24T22:00:00Z"));
        var second = timer.nextAfter(first.instant());
        assertEquals(LocalDateTime.parse("2026-10-25T02:30:00"), first.local());
        assertEquals(first.local(), second.local());
        assertNotEquals(first.offset(), second.offset());
        assertEquals(Duration.ofHours(1), Duration.between(first.instant(), second.instant()));
    }

    @Test void cronSupportsRangesListsStepsAndVixieDayOrSemantics() {
        var cron = new ScheduleDefinition.Crontab("UTC", "# report\n*/15 9-10 1 * 1\n30 12 * * *");

        assertEquals(Instant.parse("2026-06-01T09:00:00Z"),
                cron.nextAfter(Instant.parse("2026-06-01T08:59:59Z")).instant());
        // The 1st matches even though it is not Monday: restricted DOM and DOW use OR.
        assertEquals(Instant.parse("2026-07-01T09:00:00Z"),
                cron.nextAfter(Instant.parse("2026-07-01T08:59:59Z")).instant());
        assertEquals(Instant.parse("2026-07-01T12:30:00Z"),
                cron.previousAtOrBefore(Instant.parse("2026-07-01T12:31:00Z")).instant());
    }

    @Test void cronSingleStartStepContinuesToFieldMaximum() {
        var cron = new ScheduleDefinition.Crontab("UTC", "5/20 9 * * *");
        assertEquals(Instant.parse("2026-06-01T09:25:00Z"),
                cron.nextAfter(Instant.parse("2026-06-01T09:05:00Z")).instant());
    }

    @Test void concurrentCronEntriesAtOneInstantCoalesce() {
        var cron = new ScheduleDefinition.Crontab("UTC", "0 9 * * *\n0 9 * * *");
        var first = cron.nextAfter(Instant.parse("2026-06-01T08:59:00Z"));
        assertEquals(Instant.parse("2026-06-01T09:00:00Z"), first.instant());
        assertEquals(Instant.parse("2026-06-02T09:00:00Z"), cron.nextAfter(first.instant()).instant());
    }

    @Test void crontabRejectsCommandsMacrosNamesInlineCommentsAndBadFields() {
        var factory = new CrontabNodeBehaviorFactory();
        for (String entries : new String[]{
                "0 9 * * * /bin/echo no", "@daily", "0 9 * JAN *", "0 9 * * * # no", "60 9 * * *",
                "0 9 31 2 *"}) {
            var node = new GraphNode("cron", NodeKind.BEHAVIOR, "crontab",
                    Map.of("zoneId", "UTC", "entries", entries));
            BehaviorValidationException refusal = assertThrows(BehaviorValidationException.class,
                    () -> factory.validate(node));
            assertEquals("entries", refusal.propertyName());
        }
    }

    @Test void timerValidationAddressesTheInvalidProperty() {
        var factory = new TimerNodeBehaviorFactory();
        var badZone = new GraphNode("timer", NodeKind.BEHAVIOR, "timer",
                Map.of("zoneId", "Mars/Olympus", "times", "09:00", "mode", "RECURRING"));
        assertEquals("zoneId", assertThrows(BehaviorValidationException.class,
                () -> factory.validate(badZone)).propertyName());

        var offsetInsteadOfZone = new GraphNode("timer", NodeKind.BEHAVIOR, "timer",
                Map.of("zoneId", "+01:00", "times", "09:00", "mode", "RECURRING"));
        assertEquals("zoneId", assertThrows(BehaviorValidationException.class,
                () -> factory.validate(offsetInsteadOfZone)).propertyName());

        var badDay = new GraphNode("timer", NodeKind.BEHAVIOR, "timer",
                Map.of("zoneId", "UTC", "times", "09:00", "weekdays", "FUNDAY", "mode", "RECURRING"));
        assertEquals("weekdays", assertThrows(BehaviorValidationException.class,
                () -> factory.validate(badDay)).propertyName());
    }
}
