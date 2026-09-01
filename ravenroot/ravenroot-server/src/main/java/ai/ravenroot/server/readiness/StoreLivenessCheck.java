package ai.ravenroot.server.readiness;

import ai.ravenroot.api.persistence.ExecutionStore;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * An active, cheap, at-probe-time check that the durable store this process depends
 * on can actually be read from right now.
 *
 * <p>Deliberately not a rolling error window over request traffic. A purely reactive signal (count
 * recent failures, degrade after N) only detects degradation <em>after</em> something has already
 * failed against real traffic — the case that matters most for readiness, a store that is
 * unreachable while no traffic happens to be flowing, produces no errors for a reactive window to
 * observe, so it would report ready right up until the first casualty. An active check run at
 * probe time closes exactly that gap. Concurrent callers share one outstanding read until it
 * completes; {@link ReadinessGate}'s own timeout bounds each caller without cancelling the shared
 * store operation, so a hung adapter cannot grow an unbounded work queue.</p>
 *
 * <p>No persistence-port method is added for this. The execution-store contribution uses the existing
 * tenant-scoped {@link ExecutionStore#forgottenBefore(String)} read. It neither mutates the store nor
 * invents a location-specific health API, so a future remote adapter remains behind the same port.</p>
 *
 * <h2>What this does and does not cover</h2>
 * <p>{@code RavenrootServerMain} composes both the always-present audit trail and, unless explicitly
 * disabled, the execution store through {@link #all(StoreLivenessCheck...)}. A failure from either is
 * reduced to {@link ReadinessState#STORE_DEGRADED}; no exception text, path or store detail enters the
 * unauthenticated readiness response.</p>
 */
@FunctionalInterface
public interface StoreLivenessCheck {
    /** @throws Exception if the store cannot be read; any exception means "not healthy" */
    void check() throws Exception;

    /** The check every narrower {@link ReadinessGate} constructor uses when no store is composed:
     * never degrades readiness, because there is nothing wired to check. */
    static StoreLivenessCheck none() {
        return () -> {
        };
    }

    /** A cheap read against the execution-store connection the application actually uses. */
    static StoreLivenessCheck executionStore(ExecutionStore store) {
        Objects.requireNonNull(store, "store");
        return new StoreLivenessCheck() {
            private final Object monitor = new Object();
            private CompletionStage<?> outstanding;

            @Override
            public void check() throws Exception {
                CompletionStage<?> probe;
                synchronized (monitor) {
                    if (outstanding == null) {
                        probe = store.forgottenBefore("__ravenroot_readiness_probe__");
                        outstanding = probe;
                        probe.whenComplete((ignored, failed) -> {
                            synchronized (monitor) {
                                if (outstanding == probe) {
                                    outstanding = null;
                                }
                            }
                        });
                    } else {
                        probe = outstanding;
                    }
                }
                probe.toCompletableFuture().get();
            }
        };
    }

    /** Runs every required durable-dependency check in declaration order, stopping on first failure. */
    static StoreLivenessCheck all(StoreLivenessCheck... checks) {
        var required = Arrays.stream(Objects.requireNonNull(checks, "checks"))
                .map(check -> Objects.requireNonNull(check, "check"))
                .toList();
        return () -> {
            for (StoreLivenessCheck check : required) {
                check.check();
            }
        };
    }
}
