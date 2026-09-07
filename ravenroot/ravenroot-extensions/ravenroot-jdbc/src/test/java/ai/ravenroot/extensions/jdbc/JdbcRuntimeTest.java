package ai.ravenroot.extensions.jdbc;

import ai.ravenroot.api.node.service.OutboundCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcRuntimeTest {
    @Test
    void successfulTerminalReleasesAdmissionBeforeDependentContinuationRuns() {
        JdbcRuntime runtime = new JdbcRuntime(System::nanoTime, 1,
                JdbcRuntime.CLEANUP_LANES_PER_INVOCATION);
        AtomicInteger invocations = new AtomicInteger();

        var result = runtime.submit("tenant-a\0profile-a", 1, Duration.ofSeconds(10), ignored -> {
                    invocations.incrementAndGet();
                    return "first";
                })
                .thenCompose(first -> runtime.submit("tenant-a\0profile-a", 1, Duration.ofSeconds(10), ignored -> {
                    invocations.incrementAndGet();
                    return first + "-second";
                }));

        assertEquals("first-second", result.join());
        assertEquals(2, invocations.get());
    }

    @Test
    void blockedCleanupCallbacksConsumeOneFixedReservationUntilTheyActuallySettle() throws Exception {
        JdbcRuntime runtime = new JdbcRuntime(System::nanoTime, 2,
                JdbcRuntime.CLEANUP_LANES_PER_INVOCATION);
        CountDownLatch workerEntered = new CountDownLatch(1);
        CountDownLatch callbacksEntered = new CountDownLatch(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION);
        CountDownLatch callbackRelease = new CountDownLatch(1);
        CountDownLatch callbacksInternallySettled = new CountDownLatch(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION);
        CountDownLatch callbackReturn = new CountDownLatch(1);
        ConcurrentLinkedQueue<Thread> callbackThreads = new ConcurrentLinkedQueue<>();
        AtomicInteger activeCallbacks = new AtomicInteger();
        AtomicInteger callbackStarts = new AtomicInteger();

        var first = runtime.submit("tenant-a\0profile-a", 1, Duration.ofSeconds(10), control -> {
            control.credential(blockingCall(callbacksEntered, callbackRelease, callbacksInternallySettled,
                    callbackReturn, callbackThreads, activeCallbacks, callbackStarts));
            control.statement(blockingStatement(callbacksEntered, callbackRelease, callbacksInternallySettled,
                    callbackReturn, callbackThreads, activeCallbacks, callbackStarts));
            control.connection(blockingConnection(callbacksEntered, callbackRelease, callbacksInternallySettled,
                    callbackReturn, callbackThreads, activeCallbacks, callbackStarts));
            workerEntered.countDown();
            new CountDownLatch(1).await();
            return "late";
        });
        try {
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS));

            assertTrue(first.cancel(true));
            assertEquals(JdbcFailure.Code.CANCELLED,
                    JdbcTestSupport.failure(assertThrows(CompletionException.class, first::join)).code());
            assertTrue(callbacksEntered.await(1, TimeUnit.SECONDS));
            assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, callbackStarts.get());
            assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, activeCallbacks.get());

            assertAdmissionRefused(runtime);
            assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, callbackStarts.get(),
                    "blocked cleanup must not accumulate new callback threads beyond the fixed bound");

            callbackRelease.countDown();
            assertTrue(callbacksInternallySettled.await(1, TimeUnit.SECONDS));
            assertEquals(0, activeCallbacks.get());
            assertAdmissionRefused(runtime);
            assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, callbackStarts.get(),
                    "internally settled callbacks must still hold exactly the original cleanup reservations");

            callbackReturn.countDown();
            assertTrue(joinCallbacks(callbackThreads, Duration.ofMillis(500)),
                    "cleanup callback threads must terminate after returning their runtime reservations");
            assertEquals(JdbcRuntime.CLEANUP_LANES_PER_INVOCATION, callbackThreads.size());
            assertEquals("accepted", runtime.submit("tenant-b\0profile-b", 1, Duration.ofSeconds(10),
                    ignored -> "accepted").join());
            assertEquals(JdbcFailure.Code.CANCELLED,
                    JdbcTestSupport.failure(assertThrows(CompletionException.class, first::join)).code(),
                    "late callback settlement must not replace the published terminal");
        } finally {
            first.cancel(true);
            callbackRelease.countDown();
            callbackReturn.countDown();
            callbacksEntered.await(1, TimeUnit.SECONDS);
            joinCallbacks(callbackThreads, Duration.ofMillis(500));
        }
    }

    private static void assertAdmissionRefused(JdbcRuntime runtime) {
        var refused = runtime.submit("tenant-b\0profile-b", 1, Duration.ofSeconds(10), ignored -> "must-not-run");
        assertEquals(JdbcFailure.Code.ADMISSION_REFUSED,
                JdbcTestSupport.failure(assertThrows(CompletionException.class, refused::join)).code());
    }

    private static OutboundCall<Object> blockingCall(CountDownLatch entered, CountDownLatch release,
                                                       CountDownLatch internallySettled, CountDownLatch callbackReturn,
                                                       ConcurrentLinkedQueue<Thread> threads,
                                                       AtomicInteger active, AtomicInteger starts) {
        return new OutboundCall<>() {
            @Override public java.util.concurrent.CompletionStage<Object> completion() {
                return new CompletableFuture<>();
            }

            @Override public boolean cancel() {
                block(entered, release, internallySettled, callbackReturn, threads, active, starts);
                return true;
            }
        };
    }

    private static Statement blockingStatement(CountDownLatch entered, CountDownLatch release,
                                                CountDownLatch internallySettled, CountDownLatch callbackReturn,
                                                ConcurrentLinkedQueue<Thread> threads,
                                                AtomicInteger active, AtomicInteger starts) {
        return (Statement) Proxy.newProxyInstance(JdbcRuntimeTest.class.getClassLoader(),
                new Class<?>[]{Statement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("cancel")) {
                        block(entered, release, internallySettled, callbackReturn, threads, active, starts);
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                });
    }

    private static Connection blockingConnection(CountDownLatch entered, CountDownLatch release,
                                                  CountDownLatch internallySettled, CountDownLatch callbackReturn,
                                                  ConcurrentLinkedQueue<Thread> threads,
                                                  AtomicInteger active, AtomicInteger starts) {
        return (Connection) Proxy.newProxyInstance(JdbcRuntimeTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("abort") || method.getName().equals("close")) {
                        block(entered, release, internallySettled, callbackReturn, threads, active, starts);
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                });
    }

    private static void block(CountDownLatch entered, CountDownLatch release,
                              CountDownLatch internallySettled, CountDownLatch callbackReturn,
                              ConcurrentLinkedQueue<Thread> threads,
                              AtomicInteger active, AtomicInteger starts) {
        starts.incrementAndGet();
        active.incrementAndGet();
        threads.add(Thread.currentThread());
        entered.countDown();
        awaitUninterruptibly(release);
        active.decrementAndGet();
        internallySettled.countDown();
        awaitUninterruptibly(callbackReturn);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean waiting = true;
        while (waiting) try {
            latch.await();
            waiting = false;
        } catch (InterruptedException ignored) { }
    }

    private static boolean joinCallbacks(ConcurrentLinkedQueue<Thread> threads, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            int nanos = (int) (remaining - TimeUnit.MILLISECONDS.toNanos(millis));
            thread.join(millis, nanos);
            if (thread.isAlive()) return false;
        }
        return true;
    }
}
