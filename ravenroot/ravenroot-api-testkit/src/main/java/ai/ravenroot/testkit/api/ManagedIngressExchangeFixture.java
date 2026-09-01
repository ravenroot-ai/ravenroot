package ai.ravenroot.testkit.api;

import ai.ravenroot.api.execution.CancellationSignal;
import ai.ravenroot.api.ingress.IngressPrincipal;
import ai.ravenroot.api.ingress.IngressRequest;
import ai.ravenroot.api.ingress.IngressRequestContext;
import ai.ravenroot.api.ingress.IngressResponse;
import ai.ravenroot.api.ingress.IngressRouteHandler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives one managed-ingress handler through the same handling window the server adapter gives it,
 * without the test importing anything from {@code ravenroot-server}.
 *
 * <h2>Why this exists in the testkit and not in the server's own tests</h2>
 * <p>The deadline and the cancellation signal are produced by {@code ManagedIngressRegistry}, which
 * lives in {@code ravenroot-server} and is not on an extension's classpath. Without a fixture, an
 * extension that wants to prove its handler actually honours its window has three bad options: depend
 * on the server module, stand up a real HTTP listener, or hand-roll a {@link CancellationSignal} that
 * only resembles the real one. The first inverts the dependency the managed seam exists to prevent,
 * the second makes a unit test into an integration test, and the third tests the imitation.</p>
 *
 * <p>This fixture is the fourth option. It builds a genuine {@link IngressRequestContext} — the same
 * public record the adapter builds — and fires cancellation with the same exactly-once semantics the
 * adapter guarantees, so a handler that passes here behaves the same way in production.</p>
 *
 * <h2>What holding one does not grant</h2>
 * <p>A context is a value a handler reads. Building one confers no authority: the adapter never
 * accepts a context as input, so a package cannot use this fixture — or the public record it
 * produces — to lengthen a deadline, suppress a cancellation, or influence any live request.</p>
 *
 * <h2>One deliberate difference from the adapter</h2>
 * <p>{@link #cancel()} runs listeners on the calling thread, so a test can assert immediately after it
 * without a latch. The adapter runs them on a thread of its own, precisely so a blocking listener
 * cannot extend a deadline or stall a retirement. The observable semantics asserted here — fires at
 * most once, late registration still runs, a throwing listener is contained — are identical; the
 * threading is not, which is why a listener must never assume which thread it is on.</p>
 */
public final class ManagedIngressExchangeFixture {
    private final ControllableCancellation cancellation = new ControllableCancellation();
    private final Instant deadline;

    private ManagedIngressExchangeFixture(Instant deadline) {
        this.deadline = Objects.requireNonNull(deadline, "deadline");
    }

    /** A window that is still open for {@code budget}, the ordinary case. */
    public static ManagedIngressExchangeFixture withBudget(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("budget must be positive; use expired() instead");
        }
        return new ManagedIngressExchangeFixture(Instant.now().plus(budget));
    }

    /**
     * A window that has already closed.
     *
     * <p>The adapter refuses such a request with a sanitized 504 before the handler is invoked, so
     * this exists to test the other side of the same rule: a handler reached with no budget left —
     * because it was resumed, retried or scheduled — must not start long work.</p>
     */
    public static ManagedIngressExchangeFixture expired() {
        return new ManagedIngressExchangeFixture(Instant.now().minusSeconds(1));
    }

    /** The context to hand a handler; the same value for every call on this fixture. */
    public IngressRequestContext context() {
        return new IngressRequestContext(deadline, cancellation);
    }

    /**
     * Invokes {@code handler} through the context-aware entry point, exactly as the adapter does.
     *
     * <p>Calling the two-argument overload rather than {@link IngressRouteHandler#handle(IngressRequest)}
     * is the point: a handler that has not overridden it silently receives the delegating default and
     * never sees its window, and that is a fact a test should be able to observe.</p>
     */
    public CompletionStage<IngressResponse> invoke(IngressRouteHandler handler, IngressRequest request) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(request, "request");
        return handler.handle(request, context());
    }

    /** Fires cancellation. Repeated calls are ignored, as they are in the adapter. */
    public void cancel() {
        cancellation.cancel();
    }

    public boolean cancelled() {
        return cancellation.cancelled();
    }

    /** How many times registered listeners have run in total; the adapter runs each at most once. */
    public int listenerRuns() {
        return cancellation.runs.get();
    }

    /** A minimal authenticated request, enough for a handler that does not read the projection. */
    public static IngressRequest request(String method, String relativePath) {
        return request(method, relativePath, Map.of(), Map.of(), new byte[0]);
    }

    public static IngressRequest request(String method, String relativePath,
                                         Map<String, List<String>> query, Map<String, String> headers,
                                         byte[] body) {
        return new IngressRequest(new IngressPrincipal("tenant", "subject", "issuer", "USER"),
                method, relativePath, query, headers, body);
    }

    private static final class ControllableCancellation implements CancellationSignal {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicInteger runs = new AtomicInteger();
        private final Queue<Runnable> listeners = new ConcurrentLinkedQueue<>();

        @Override public boolean cancelled() {
            return cancelled.get();
        }

        @Override public void onCancel(Runnable listener) {
            Objects.requireNonNull(listener, "listener");
            listeners.add(listener);
            if (cancelled.get()) drain();
        }

        private void cancel() {
            if (cancelled.compareAndSet(false, true)) drain();
        }

        private void drain() {
            Runnable listener;
            while ((listener = listeners.poll()) != null) {
                runs.incrementAndGet();
                try {
                    listener.run();
                } catch (RuntimeException | Error ignored) {
                    // Mirrors the adapter: one listener's failure neither stops the others nor
                    // surfaces as a failure of the cancellation itself.
                }
            }
        }
    }
}
