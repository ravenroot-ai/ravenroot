package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.*;
import ai.ravenroot.core.graph.GraphNode;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

/** Strict data-only five-field crontab source; it never accepts or executes a command tail. */
final class CrontabNodeBehaviorFactory extends ScheduledSourceNodeFactory {
    CrontabNodeBehaviorFactory() { }
    CrontabNodeBehaviorFactory(Clock clock, ScheduledExecutorService scheduler) { super(clock, scheduler); }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("crontab", "Crontab", "Scheduling",
                "Starts graph occurrences from strict five-field numeric cron expressions; no shell is executed.",
                "event", false, List.of(
                NodePropertyDescriptor.boundedText("zoneId", "Timezone", NodePropertyType.STRING, true,
                        "IANA timezone ID, for example Europe/Rome or UTC.", "", 128, 1, 128),
                NodePropertyDescriptor.boundedText("entries", "Cron entries", NodePropertyType.TEXT, true,
                        "Up to 128 five-field numeric entries. Blank lines and full-line # comments are allowed.",
                        "", 16_384, 0, 0),
                TimerNodeBehaviorFactory.choiceProperty("misfirePolicy", "Misfire policy", false, "LATEST_ONLY",
                        List.of("SKIP", "LATEST_ONLY"), "How occurrences missed while unavailable are handled.")),
                Set.of("source", "durable", "calendar"), NodeRuntimeNature.SOURCE,
                Set.of(NodeRuntimeNature.SOURCE)).withOutcomes(NodeOutcomeDescriptor.literal("continue",
                "A scheduled occurrence was admitted and its metadata payload continues through the graph."));
    }

    @Override Configuration configuration(GraphNode node) {
        try {
            var schedule = new ScheduleDefinition.Crontab(required(node, "zoneId"), required(node, "entries"));
            schedule.nextAfter(currentInstant());
            return new Configuration("crontab", schedule, Mode.RECURRING,
                    choice(node, "misfirePolicy", "LATEST_ONLY", Misfire.class));
        } catch (PropertyFailure failure) { throw failure; }
        catch (RuntimeException invalid) {
            String property = invalid.getMessage() != null && invalid.getMessage().contains("zone")
                    ? "zoneId" : "entries";
            throw new PropertyFailure(property);
        }
    }
}
