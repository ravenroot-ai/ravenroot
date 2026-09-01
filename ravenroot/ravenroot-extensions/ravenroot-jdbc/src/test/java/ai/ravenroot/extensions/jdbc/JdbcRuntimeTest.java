package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.node.service.OutboundCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRuntimeTest {
    @Test
    void blockedCleanupCallbacksConsumeOneFixedReservationUntilTheyActuallySettle() throws Exception {
        JdbcRuntime runtime = new JdbcRuntime(System::nanoTime, 2,
                JdbcRuntime.CLEANUP_LANES_PER_INVOCATION);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch callbacksEntered = new CountDownLatch(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION);
        CountDownLatch callbackRelease = new CountDownLatch(1);
        AtomicInteger activeCallbacks = new AtomicInteger();
        AtomicInteger callbackStarts = new AtomicInteger();

        var first = runtime.submit("tenant-a\0profile-a", 1, Duration.ofSeconds(10), control -> {
            control.credential(blockingCall(callbacksEntered, callbackRelease, activeCallbacks, callbackStarts));
            control.statement(blockingStatement(callbacksEntered, callbackRelease, activeCallbacks, callbackStarts));
            control.connection(blockingConnection(callbacksEntered, callbackRelease, activeCallbacks, callbackStarts));
            workerEntered.countDown();
            new CountDownLatch(1).await();
            return "late";
        });
        assertTrue(workerEntered.await(1, TimeUnit.SECONDS));

        assertTrue(first.cancel(true));
        assertEquals(JdbcFailure.Code.CANCELLED,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, first::join)).code());
        assertTrue(callbacksEntered.await(1, TimeUnit.SECONDS));
        assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, callbackStarts.get());
        assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, activeCallbacks.get());

        var refused = runtime.submit("tenant-b\0profile-b", 1, Duration.ofSeconds(10), ignored -> "must-not-run");
        assertEquals(JdbcFailure.Code.ADMISSION_REFUSED,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, refused::join)).code());
        assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, callbackStarts.get(),
                "blocked cleanup must not accumulate new callback threads beyond the fixed bound");

        callbackRelease.countDown();
        for (int attempt = 0; attempt < 100 && activeCallbacks.get() != 0; attempt++) Thread.sleep(5);
        assertEquals(0, activeCallbacks.get());
        assertEquals("accepted", runtime.submit("tenant-b\0profile-b", 1, Duration.ofSeconds(10),
                ignored -> "accepted").join());
        assertEquals(JdbcFailure.Code.CANCELLED,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, first::join)).code(),
                "late callback settlement must not replace the published terminal");
    }

    private static OutboundCall<Object> blockingCall(CountDownLatch entered, CountDownLatch release,
                                                       AtomicInteger active, AtomicInteger starts) {
        return new OutboundCall<>() {
            @Override public java.util.concurrent.CompletionStage<Object> completion() {
                return new CompletableFuture<>();
            }

            @Override public boolean cancel() {
                block(entered, release, active, starts);
                return true;
            }
        };
    }

    private static Statement blockingStatement(CountDownLatch entered, CountDownLatch release,
                                                AtomicInteger active, AtomicInteger starts) {
        return (Statement) Proxy.newProxyInstance(JdbcRuntimeTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) block(entered, release, active, starts);
                    return FakeJdbc.defaultValue(method.getReturnType());
                });
    }

    private static Connection blockingConnection(CountDownLatch entered, CountDownLatch release,
                                                  AtomicInteger active, AtomicInteger starts) {
        return (Connection) Proxy.newProxyInstance(JdbcRuntimeTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("abort") || method.getName().equals("close")) {
                        block(entered, release, active, starts);
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                });
    }

    private static void block(CountDownLatch entered, CountDownLatch release,
                              AtomicInteger active, AtomicInteger starts) {
        starts.incrementAndGet();
        active.incrementAndGet();
        entered.countDown();
        boolean waiting = true;
        while (waiting) try {
            release.await();
            waiting = false;
        } catch (InterruptedException ignored) { }
        active.decrementAndGet();
    }
}
