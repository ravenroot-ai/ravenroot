package ai.ravenroot.extensions.telegram;

import ai.ravenroot.api.execution.NodeResult;
import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.NodeConfiguration;
import ai.ravenroot.api.security.CredentialResolver;
import ai.ravenroot.api.security.SecretValue;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TelegramAdmissionConcurrencyTest {
    private static final Executor VIRTUAL = task -> Thread.startVirtualThread(task);
    private static final URI UNUSED_ORIGIN = URI.create("https://localhost:9");

    @Test void globalGateRejectsNPlusOneBeforeCredentialOrNetworkAndRecovers() throws Exception {
        var controls = controls(2, 16, new AtomicLong());
        var blocker = new BlockingCredentials(2);
        NodeAction action = action(controls, blocker, (tenant, name) -> profile(tenant, name, 16, 30), "profile", null);
        CompletionStage<NodeResult> first = action.handle(message("global-a"));
        CompletionStage<NodeResult> second = action.handle(message("global-b"));
        blocker.awaitEntered();
        assertCapacity(action.handle(message("global-c")));
        assertEquals(2, blocker.calls.get());
        blocker.release();
        assertCredential(first); assertCredential(second);
        assertCredential(action.handle(message("global-c")));
        assertEquals(3, blocker.calls.get());
    }

    @Test void tenantGateRejectsNPlusOneAcrossIndependentProfilesAndActionsThenRecovers() throws Exception {
        var controls = controls(10, 2, new AtomicLong());
        var blocker = new BlockingCredentials(2);
        TelegramProfileResolver profiles = (tenant, name) -> profile(tenant, name, 16, 30);
        NodeAction firstAction = action(controls, blocker, profiles, "profile-a", null);
        NodeAction secondAction = action(controls, blocker, profiles, "profile-b", null);
        NodeAction thirdAction = action(controls, blocker, profiles, "profile-c", null);
        CompletionStage<NodeResult> first = firstAction.handle(message("tenant"));
        CompletionStage<NodeResult> second = secondAction.handle(message("tenant"));
        blocker.awaitEntered();
        assertCapacity(thirdAction.handle(message("tenant")));
        assertEquals(2, blocker.calls.get());
        blocker.release();
        assertCredential(first); assertCredential(second);
        assertCredential(thirdAction.handle(message("tenant")));
    }

    @Test void profileGateRejectsNPlusOneAcrossIndependentActionsThenRecovers() throws Exception {
        var controls = controls(10, 10, new AtomicLong());
        var blocker = new BlockingCredentials(2);
        TelegramProfileResolver profiles = (tenant, name) -> profile(tenant, name, 2, 30);
        NodeAction firstAction = action(controls, blocker, profiles, "profile", null);
        NodeAction secondAction = action(controls, blocker, profiles, "profile", null);
        NodeAction thirdAction = action(controls, blocker, profiles, "profile", null);
        CompletionStage<NodeResult> first = firstAction.handle(message("tenant"));
        CompletionStage<NodeResult> second = secondAction.handle(message("tenant"));
        blocker.awaitEntered();
        assertCapacity(thirdAction.handle(message("tenant")));
        assertEquals(2, blocker.calls.get());
        blocker.release();
        assertCredential(first); assertCredential(second);
        assertCredential(thirdAction.handle(message("tenant")));
    }

    @Test void nodeActionGateRejectsNPlusOneThenRecovers() throws Exception {
        var controls = controls(10, 10, new AtomicLong());
        var blocker = new BlockingCredentials(2);
        NodeAction action = action(controls, blocker, (tenant, name) -> profile(tenant, name, 16, 30),
                "profile", 2);
        CompletionStage<NodeResult> first = action.handle(message("tenant"));
        CompletionStage<NodeResult> second = action.handle(message("tenant"));
        blocker.awaitEntered();
        assertCapacity(action.handle(message("tenant")));
        assertEquals(2, blocker.calls.get());
        blocker.release();
        assertCredential(first); assertCredential(second);
        assertCredential(action.handle(message("tenant")));
    }

    @Test void validationRateCredentialSubmissionTransportAndSuccessPathsReleasePermits() throws Exception {
        AtomicLong ticker = new AtomicLong();
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":1,\"date\":1}}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":2,\"date\":2}}");
            AtomicInteger lookups = new AtomicInteger();
            CredentialResolver credentials = ignored -> {
                lookups.incrementAndGet();
                return Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray()));
            };
            var controls = controls(1, 1, ticker);
            NodeAction action = httpsAction(controls, credentials, (tenant, name) -> profile(tenant, name, 1, 1),
                    telegram, "profile", 1);

            assertInvalid(action.handle(TelegramTestSupport.message("release", Map.of(
                    "version", "telegram.send.v1", "chatId", "not-a-chat", "text", "invalid"))));
            assertEquals("SENT", status(action.handle(message("release"))));
            assertEquals("RATE_LIMITED", status(action.handle(message("release"))));
            assertEquals(1, lookups.get(), "rate rejection must precede credential lookup");
            assertEquals(1, telegram.requests().size(), "rate rejection must precede network I/O");
            ticker.set(TelegramRateLimiter.WINDOW_NANOS);
            assertEquals("SENT", status(action.handle(message("release"))), "rate rejection released every permit");
        }

        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":3,\"date\":3}}");
            AtomicBoolean available = new AtomicBoolean();
            CredentialResolver credentials = ignored -> available.get()
                    ? Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray())) : Optional.empty();
            NodeAction action = httpsAction(controls(1, 1, new AtomicLong()), credentials,
                    (tenant, name) -> profile(tenant, name, 1, 30), telegram, "profile", 1);
            assertCredential(action.handle(message("credential-release")));
            available.set(true);
            assertEquals("SENT", status(action.handle(message("credential-release"))));
        }

        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":4,\"date\":4}}");
            RejectOnceExecutor executor = new RejectOnceExecutor();
            var controls = new TelegramSendNodeBehavior.RuntimeControls(System::nanoTime, executor, 1, 1, 32);
            NodeAction action = httpsAction(controls, validCredentials(),
                    (tenant, name) -> profile(tenant, name, 1, 30), telegram, "profile", 1);
            assertCapacity(action.handle(message("submission-release")));
            assertEquals("SENT", status(action.handle(message("submission-release"))));
        }

        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "application/json", "not-json");
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":5,\"date\":5}}");
            NodeAction action = httpsAction(controls(1, 1, new AtomicLong()), validCredentials(),
                    (tenant, name) -> profile(tenant, name, 1, 30), telegram, "profile", 1);
            assertEquals("AMBIGUOUS", status(action.handle(message("transport-release"))));
            assertEquals("SENT", status(action.handle(message("transport-release"))));
        }
    }

    @Test void cancellationKeepsThePermitUntilWorkStopsThenReleasesIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CredentialResolver credentials = ignored -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Optional.empty(); }
            return Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray()));
        };
        try (var telegram = new FakeTelegramHttps()) {
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":6,\"date\":6}}");
            telegram.enqueue(200, "{\"ok\":true,\"result\":{\"message_id\":7,\"date\":7}}");
            NodeAction action = httpsAction(controls(1, 1, new AtomicLong()), credentials,
                    (tenant, name) -> profile(tenant, name, 1, 30), telegram, "profile", 1);
            CompletableFuture<NodeResult> cancelled = action.handle(message("cancel-release")).toCompletableFuture();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(cancelled.cancel(true));
            assertCapacity(action.handle(message("cancel-release")), "cancellation must not release active work early");
            release.countDown();

            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (telegram.requests().size() < 1 && System.nanoTime() - deadline < 0) Thread.onSpinWait();
            assertEquals(1, telegram.requests().size());
            CompletionStage<NodeResult> recovery;
            while (true) {
                recovery = action.handle(message("cancel-release"));
                try { assertEquals("SENT", status(recovery)); break; }
                catch (CompletionException capacityUntilFinally) {
                    if (!(capacityUntilFinally.getCause() instanceof TelegramSendException telegramFailure)
                            || telegramFailure.code() != TelegramSendException.Code.CAPACITY_UNAVAILABLE
                            || System.nanoTime() - deadline >= 0)
                        throw capacityUntilFinally;
                    Thread.onSpinWait();
                }
            }
            assertEquals(2, telegram.requests().size());
        }
    }

    private static TelegramSendNodeBehavior.RuntimeControls controls(int global, int tenant, AtomicLong ticker) {
        return new TelegramSendNodeBehavior.RuntimeControls(ticker::get, VIRTUAL, global, tenant, 64);
    }
    private static NodeAction action(TelegramSendNodeBehavior.RuntimeControls controls, CredentialResolver credentials,
                                     TelegramProfileResolver profiles, String profile, Integer actionLimit) {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put("botProfile", profile);
        if (actionLimit != null) properties.put("maxConcurrency", actionLimit.toString());
        return new TelegramSendNodeBehavior(credentials, profiles, HttpClient.newHttpClient(), UNUSED_ORIGIN, controls)
                .create(new NodeConfiguration("telegram", "telegram.send", properties));
    }
    private static NodeAction httpsAction(TelegramSendNodeBehavior.RuntimeControls controls, CredentialResolver credentials,
                                          TelegramProfileResolver profiles, FakeTelegramHttps telegram,
                                          String profile, int actionLimit) throws Exception {
        return new TelegramSendNodeBehavior(credentials, profiles, telegram.client(), telegram.origin(), controls)
                .create(new NodeConfiguration("telegram", "telegram.send",
                        Map.of("botProfile", profile, "maxConcurrency", Integer.toString(actionLimit))));
    }
    private static Optional<TelegramProfile> profile(String tenant, String name, int concurrency, int rate) {
        return Optional.of(new TelegramProfile(tenant, name, "credential", Set.of("*"), Set.of("sendMessage"),
                Set.of(), false, concurrency, rate, 100, 1_000, 4_096, 1_024, 0, 0));
    }
    private static CredentialResolver validCredentials() {
        return ignored -> Optional.of(new SecretValue(TelegramTestSupport.TOKEN.toCharArray()));
    }
    private static ai.ravenroot.api.execution.NodeMessage message(String tenant) {
        return TelegramTestSupport.message(tenant, TelegramTestSupport.textPayload("bounded"));
    }
    private static String status(CompletionStage<NodeResult> stage) {
        return ((Map<?, ?>) stage.toCompletableFuture().join().payload()).get("status").toString();
    }
    private static void assertCapacity(CompletionStage<NodeResult> stage) { assertCapacity(stage, null); }
    private static void assertCapacity(CompletionStage<NodeResult> stage, String message) {
        CompletionException failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join(), message);
        assertEquals(TelegramSendException.Code.CAPACITY_UNAVAILABLE, ((TelegramSendException) failure.getCause()).code());
    }
    private static void assertCredential(CompletionStage<NodeResult> stage) {
        CompletionException failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertEquals(TelegramSendException.Code.CREDENTIAL_UNAVAILABLE, ((TelegramSendException) failure.getCause()).code());
    }
    private static void assertInvalid(CompletionStage<NodeResult> stage) {
        CompletionException failure = assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertEquals(TelegramSendException.Code.INVALID_INPUT, ((TelegramSendException) failure.getCause()).code());
    }

    private static final class BlockingCredentials implements CredentialResolver {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private BlockingCredentials(int expected) { entered = new CountDownLatch(expected); }
        @Override public Optional<SecretValue> resolve(String reference) {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return Optional.empty();
        }
        void awaitEntered() throws InterruptedException { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
        void release() { release.countDown(); }
    }

    private static final class RejectOnceExecutor implements Executor {
        private final AtomicBoolean reject = new AtomicBoolean(true);
        @Override public void execute(Runnable command) {
            if (reject.compareAndSet(true, false)) throw new java.util.concurrent.RejectedExecutionException("expected");
            Thread.startVirtualThread(command);
        }
    }
}
