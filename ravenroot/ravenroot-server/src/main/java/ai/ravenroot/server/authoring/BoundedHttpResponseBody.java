package ai.ravenroot.server.authoring;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Collects at most {@code limit + 1} response bytes while keeping the HTTP exchange open until the
 * body completes. Unlike {@code ofInputStream()}, this lets {@link java.net.http.HttpRequest#timeout()}
 * bound a provider that sends headers and then stalls, without first allocating an unbounded body.
 */
final class BoundedHttpResponseBody {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("ravenroot-http-body-deadline").factory());

    private BoundedHttpResponseBody() { }

    static HttpResponse.BodyHandler<byte[]> upTo(int limit, Duration timeout) {
        if (limit < 0 || limit == Integer.MAX_VALUE) throw new IllegalArgumentException("invalid response limit");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("invalid timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        return ignored -> new Subscriber(limit, deadline);
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private ScheduledFuture<?> deadlineTask;
        private boolean finished;

        private Subscriber(int limit, long deadline) {
            this.limit = limit;
            this.output = new ByteArrayOutputStream(Math.min(limit + 1, 8192));
            long remaining = Math.max(0L, deadline - System.nanoTime());
            this.deadlineTask = DEADLINES.schedule(this::expire, remaining, TimeUnit.NANOSECONDS);
        }

        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public synchronized void onSubscribe(Flow.Subscription next) {
            if (finished) {
                next.cancel();
                return;
            }
            if (subscription != null) {
                next.cancel();
                return;
            }
            subscription = next;
            next.request(1);
        }

        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (finished) return;
            for (ByteBuffer buffer : buffers) {
                int remaining = limit + 1 - output.size();
                if (remaining <= 0) break;
                int count = Math.min(remaining, buffer.remaining());
                if (count > 0) {
                    byte[] bytes = new byte[count];
                    buffer.get(bytes);
                    output.writeBytes(bytes);
                }
                if (output.size() > limit) break;
            }
            if (output.size() > limit) {
                finished = true;
                subscription.cancel();
                deadlineTask.cancel(false);
                body.complete(output.toByteArray());
            } else subscription.request(1);
        }

        @Override public synchronized void onError(Throwable failure) {
            if (finished) return;
            finished = true;
            deadlineTask.cancel(false);
            body.completeExceptionally(failure);
        }

        @Override public synchronized void onComplete() {
            if (finished) return;
            finished = true;
            deadlineTask.cancel(false);
            body.complete(output.toByteArray());
        }

        private synchronized void expire() {
            if (finished) return;
            finished = true;
            if (subscription != null) subscription.cancel();
            body.completeExceptionally(new HttpTimeoutException("HTTP response body deadline exceeded"));
        }
    }
}
