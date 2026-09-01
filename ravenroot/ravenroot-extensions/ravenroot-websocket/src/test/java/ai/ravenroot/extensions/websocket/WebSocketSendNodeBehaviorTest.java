package ai.ravenroot.extensions.websocket;

import ai.ravenroot.api.node.NodeAction;
import ai.ravenroot.api.node.service.NodePackageServiceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.binary;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.failure;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.message;
import static ai.ravenroot.extensions.websocket.WebSocketTestSupport.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketSendNodeBehaviorTest {
    @Test void strictTextAndBase64AcceptExactAndRejectLimitPlusOne() {
        WebSocketProfile profile = WebSocketTestSupport.profile(2, 2);
        var resolver = WebSocketTestSupport.resolver(profile);
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = new WebSocketSendNodeBehavior(resolver)
                .create(WebSocketTestSupport.configuration(), transport);

        var exact = action.handle(message("tenant-a", text("1234567890123456")));
        transport.awaitOpen(0).fail(NodePackageServiceException.Reason.TRANSPORT_FAILED);
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(exact).code());
        assertEquals(WebSocketException.Code.INVALID_INPUT,
                failure(action.handle(message("tenant-a", text("12345678901234567")))).code());

        String encoded = Base64.getEncoder().encodeToString(new byte[16]);
        var binaryExact = action.handle(message("tenant-a", binary(encoded)));
        transport.awaitOpen(1).fail(NodePackageServiceException.Reason.TRANSPORT_FAILED);
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(binaryExact).code());
        assertEquals(WebSocketException.Code.INVALID_INPUT,
                failure(action.handle(message("tenant-a", binary(Base64.getEncoder()
                        .encodeToString(new byte[17]))))).code());
        assertEquals(WebSocketException.Code.INVALID_INPUT,
                failure(action.handle(message("tenant-a", binary("YQ")))).code());
    }

    @Test void malformedUtf16AndMultibyteUtf8AreRejectedWithoutOpening() {
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 1);
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile))
                .create(WebSocketTestSupport.configuration(), transport);

        assertEquals(WebSocketException.Code.INVALID_INPUT,
                failure(action.handle(message("tenant-a", text("\ud800")))).code());
        assertEquals(WebSocketException.Code.INVALID_INPUT,
                failure(action.handle(message("tenant-a", text("€€€€€€")))).code());
        assertEquals(0, transport.opens.size());
    }

    @Test void openFailureIsTypedBeforeAnyFrameHandoff() {
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, new WebSocketAdmissionRegistry(), 1);
        var result = action.handle(message("tenant-a", text("hello")));

        transport.awaitOpen(0).fail(NodePackageServiceException.Reason.DEADLINE_EXCEEDED);

        assertEquals(WebSocketException.Code.DEADLINE_EXCEEDED, failure(result).code());
        assertEquals(0, transport.opens.getFirst().session.textCalls.get());
    }

    @Test void onlyFailureAfterFrameHandoffIsAmbiguous() {
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, new WebSocketAdmissionRegistry(), 1);
        var result = action.handle(message("tenant-a", text("hello")));
        var opened = transport.awaitOpen(0);
        opened.succeed();
        opened.session.send.completeExceptionally(
                new NodePackageServiceException(NodePackageServiceException.Reason.TRANSPORT_FAILED));

        assertEquals(WebSocketException.Code.AMBIGUOUS, failure(result).code());
        assertEquals(1, opened.session.textCalls.get());
        assertEquals(1, opened.session.cancelCalls.get());
    }

    @Test void sharedTenantProfilePermitIsHeldUntilTransportTerminal() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var firstTransport = new WebSocketTestSupport.FakeTransport();
        var secondTransport = new WebSocketTestSupport.FakeTransport();
        NodeAction first = action(firstTransport, admission, 1);
        NodeAction second = action(secondTransport, admission, 1);

        var accepted = first.handle(message("tenant-a", text("one")));
        var open = firstTransport.awaitOpen(0);
        open.succeed();
        open.session.send.complete(null);
        assertTrue(accepted.toCompletableFuture().isDone());
        assertEquals(WebSocketException.Code.CAPACITY_UNAVAILABLE,
                failure(second.handle(message("tenant-a", text("two")))).code());

        open.session.terminal();
        var replacement = second.handle(message("tenant-a", text("two")));
        secondTransport.awaitOpen(0).fail(NodePackageServiceException.Reason.TRANSPORT_FAILED);
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(replacement).code());
        assertEquals(0, admission.size());
    }

    @Test void sameProfileIsIndependentAcrossTrustedTenants() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);

        var one = action.handle(message("tenant-a", text("one")));
        var two = action.handle(message("tenant-b", text("two")));

        assertEquals(2, transport.opens.size());
        transport.opens.forEach(open -> open.fail(NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(one).code());
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(two).code());
        assertEquals(0, admission.size());
    }

    @Test void cancelBeforeOpenIsNonAmbiguousAndPermitWaitsForOpenSettlement() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);
        CompletableFuture<?> result = action.handle(message("tenant-a", text("one"))).toCompletableFuture();
        WebSocketTestSupport.OpenAttempt open = transport.awaitOpen(0);

        assertTrue(result.cancel(true));
        assertEquals(WebSocketException.Code.DEADLINE_EXCEEDED, failure(result).code());
        assertTrue(open.cancelled.get());
        assertEquals(0, admission.size());
    }

    @Test void cancelAfterHandoffIsAmbiguousAndNoLateCompletionChangesIt() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);
        CompletableFuture<?> result = action.handle(message("tenant-a", text("one"))).toCompletableFuture();
        WebSocketTestSupport.OpenAttempt open = transport.awaitOpen(0);
        open.succeed();

        assertTrue(result.cancel(true));
        assertEquals(WebSocketException.Code.AMBIGUOUS, failure(result).code());
        open.session.send.complete(null);
        assertEquals(WebSocketException.Code.AMBIGUOUS, failure(result).code());
        assertEquals(0, admission.size());
    }

    @Test void cancellationFirstLinearizesBeforeHandoffAndLateOpenCannotSendOrDoubleRelease()
            throws Exception {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);
        CompletableFuture<?> result = action.handle(message("tenant-a", text("one"))).toCompletableFuture();
        WebSocketTestSupport.OpenAttempt open = transport.awaitOpen(0);
        open.cancelSettles = false;

        assertTrue(result.cancel(true));
        assertEquals(WebSocketException.Code.DEADLINE_EXCEEDED, failure(result).code());
        assertEquals(0, open.session.textCalls.get());
        assertEquals(1, admission.size(), "the permit stays until the managed call settles");

        open.succeed();
        assertEquals(0, open.session.textCalls.get());
        assertEquals(1, open.session.cancelCalls.get());
        assertEquals(0, admission.size());
        open.listener.onClosed(1000, "late-duplicate");
        assertEquals(0, admission.size());
    }

    @Test void handoffFirstBlocksCancellationUntilSendWasActuallyInvoked() throws Exception {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);
        CompletableFuture<?> result = action.handle(message("tenant-a", text("one"))).toCompletableFuture();
        WebSocketTestSupport.OpenAttempt open = transport.awaitOpen(0);
        open.session.blockSend();
        Thread opener = Thread.ofVirtual().start(open::succeed);
        assertTrue(open.session.sendEntered.await(1, TimeUnit.SECONDS));
        AtomicBoolean cancellation = new AtomicBoolean();
        Thread canceller = Thread.ofVirtual().start(() -> cancellation.set(result.cancel(true)));

        assertFalse(result.isDone(), "cancel cannot pass the frame-handoff critical section");
        open.session.sendRelease.countDown();
        opener.join(Duration.ofSeconds(1));
        canceller.join(Duration.ofSeconds(1));

        assertTrue(cancellation.get());
        assertEquals(WebSocketException.Code.AMBIGUOUS, failure(result).code());
        assertEquals(1, open.session.textCalls.get());
        assertEquals(1, open.session.cancelCalls.get());
        assertEquals(0, admission.size());
    }

    @Test void synchronousSendFailureIsAmbiguousAndSettlesExactlyOnce() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);
        var result = action.handle(message("tenant-a", text("one")));
        WebSocketTestSupport.OpenAttempt open = transport.awaitOpen(0);
        open.session.synchronousSendFailure = new IllegalStateException("peer-token-must-not-escape");

        open.succeed();

        assertEquals(WebSocketException.Code.AMBIGUOUS, failure(result).code());
        assertFalse(failure(result).getMessage().contains("peer-token"));
        assertEquals(1, open.session.textCalls.get());
        assertEquals(1, open.session.cancelCalls.get());
        assertEquals(0, admission.size());
    }

    @Test void completedWriteWinsAgainstLaterCancelWithoutMutatingSession() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);
        CompletableFuture<?> result = action.handle(message("tenant-a", text("one"))).toCompletableFuture();
        WebSocketTestSupport.OpenAttempt open = transport.awaitOpen(0);
        open.succeed();

        open.session.send.complete(null);
        assertFalse(result.cancel(true));
        assertEquals(0, open.session.cancelCalls.get(), "a losing cancel must not touch the session");
        open.session.terminal();
        assertEquals(0, admission.size());
    }

    @Test void terminalBeforeOpenCompletionFencesLateSessionAndKeepsOriginalOutcome() {
        WebSocketAdmissionRegistry admission = new WebSocketAdmissionRegistry();
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = action(transport, admission, 1);
        var result = action.handle(message("tenant-a", text("one")));
        WebSocketTestSupport.OpenAttempt open = transport.awaitOpen(0);

        open.listener.onFailure(new NodePackageServiceException(
                NodePackageServiceException.Reason.TRANSPORT_FAILED));
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(result).code());
        assertEquals(0, admission.size());

        open.succeed();
        assertEquals(0, open.session.textCalls.get());
        assertEquals(1, open.session.cancelCalls.get());
        assertEquals(WebSocketException.Code.TRANSPORT_UNAVAILABLE, failure(result).code());
        assertEquals(0, admission.size());
    }

    @Test void requestContainsOnlyOperatorProfileAuthorityAndTightenedLimits() {
        WebSocketProfile profile = WebSocketTestSupport.profile(1, 1);
        var transport = new WebSocketTestSupport.FakeTransport();
        NodeAction action = new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile))
                .create(new ai.ravenroot.api.node.NodeConfiguration("socket", WebSocketSendNodeBehavior.BEHAVIOR,
                        java.util.Map.of("websocketProfile", "events", "maxMessageBytes", "8",
                                "maxFragments", "1", "timeoutMs", "100")), transport);
        var result = action.handle(message("tenant-a", text("ok")));
        var open = transport.awaitOpen(0);

        assertEquals(profile.destination(), open.request.destination());
        assertEquals(profile.headers(), open.request.headers());
        assertEquals(profile.subprotocols(), open.request.subprotocols());
        assertEquals(8, open.request.maximumMessageBytes().orElseThrow());
        assertEquals(1, open.request.maximumFragments().orElseThrow());
        assertEquals("handshake", open.request.credential().orElseThrow().bindingId());
        assertFalse(open.request.headers().toString().contains("socket-secret"));
        open.fail(NodePackageServiceException.Reason.CREDENTIAL_UNAVAILABLE);
        assertEquals(WebSocketException.Code.CREDENTIAL_UNAVAILABLE, failure(result).code());
        assertFalse(failure(result).getMessage().contains("socket-secret"));
    }

    private static NodeAction action(WebSocketTestSupport.FakeTransport transport,
                                     WebSocketAdmissionRegistry admission, int maximum) {
        WebSocketProfile profile = WebSocketTestSupport.profile(maximum, 2);
        return new WebSocketSendNodeBehavior(WebSocketTestSupport.resolver(profile), admission)
                .create(WebSocketTestSupport.configuration(), transport);
    }
}
