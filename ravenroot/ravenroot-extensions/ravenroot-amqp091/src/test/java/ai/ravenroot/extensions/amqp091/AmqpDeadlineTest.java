package ai.ravenroot.extensions.amqp091;

import ai.ravenroot.api.security.SecretValue;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmqpDeadlineTest {
    @Test
    void blockedNewConnectionCannotRetainInvocationPastTheAbsoluteDeadline() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch aborted = new CountDownLatch(1);
        AtomicInteger channels = new AtomicInteger();
        AtomicReference<Connection> lateConnection = new AtomicReference<>();
        ConnectionFactory factory = new ConnectionFactory() {
            @Override
            public Connection newConnection(String connectionName) {
                entered.countDown();
                awaitUninterruptibly(release);
                Connection connection = connection(channels, aborted);
                lateConnection.set(connection);
                return connection;
            }
        };
        var protocol = new RabbitMqAmqpProtocol(() -> factory);
        var invocation = action(protocol).handle(AmqpTestSupport.message(AmqpTestSupport.payload()))
                .toCompletableFuture();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) invocation.get(180, TimeUnit.MILLISECONDS).payload();
            assertEquals("TEMPORARY_FAILURE", output.get("status"));
            assertEquals(1, output.get("attemptCount"));
            assertEquals(0, channels.get(), "a timed-out attempt must never progress to channel creation");
        } finally {
            release.countDown();
        }
        assertTrue(aborted.await(1, TimeUnit.SECONDS), "a connection returned after timeout must be aborted");
        assertTrue(lateConnection.get() != null);
    }

    @Test
    void blockedCreateChannelCannotRetainInvocationOrPublishPastTheAbsoluteDeadline() throws Exception {
        assertBlockedRabbitStage(RabbitStage.CREATE_CHANNEL);
    }

    @Test
    void blockedConfirmSelectCannotRetainInvocationOrPublishPastTheAbsoluteDeadline() throws Exception {
        assertBlockedRabbitStage(RabbitStage.CONFIRM_SELECT);
    }

    @Test
    void sequentialConnectionStagesDepleteOneAdapterDeadline() {
        var ticker = new AmqpTestSupport.MutableTicker();
        AtomicInteger aborts = new AtomicInteger();
        AtomicInteger abortBudget = new AtomicInteger(-1);
        AtomicInteger publishes = new AtomicInteger();
        Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "confirmSelect" -> {
                        ticker.advanceMillis(40);
                        yield null;
                    }
                    case "basicPublish" -> {
                        publishes.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "budget-amqp-channel";
                    default -> primitiveDefault(method.getReturnType());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isOpen" -> true;
                    case "createChannel" -> {
                        ticker.advanceMillis(30);
                        yield channel;
                    }
                    case "abort" -> {
                        abortBudget.set((int) arguments[0]);
                        aborts.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "budget-amqp-connection";
                    default -> primitiveDefault(method.getReturnType());
                });
        ConnectionFactory factory = new ConnectionFactory() {
            @Override
            public Connection newConnection(String connectionName) {
                ticker.advanceMillis(30);
                return connection;
            }
        };
        var protocol = new RabbitMqAmqpProtocol(() -> factory, ticker);
        AmqpProtocol.ConnectAttempt attempt = protocol.beginConnect(
                AmqpTestSupport.profile(), "secret".toCharArray(), 100);

        AmqpProtocol.ConnectFailure failure = assertThrows(AmqpProtocol.ConnectFailure.class, attempt::establish);

        assertEquals(AmqpProtocol.ConnectFailureKind.TEMPORARY, failure.kind());
        assertTrue(awaitCount(aborts, 1, 1_000));
        assertEquals(1, aborts.get());
        assertEquals(0, abortBudget.get(), "cleanup receives no synthetic post-deadline budget");
        assertEquals(0, publishes.get());
    }

    @Test
    void blockedConnectClearsAttemptSecretAndReleasesAdmissionForTheNextInvocation() throws Exception {
        var protocol = new BlockingConnectProtocol();
        var action = action(protocol);
        var first = action.handle(AmqpTestSupport.message(AmqpTestSupport.payload())).toCompletableFuture();
        assertTrue(protocol.connectEntered.await(1, TimeUnit.SECONDS));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) first.get(180, TimeUnit.MILLISECONDS).payload();
            assertEquals("TEMPORARY_FAILURE", output.get("status"));
            assertEquals(1, output.get("attemptCount"));
            assertTrue(cleared(protocol.passwords.getFirst()));
            assertEquals(0, protocol.publishes.get());
            assertTrue(awaitCount(protocol.cancels, 1, 1_000));
        } finally {
            protocol.connectRelease.countDown();
        }
        assertTrue(protocol.connectExited.await(1, TimeUnit.SECONDS));
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) action.handle(AmqpTestSupport.message(
                AmqpTestSupport.payload())).toCompletableFuture().get(300, TimeUnit.MILLISECONDS).payload();
        assertEquals("CONFIRMED", second.get("status"));
        assertEquals(1, protocol.publishes.get(), "the expired attempt must never publish late");
    }

    @Test
    void blockedAbortAddsNoFixedPostDeadlineWaitAndPublishWorkerTerminates() throws Exception {
        var protocol = new BlockingAbortProtocol();
        var invocation = action(protocol).handle(AmqpTestSupport.message(AmqpTestSupport.payload()))
                .toCompletableFuture();
        assertTrue(protocol.publishEntered.await(1, TimeUnit.SECONDS));
        long started = System.nanoTime();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) invocation.get(140, TimeUnit.MILLISECONDS).payload();
            assertEquals("AMBIGUOUS", output.get("status"));
            assertTrue(awaitCount(protocol.aborts, 1, 1_000));
            assertEquals(1, protocol.aborts.get());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 130,
                    "abort must not add a fixed grace after the main deadline");
            assertTrue(protocol.publishExited.await(100, TimeUnit.MILLISECONDS));
        } finally {
            protocol.abortRelease.countDown();
        }
        assertTrue(protocol.abortExited.await(1, TimeUnit.SECONDS), "the released abort worker must terminate");
    }

    @Test
    void blockedSynchronousPublishIsAbortedWithinTheTotalDeadline() throws Exception {
        var protocol = new BlockingProtocol(true, false);
        var action = action(protocol);
        var invocation = action.handle(AmqpTestSupport.message(AmqpTestSupport.payload()))
                .toCompletableFuture();
        assertTrue(protocol.publishEntered.await(1, TimeUnit.SECONDS));
        long started = System.nanoTime();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) invocation.get(300, TimeUnit.MILLISECONDS).payload();
            assertEquals("AMBIGUOUS", output.get("status"));
            assertEquals(1, protocol.publishes.get());
            assertTrue(awaitCount(protocol.aborts, 1, 1_000));
            assertEquals(1, protocol.aborts.get());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 250,
                    "deadline completion must stay below 250ms");
            assertTrue(cleared(protocol.passwords.getFirst()));
        } finally {
            protocol.release.countDown();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) action.handle(AmqpTestSupport.message(
                AmqpTestSupport.payload())).toCompletableFuture().get(300, TimeUnit.MILLISECONDS).payload();
        assertEquals("CONFIRMED", second.get("status"), "deadline cleanup must release admission");
    }

    @Test
    void confirmedDeliverySurvivesBlockedOrderlyCloseAndAdmissionIsReusable() throws Exception {
        var protocol = new BlockingProtocol(false, true);
        var action = action(protocol);
        var invocation = action.handle(AmqpTestSupport.message(AmqpTestSupport.payload())).toCompletableFuture();
        assertTrue(protocol.closeEntered.await(1, TimeUnit.SECONDS));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) invocation.get(300, TimeUnit.MILLISECONDS).payload();
            assertEquals("CONFIRMED", output.get("status"));
            assertTrue(awaitCount(protocol.aborts, 1, 1_000));
            assertEquals(1, protocol.aborts.get());
        } finally {
            protocol.release.countDown();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> second = (Map<String, Object>) action.handle(AmqpTestSupport.message(
                AmqpTestSupport.payload())).toCompletableFuture().get(300, TimeUnit.MILLISECONDS).payload();
        assertEquals("CONFIRMED", second.get("status"), "deadline cleanup must release admission");
    }

    @Test
    void confirmTimeoutCleansUpWithinTheRemainingBudgetAndClearsSecretsBeforeAdmissionReuse() {
        var protocol = new AmqpTestSupport.FakeProtocol(
                AmqpTestSupport.Event.SILENT, AmqpTestSupport.Event.CONFIRM);
        var action = AmqpTestSupport.behavior(protocol).create(
                AmqpTestSupport.configuration(Map.of("confirmTimeoutMs", "100", "maxConcurrency", "1")));
        assertEquals("AMBIGUOUS", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertTrue(awaitCleanup(protocol, 1_000), "timeout cleanup must be scheduled promptly");
        assertEquals(1, protocol.aborts.get() + protocol.closeTimeouts.size(),
                "timeout cleanup must take exactly one bounded close or abort path");
        assertTrue(protocol.closeTimeouts.stream().allMatch(timeout -> timeout <= 2),
                "orderly close may use only the sub-millisecond rounded remainder");
        assertTrue(cleared(protocol.passwordReferences.getFirst()));
        assertEquals("CONFIRMED", AmqpTestSupport.output(action, AmqpTestSupport.payload()).get("status"));
        assertEquals(2, protocol.publishes.get());
    }

    @Test
    void eachStageReceivesOnlyTheMonotonicRemainingBudget() {
        var ticker = new AmqpTestSupport.MutableTicker();
        var protocol = new BudgetProtocol(ticker);
        var controls = new AmqpRuntimeControls(ticker, Runnable::run, 1, 1, 16);
        var behavior = new AmqpPublishNodeBehavior(
                reference -> Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray())),
                (tenant, name) -> Optional.of(AmqpTestSupport.profile(tenant, name, 1, 100, 100, 0)),
                protocol, controls, ticker, millis -> ticker.advanceMillis(millis));
        assertEquals("CONFIRMED", AmqpTestSupport.output(behavior.create(AmqpTestSupport.configuration(
                Map.of("confirmTimeoutMs", "100", "maxConcurrency", "1"))),
                AmqpTestSupport.payload()).get("status"));
        assertEquals(List.of(100), protocol.connectBudgets);
        assertEquals(List.of(80), protocol.publishBudgets);
        assertEquals(List.of(60), protocol.closeBudgets);
        assertEquals(0, protocol.aborts.get());
    }

    @Test
    void rabbitAdapterAbortsAPartialConnectionInsteadOfOrderlyClosingIt() {
        AtomicInteger aborts = new AtomicInteger();
        Connection partial = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isOpen" -> true;
                    case "createChannel" -> null;
                    case "abort" -> { aborts.incrementAndGet(); yield null; }
                    case "toString" -> "partial-amqp-connection";
                    default -> primitiveDefault(method.getReturnType());
                });
        ConnectionFactory factory = new ConnectionFactory() {
            @Override
            public Connection newConnection(String connectionName) {
                return partial;
            }
        };
        var protocol = new RabbitMqAmqpProtocol(() -> factory);
        AmqpProtocol.ConnectAttempt attempt = protocol.beginConnect(
                AmqpTestSupport.profile(), "secret".toCharArray(), 100);
        AmqpProtocol.ConnectFailure failure = assertThrows(AmqpProtocol.ConnectFailure.class, attempt::establish);
        assertEquals(AmqpProtocol.ConnectFailureKind.TEMPORARY, failure.kind());
        assertTrue(awaitCount(aborts, 1, 1_000));
    }

    private static ai.ravenroot.api.node.NodeAction action(AmqpProtocol protocol) {
        return action(protocol, 100);
    }

    private static ai.ravenroot.api.node.NodeAction action(AmqpProtocol protocol, int timeoutMs) {
        var controls = new AmqpRuntimeControls(System::nanoTime,
                command -> Thread.ofVirtual().start(command), 1, 1, 16);
        var behavior = new AmqpPublishNodeBehavior(
                reference -> Optional.of(new SecretValue(AmqpTestSupport.SECRET.toCharArray())),
                (tenant, name) -> Optional.of(AmqpTestSupport.profile(
                        tenant, name, 1, 100, timeoutMs, 0)),
                protocol, controls, System::nanoTime, Thread::sleep);
        return behavior.create(AmqpTestSupport.configuration(Map.of(
                "confirmTimeoutMs", Integer.toString(timeoutMs), "maxConcurrency", "1")));
    }

    private static final class BlockingAbortProtocol implements AmqpProtocol {
        private final CountDownLatch publishEntered = new CountDownLatch(1);
        private final CountDownLatch publishExited = new CountDownLatch(1);
        private final CountDownLatch abortRelease = new CountDownLatch(1);
        private final CountDownLatch abortExited = new CountDownLatch(1);
        private final AtomicInteger aborts = new AtomicInteger();

        @Override
        public ConnectAttempt beginConnect(AmqpProfile profile, char[] password, int timeoutMs) {
            return immediate(new Session() {
                @Override
                public void publish(Publication publication, Observer observer, int timeoutMs) throws Exception {
                    publishEntered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } finally {
                        publishExited.countDown();
                    }
                }

                @Override
                public void close(int timeoutMs) { }

                @Override
                public void abort(int timeoutMs) {
                    aborts.incrementAndGet();
                    try {
                        awaitUninterruptibly(abortRelease);
                    } finally {
                        abortExited.countDown();
                    }
                }
            });
        }
    }

    private static final class BlockingConnectProtocol implements AmqpProtocol {
        private final CountDownLatch connectEntered = new CountDownLatch(1);
        private final CountDownLatch connectRelease = new CountDownLatch(1);
        private final CountDownLatch connectExited = new CountDownLatch(1);
        private final AtomicInteger begins = new AtomicInteger();
        private final AtomicInteger cancels = new AtomicInteger();
        private final AtomicInteger publishes = new AtomicInteger();
        private final List<char[]> passwords = new CopyOnWriteArrayList<>();

        @Override
        public ConnectAttempt beginConnect(AmqpProfile profile, char[] password, int timeoutMs) {
            passwords.add(password);
            if (begins.incrementAndGet() > 1) return immediate(confirmingSession(publishes));
            return new ConnectAttempt() {
                @Override
                public void establish() {
                    connectEntered.countDown();
                    try {
                        awaitUninterruptibly(connectRelease);
                    } finally {
                        connectExited.countDown();
                    }
                }

                @Override
                public Session claim() {
                    throw new AssertionError("an expired connection attempt must not be claimed");
                }

                @Override
                public void cancel() {
                    cancels.incrementAndGet();
                }
            };
        }
    }

    private static final class BlockingProtocol implements AmqpProtocol {
        private final boolean blockPublish;
        private final boolean blockCloseOnce;
        private final CountDownLatch publishEntered = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger publishes = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger aborts = new AtomicInteger();
        private final List<char[]> passwords = new CopyOnWriteArrayList<>();

        private BlockingProtocol(boolean blockPublish, boolean blockCloseOnce) {
            this.blockPublish = blockPublish;
            this.blockCloseOnce = blockCloseOnce;
        }

        @Override
        public ConnectAttempt beginConnect(AmqpProfile profile, char[] password, int timeoutMs) {
            passwords.add(password);
            return immediate(new Session() {
                @Override
                public void publish(Publication publication, Observer observer, int timeoutMs) throws Exception {
                    publishes.incrementAndGet();
                    publishEntered.countDown();
                    if (blockPublish) release.await();
                    observer.confirmed();
                }

                @Override
                public void close(int timeoutMs) throws Exception {
                    int close = closes.incrementAndGet();
                    closeEntered.countDown();
                    if (blockCloseOnce && close == 1) release.await();
                }

                @Override
                public void abort(int timeoutMs) {
                    aborts.incrementAndGet();
                    release.countDown();
                }
            });
        }
    }

    private static final class BudgetProtocol implements AmqpProtocol {
        private final AmqpTestSupport.MutableTicker ticker;
        private final List<Integer> connectBudgets = new CopyOnWriteArrayList<>();
        private final List<Integer> publishBudgets = new CopyOnWriteArrayList<>();
        private final List<Integer> closeBudgets = new CopyOnWriteArrayList<>();
        private final AtomicInteger aborts = new AtomicInteger();

        private BudgetProtocol(AmqpTestSupport.MutableTicker ticker) {
            this.ticker = ticker;
        }

        @Override
        public ConnectAttempt beginConnect(AmqpProfile profile, char[] password, int timeoutMs) {
            connectBudgets.add(timeoutMs);
            ticker.advanceMillis(20);
            return immediate(new Session() {
                @Override
                public void publish(Publication publication, Observer observer, int timeoutMs) {
                    publishBudgets.add(timeoutMs);
                    ticker.advanceMillis(20);
                    observer.confirmed();
                }

                @Override
                public void close(int timeoutMs) {
                    closeBudgets.add(timeoutMs);
                    ticker.advanceMillis(20);
                }

                @Override
                public void abort(int timeoutMs) {
                    aborts.incrementAndGet();
                }
            });
        }
    }

    private static AmqpProtocol.ConnectAttempt immediate(AmqpProtocol.Session session) {
        return new AmqpProtocol.ConnectAttempt() {
            @Override public void establish() { }
            @Override public AmqpProtocol.Session claim() { return session; }
            @Override public void cancel() { }
        };
    }

    private static AmqpProtocol.Session confirmingSession(AtomicInteger publishes) {
        return new AmqpProtocol.Session() {
            @Override
            public void publish(AmqpProtocol.Publication publication, AmqpProtocol.Observer observer, int timeoutMs) {
                publishes.incrementAndGet();
                observer.confirmed();
            }

            @Override public void close(int timeoutMs) { }
            @Override public void abort(int timeoutMs) { }
        };
    }

    private static void assertBlockedRabbitStage(RabbitStage blockedStage) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger aborts = new AtomicInteger();
        AtomicInteger publishes = new AtomicInteger();
        Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "confirmSelect" -> {
                        if (blockedStage == RabbitStage.CONFIRM_SELECT)
                            blockStage(entered, release, exited);
                        yield null;
                    }
                    case "basicPublish" -> {
                        publishes.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "blocked-amqp-channel";
                    default -> primitiveDefault(method.getReturnType());
                });
        Connection connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isOpen" -> true;
                    case "createChannel" -> {
                        if (blockedStage == RabbitStage.CREATE_CHANNEL)
                            blockStage(entered, release, exited);
                        yield channel;
                    }
                    case "abort" -> {
                        aborts.incrementAndGet();
                        yield null;
                    }
                    case "toString" -> "blocked-amqp-connection";
                    default -> primitiveDefault(method.getReturnType());
                });
        ConnectionFactory factory = new ConnectionFactory() {
            @Override
            public Connection newConnection(String connectionName) {
                return connection;
            }
        };
        int timeoutMs = 250;
        var invocation = action(new RabbitMqAmqpProtocol(() -> factory), timeoutMs)
                .handle(AmqpTestSupport.message(AmqpTestSupport.payload())).toCompletableFuture();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) invocation.get(400, TimeUnit.MILLISECONDS).payload();
            assertEquals("TEMPORARY_FAILURE", output.get("status"));
            assertEquals(1, output.get("attemptCount"));
            assertTrue(awaitCount(aborts, 1, 1_000));
            assertEquals(1, aborts.get());
            assertEquals(0, publishes.get());
        } finally {
            release.countDown();
        }
        assertTrue(exited.await(1, TimeUnit.SECONDS), "the released connection-stage worker must terminate");
        assertEquals(1, aborts.get(), "the connection has one cleanup owner");
        assertEquals(0, publishes.get(), "an expired connection attempt must never publish late");
    }

    private static void blockStage(CountDownLatch entered, CountDownLatch release, CountDownLatch exited) {
        entered.countDown();
        try {
            awaitUninterruptibly(release);
        } finally {
            exited.countDown();
        }
    }

    private enum RabbitStage { CREATE_CHANNEL, CONFIRM_SELECT }

    private static boolean cleared(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static Connection connection(AtomicInteger channels, CountDownLatch aborted) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "isOpen" -> true;
                    case "createChannel" -> { channels.incrementAndGet(); yield null; }
                    case "abort" -> { aborted.countDown(); yield null; }
                    case "toString" -> "late-amqp-connection";
                    default -> primitiveDefault(method.getReturnType());
                });
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static boolean awaitCount(AtomicInteger counter, int expected, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (counter.get() < expected && System.nanoTime() < deadline) Thread.onSpinWait();
        return counter.get() >= expected;
    }

    private static boolean awaitCleanup(AmqpTestSupport.FakeProtocol protocol, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (protocol.aborts.get() + protocol.closeTimeouts.size() == 0 && System.nanoTime() < deadline)
            Thread.onSpinWait();
        return protocol.aborts.get() + protocol.closeTimeouts.size() > 0;
    }
}
