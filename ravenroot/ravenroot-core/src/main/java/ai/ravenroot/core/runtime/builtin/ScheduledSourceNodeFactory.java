package ai.ravenroot.core.runtime.builtin;

import ai.ravenroot.api.application.GraphAdmissionReason;
import ai.ravenroot.api.catalog.NodeTypeDescriptor;
import ai.ravenroot.api.deployment.*;
import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.persistence.JournalCursor;
import ai.ravenroot.core.graph.GraphNode;
import ai.ravenroot.core.runtime.BehaviorValidationException;
import ai.ravenroot.core.runtime.CoreInboundSourceFactory;
import ai.ravenroot.core.runtime.NodeHandler;

import java.time.*;
import java.time.zone.ZoneRulesProvider;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Common durable lifecycle for the two calendar source descriptors. */
abstract class ScheduledSourceNodeFactory implements CoreInboundSourceFactory {
    static final int MAX_MISSED_COUNT = 64;
    static final Duration MAX_CLOCK_RECHECK = Duration.ofSeconds(60);
    static final Duration REFUSAL_RETRY = Duration.ofSeconds(5);
    static final Duration FAILURE_RETRY = Duration.ofSeconds(15);
    /** Scheduler jitter does not turn an otherwise timely tick into a missed occurrence. */
    static final Duration MISFIRE_GRACE = Duration.ofSeconds(30);
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "ravenroot-core-schedules");
        thread.setDaemon(true);
        return thread;
    });

    enum Mode { ONCE, RECURRING }
    enum Misfire { SKIP, LATEST_ONLY }

    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    ScheduledSourceNodeFactory() { this(Clock.systemUTC(), SCHEDULER); }
    ScheduledSourceNodeFactory(Clock clock, ScheduledExecutorService scheduler) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    final Instant currentInstant() { return clock.instant(); }

    abstract Configuration configuration(GraphNode node);

    @Override public final void validate(GraphNode node) {
        try { configuration(node); }
        catch (PropertyFailure failure) {
            throw new BehaviorValidationException(failure.property, GraphAdmissionReason.INVALID_PROPERTY);
        } catch (RuntimeException failure) {
            throw new BehaviorValidationException("entries", GraphAdmissionReason.INVALID_PROPERTY);
        }
    }

    @Override public final NodeHandler create(GraphNode node) {
        configuration(node);
        return message -> CompletableFuture.completedFuture(
                new NodeResult("continue", message.payload(), message.attributes()));
    }

    @Override public final InboundSource createSource(GraphNode node, InboundSourceContext context) {
        return new ScheduledSource(configuration(node), clock, scheduler);
    }

    record Configuration(String kind, ScheduleDefinition schedule, Mode mode, Misfire misfire) {
        Configuration {
            Objects.requireNonNull(kind); Objects.requireNonNull(schedule);
            Objects.requireNonNull(mode); Objects.requireNonNull(misfire);
        }
    }

    static String required(GraphNode node, String property) {
        Object raw = node.properties().get(property);
        if (raw == null || raw.toString().isBlank()) throw new PropertyFailure(property);
        return raw.toString().strip();
    }

    static String optional(GraphNode node, String property, String fallback) {
        Object raw = node.properties().get(property);
        return raw == null || raw.toString().isBlank() ? fallback : raw.toString().strip();
    }

    static <E extends Enum<E>> E choice(GraphNode node, String property, String fallback, Class<E> type) {
        try { return Enum.valueOf(type, optional(node, property, fallback)); }
        catch (RuntimeException invalid) { throw new PropertyFailure(property); }
    }

    static final class PropertyFailure extends IllegalArgumentException {
        final String property;
        PropertyFailure(String property) { this.property = property; }
    }

    private static final class ScheduledSource implements InboundSource {
        private static final String BASELINE = "baseline";
        private static final String OCCURRENCES = "occurrences";
        private final Configuration configuration;
        private final Clock clock;
        private final ScheduledExecutorService scheduler;
        private final AtomicBoolean active = new AtomicBoolean();
        private volatile InboundSourceContext context;
        private volatile DurableConsumerIngress ingress;
        private volatile ScheduledFuture<?> wakeup;

        ScheduledSource(Configuration configuration, Clock clock, ScheduledExecutorService scheduler) {
            this.configuration = configuration; this.clock = clock; this.scheduler = scheduler;
        }

        @Override public synchronized CompletionStage<Void> start(InboundSourceContext started) {
            if (active.get()) return CompletableFuture.completedFuture(null);
            context = Objects.requireNonNull(started, "context");
            String consumer = "schedule-" + ScheduleDefinition.digest(started.deploymentId().value())
                    + "-" + configuration.schedule().fingerprint();
            DurableConsumerIngress opened = started.ingress().openDurableConsumer(consumer);
            try {
                JournalCursor baseline = await(opened.sourceCheckpoint(started.identity(), BASELINE));
                if (baseline.deliveredThrough() == 0) {
                    await(opened.advanceSourceCheckpoint(baseline, encode(clock.instant())));
                }
                ingress = opened;
                active.set(true);
                schedule(Duration.ZERO);
                return CompletableFuture.completedFuture(null);
            } catch (RuntimeException failure) {
                opened.close();
                context = null;
                throw failure;
            }
        }

        @Override public synchronized CompletionStage<Void> stop() {
            active.set(false);
            ScheduledFuture<?> pending = wakeup;
            wakeup = null;
            if (pending != null) pending.cancel(false);
            DurableConsumerIngress opened = ingress;
            ingress = null;
            context = null;
            if (opened != null) opened.close();
            return CompletableFuture.completedFuture(null);
        }

        private synchronized void schedule(Duration delay) {
            if (!active.get()) return;
            long millis = Math.max(0, Math.min(MAX_CLOCK_RECHECK.toMillis(), delay.toMillis()));
            wakeup = scheduler.schedule(() -> Thread.startVirtualThread(this::cycle), millis, TimeUnit.MILLISECONDS);
        }

        private void cycle() {
            if (!active.get()) return;
            try {
                InboundSourceContext owner = context;
                DurableConsumerIngress durable = ingress;
                if (owner == null || durable == null) return;
                JournalCursor baselineCursor = await(durable.sourceCheckpoint(owner.identity(), BASELINE));
                JournalCursor cursor = await(durable.sourceCheckpoint(owner.identity(), OCCURRENCES));
                if (configuration.mode() == Mode.ONCE && cursor.deliveredThrough() != 0) return;

                Instant after = cursor.deliveredThrough() == 0
                        ? decode(baselineCursor.deliveredThrough()) : decode(cursor.deliveredThrough());
                ScheduleDefinition.Occurrence first = configuration.schedule().nextAfter(after);
                Instant now = clock.instant();
                if (first.instant().isAfter(now)) {
                    owner.reportHealthy();
                    schedule(Duration.between(now, first.instant()));
                    return;
                }
                ScheduleDefinition.Occurrence selected = configuration.mode() == Mode.ONCE
                        ? first : configuration.schedule().previousAtOrBefore(now);
                if (selected == null || !selected.instant().isAfter(after)) {
                    schedule(MAX_CLOCK_RECHECK); return;
                }
                int missed = missedCount(after, selected.instant());
                boolean misfire = missed > 1 || selected.instant().plus(MISFIRE_GRACE).isBefore(now);
                if (configuration.misfire() == Misfire.SKIP && misfire) {
                    await(durable.advanceSourceCheckpoint(cursor, encode(selected.instant())));
                    owner.reportHealthy();
                    schedule(Duration.ZERO);
                    return;
                }

                Instant actual = clock.instant();
                String occurrenceId = ScheduleDefinition.digest(owner.deploymentId().value() + '\0'
                        + owner.nodeId() + '\0' + configuration.schedule().fingerprint() + '\0'
                        + selected.instant() + '\0' + selected.offset());
                Map<String, Object> payload = Map.ofEntries(
                        Map.entry("kind", configuration.kind()),
                        Map.entry("scheduledAt", selected.instant().toString()),
                        Map.entry("actualAt", actual.toString()),
                        Map.entry("scheduledLocal", selected.local().toString()),
                        Map.entry("zoneId", configuration.schedule().zone().getId()),
                        Map.entry("offset", selected.offset().toString()),
                        Map.entry("occurrenceId", occurrenceId),
                        Map.entry("misfire", misfire),
                        Map.entry("missedCount", missed),
                        Map.entry("schedule", selected.selector()),
                        Map.entry("tzdbVersion", tzdbVersion(configuration.schedule().zone())));
                IngressReceipt receipt = durable.offerDurably(owner.identity(), IngressTarget.start(), payload,
                        OCCURRENCES, occurrenceId);
                if (receipt.acknowledgeable()) {
                    await(durable.advanceSourceCheckpoint(cursor, encode(selected.instant())));
                    owner.reportHealthy();
                    schedule(Duration.ZERO);
                } else {
                    if (receipt instanceof IngressReceipt.Ambiguous) {
                        owner.reportDegraded("scheduled occurrence acceptance is unresolved");
                    }
                    schedule(receipt instanceof IngressReceipt.Refused ? REFUSAL_RETRY : FAILURE_RETRY);
                }
            } catch (RuntimeException failure) {
                InboundSourceContext owner = context;
                if (owner != null) owner.reportDegraded("scheduled source persistence or calculation failed");
                schedule(FAILURE_RETRY);
            }
        }

        private int missedCount(Instant after, Instant through) {
            int count = 0;
            ScheduleDefinition.Occurrence candidate = configuration.schedule().nextAfter(after);
            while (!candidate.instant().isAfter(through) && count < MAX_MISSED_COUNT) {
                count++;
                candidate = configuration.schedule().nextAfter(candidate.instant());
            }
            return count;
        }

        private static long encode(Instant instant) {
            return Math.addExact(instant.getEpochSecond(), 1L);
        }
        private static Instant decode(long position) {
            if (position == 0) throw new IllegalStateException("schedule baseline is absent");
            return Instant.ofEpochSecond(position - 1L);
        }
        private static <T> T await(CompletionStage<T> stage) {
            return stage.toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
        private static String tzdbVersion(ZoneId zone) {
            try {
                var versions = ZoneRulesProvider.getVersions(zone.getId());
                return versions.isEmpty() ? "unknown" : versions.lastKey();
            } catch (RuntimeException unavailable) { return "unknown"; }
        }
    }
}
