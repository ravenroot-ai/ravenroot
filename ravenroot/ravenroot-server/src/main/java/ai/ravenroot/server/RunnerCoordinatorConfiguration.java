package ai.ravenroot.server;

import java.util.Map;

/** Typed startup authority for the independently scalable runner HTTP service. */
record RunnerCoordinatorConfiguration(int port, int httpThreads, int httpQueue) {
    static final RunnerCoordinatorConfiguration DEFAULTS = new RunnerCoordinatorConfiguration(8080, 16, 64);
    RunnerCoordinatorConfiguration {
        if (port < 1 || port > 65535 || httpThreads < 1 || httpQueue < 1)
            throw new IllegalArgumentException("coordinator port and HTTP capacities must be positive; port is an unsigned 16-bit endpoint");
    }
    static RunnerCoordinatorConfiguration fromEnvironment(Map<String, String> environment) {
        return new RunnerCoordinatorConfiguration(value(environment, "RAVENROOT_PORT", DEFAULTS.port()),
                value(environment, "RAVENROOT_RUNNER_COORDINATOR_HTTP_THREADS", DEFAULTS.httpThreads()),
                value(environment, "RAVENROOT_RUNNER_COORDINATOR_HTTP_QUEUE", DEFAULTS.httpQueue()));
    }
    private static int value(Map<String, String> environment, String name, int fallback) {
        return Integer.parseInt(environment.getOrDefault(name, Integer.toString(fallback)));
    }
}
