package ai.ravenroot.server;

import static org.junit.jupiter.api.Assertions.*;

import ai.ravenroot.api.activity.ActivityArchiveException;
import ai.ravenroot.api.activity.ActivityPage;
import ai.ravenroot.api.activity.ActivityQuery;
import ai.ravenroot.api.application.ApplicationStatus;
import ai.ravenroot.api.application.RavenrootApplication;
import ai.ravenroot.api.application.RuntimeSnapshot;
import ai.ravenroot.server.security.DisabledLoopbackAuthenticator;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActivityRouteTest {
  @TempDir Path uiDirectory;

  @Test
  void tenantComesFromAuthenticationAndDefaultLimitUsesArchiveMaximum() throws Exception {
    var observed = new AtomicReference<String>();
    try (var server =
        server(
            application(
                (tenant, query) -> {
                  observed.set(tenant + ":" + query.limit());
                  return new ActivityPage(List.of(), query.afterCursor(), 1);
                }))) {
      server.start();
      var ok = get(server, "/v1/activity");
      assertEquals(200, ok.statusCode());
      assertEquals("local:2", observed.get());
      assertTrue(ok.body().contains("\"gap\":false"), ok.body());

      var forged = get(server, "/v1/activity?tenantId=other");
      assertEquals(400, forged.statusCode());
      assertTrue(forged.body().contains("INVALID_REQUEST"), forged.body());
    }
  }

  @Test
  void rejectsMalformedAndOverflowingCursorsAndReturnsExplicitGapRecoveryFloor() throws Exception {
    try (var server =
        server(
            application(
                (tenant, query) -> {
                  throw new ActivityArchiveException(
                      ActivityArchiveException.Reason.CURSOR_EXPIRED, "expired", 9);
                }))) {
      server.start();
      assertEquals(400, get(server, "/v1/activity?after=9223372036854775808").statusCode());
      assertEquals(400, get(server, "/v1/activity?after=-1").statusCode());
      assertEquals(400, get(server, "/v1/activity?limit=3").statusCode());
      var gap = get(server, "/v1/activity?after=1");
      assertEquals(200, gap.statusCode());
      assertTrue(gap.body().contains("\"gap\":true"), gap.body());
      assertTrue(gap.body().contains("\"retainedFromCursor\":9"), gap.body());
      assertTrue(gap.body().contains("\"nextCursor\":8"), gap.body());
    }
  }

  @Test
  void reportsArchiveCapacityOrUnavailabilityAsRetryableServiceFailure() throws Exception {
    try (var server =
        server(
            application(
                (tenant, query) -> {
                  throw new ActivityArchiveException(
                      ActivityArchiveException.Reason.UNAVAILABLE, "capacity exhausted");
                }))) {
      server.start();
      var unavailable = get(server, "/v1/activity");
      assertEquals(503, unavailable.statusCode());
      assertTrue(unavailable.body().contains("REQUEST_INTERRUPTED"), unavailable.body());
    }
  }

  private RavenrootServer server(RavenrootApplication application) {
    return new RavenrootServer(
        application,
        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        uiDirectory,
        new DisabledLoopbackAuthenticator());
  }

  private static HttpResponse<String> get(RavenrootServer server, String path) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  private static RavenrootApplication application(ActivityRead read) {
    InvocationHandler handler =
        (proxy, method, args) -> {
          Object result =
              switch (method.getName()) {
                case "activityArchiveAvailable" -> true;
                case "activityArchiveMaxPageSize" -> 2;
                case "activityAfter" -> read.read((String) args[0], (ActivityQuery) args[1]);
                default -> null;
              };
          if (result != null) return result;
          if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
          return switch (method.getName()) {
            case "status" -> new ApplicationStatus("RUNNING", "test", Set.of());
            case "runtimeSnapshot" -> new RuntimeSnapshot(0, Map.of());
            case "nodeTypes",
                    "programArtifacts",
                    "executionEventsAfter",
                    "durableEventsAfter",
                    "liveExecutions" ->
                List.of();
            case "subscribeToExecutionEvents" -> (AutoCloseable) () -> {};
            case "durableEventJournalAvailable", "executionResultsRetained" -> false;
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
          };
        };
    return (RavenrootApplication)
        Proxy.newProxyInstance(
            ActivityRouteTest.class.getClassLoader(),
            new Class<?>[] {RavenrootApplication.class},
            handler);
  }

  @FunctionalInterface
  private interface ActivityRead {
    ActivityPage read(String tenantId, ActivityQuery query);
  }
}
