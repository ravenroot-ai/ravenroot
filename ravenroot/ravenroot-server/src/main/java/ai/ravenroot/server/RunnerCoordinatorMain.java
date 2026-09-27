package ai.ravenroot.server;

import ai.ravenroot.api.security.DefaultAuthorizationService;
import ai.ravenroot.server.persistence.*;
import ai.ravenroot.server.security.AuthenticationConfiguration;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Independently supervised, shared-store-only runner coordinator executable. */
public final class RunnerCoordinatorMain {
    private RunnerCoordinatorMain() { }
    static void validateTopology(Map<String, String> environment, ExecutionStoreConfiguration configuration) {
        if (!(configuration instanceof ExecutionStoreConfiguration.Shared))
            throw new IllegalArgumentException("runner coordinator requires the PostgreSQL shared execution authority");
        if (!"oidc".equals(environment.get("RAVENROOT_AUTH_MODE")))
            throw new IllegalArgumentException("runner coordinator requires OIDC workload and operator identities");
        if (!"true".equals(environment.get("RAVENROOT_RUNNER_SHARED_ARTIFACTS")))
            throw new IllegalArgumentException("runner coordinator requires one shared POSIX-locking artifact volume also mounted by the graph authority");
        if (environment.containsKey(ExecutionStoreConfiguration.DIRECTORY_VARIABLE))
            throw new IllegalArgumentException("runner coordinator cannot select a local execution directory");
    }
    public static void main(String[] arguments) throws Exception {
        var environment = System.getenv();
        var configuration = ExecutionStoreConfiguration.fromEnvironment(environment);
        validateTopology(environment, configuration);
        var plane = RunnerPlaneConfiguration.fromEnvironment(environment);
        if (plane == null) throw new IllegalArgumentException("RAVENROOT_RUNNER_CONFIG is required");
        var http = RunnerCoordinatorConfiguration.fromEnvironment(environment);
        var authentication = AuthenticationConfiguration.fromEnvironment(environment, http.port());
        Clock clock = Clock.systemUTC();
        try (var stores = ExecutionStoreBootstrap.openOwned(configuration, clock)) {
            var service = plane.service(stores.store(), clock);
            try (var telemetry = ai.ravenroot.observability.otel.TelemetrySupport.install(
                        ai.ravenroot.observability.otel.TelemetryConfiguration.fromEnvironment(environment),
                        new ai.ravenroot.core.runtime.ExecutionMonitor(), null, service.telemetry()).orElse(() -> { });
                 var server = new RunnerCoordinator(authentication.bindAddress(), authentication.authenticator(),
                         new DefaultAuthorizationService(event -> System.err.printf(
                                 "runner_authorization action=%s allowed=%s request=%s%n", event.action(), event.allowed(), event.requestId())),
                         service, plane.issuer(), plane.artifacts(), clock, http.httpThreads(), http.httpQueue())) {
                server.start();
                var stopped = new CountDownLatch(1);
                Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown, "runner-coordinator-shutdown"));
                stopped.await();
            }
        }
    }
}
