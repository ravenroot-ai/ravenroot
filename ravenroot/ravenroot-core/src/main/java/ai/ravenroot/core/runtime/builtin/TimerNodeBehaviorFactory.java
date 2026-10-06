package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.catalog.*;
import ai.ravenroot.core.graph.GraphNode;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;

/** Core source that emits at selected local times and weekdays. */
final class TimerNodeBehaviorFactory extends ScheduledSourceNodeFactory {
    TimerNodeBehaviorFactory() { }
    TimerNodeBehaviorFactory(Clock clock, ScheduledExecutorService scheduler) { super(clock, scheduler); }

    @Override public NodeTypeDescriptor descriptor() {
        return new NodeTypeDescriptor("timer", "Timer", "Scheduling",
                "Starts a graph occurrence at bounded local times in an explicit IANA timezone.",
                "event", false, List.of(
                NodePropertyDescriptor.boundedText("zoneId", "Timezone", NodePropertyType.STRING, true,
                        "IANA timezone ID, for example Europe/Rome or UTC.", "", 128, 1, 128),
                NodePropertyDescriptor.boundedText("times", "Local times", NodePropertyType.STRING, true,
                        "Comma-separated ISO local times HH:mm or HH:mm:ss; at most 64 unique values.",
                        "", 1024, ScheduleDefinition.Timer.MAX_TIMES, 16),
                NodePropertyDescriptor.boundedText("weekdays", "Weekdays", NodePropertyType.STRING, false,
                        "Comma-separated ISO weekdays; omitted means every day.", "", 128, 7, 12),
                choiceProperty("mode", "Mode", true, "ONCE", List.of("ONCE", "RECURRING"),
                        "ONCE emits the first match after activation; RECURRING continues."),
                choiceProperty("misfirePolicy", "Misfire policy", false, "LATEST_ONLY",
                        List.of("SKIP", "LATEST_ONLY"), "How occurrences missed while unavailable are handled.")),
                Set.of("source", "durable", "calendar"), NodeRuntimeNature.SOURCE,
                Set.of(NodeRuntimeNature.SOURCE)).withOutcomes(NodeOutcomeDescriptor.literal("continue",
                "A scheduled occurrence was admitted and its metadata payload continues through the graph."));
    }

    @Override Configuration configuration(GraphNode node) {
        try {
            var schedule = new ScheduleDefinition.Timer(required(node, "zoneId"), required(node, "times"),
                    optional(node, "weekdays", ""));
            return new Configuration("timer", schedule, choice(node, "mode", "", Mode.class),
                    choice(node, "misfirePolicy", "LATEST_ONLY", Misfire.class));
        } catch (PropertyFailure failure) { throw failure; }
        catch (RuntimeException invalid) {
            String property = invalid.getMessage() != null && invalid.getMessage().contains("zone") ? "zoneId"
                    : invalid.getMessage() != null && invalid.getMessage().contains("weekday") ? "weekdays" : "times";
            throw new PropertyFailure(property);
        }
    }

    static NodePropertyDescriptor choiceProperty(String name, String display, boolean required, String fallback,
                                                 List<String> choices, String description) {
        return new NodePropertyDescriptor(name, display, NodePropertyType.STRING, required, description, fallback,
                choices, false, null, null, "", "", 32, 1, 32);
    }
}
