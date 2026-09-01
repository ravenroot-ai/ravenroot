package ai.ravenroot.core.security.egress;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Response body handlers that stop reading at a ceiling (SEC-10).
 *
 * <p><b>A gate, not a default.</b> Previously the HTTP node read the body with
 * {@code BodyHandlers.ofString}, which is unbounded: an attacker-chosen endpoint could return an
 * arbitrarily large body and the node would buffer all of it. There is deliberately no factory here
 * that means "no limit" — every entry point takes a positive ceiling.
 *
 * <p><b>Scope.</b> This is the reach-side safety ceiling: it stops an unbounded
 * read from a destination the caller does not control. It is not a quota, a rate limit or a
 * per-tenant budget — those belong to volume policy and must not be grafted onto this
 * class.
 */
public final class BoundedBodyHandlers {

    /** Raised when a response exceeds the ceiling. An {@link IOException} so it surfaces normally. */
    public static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public ResponseTooLargeException(long limit) {
            super("Response body exceeds the outbound limit of " + limit + " bytes");
        }
    }

    private BoundedBodyHandlers() {
    }

    /**
     * A string body handler that refuses beyond {@code maxBytes}. A declared {@code Content-Length}
     * over the ceiling is refused before any body byte is read; an undeclared or understated length
     * is caught while streaming, and the subscription is cancelled at that point rather than after
     * buffering the rest.
     */
    public static HttpResponse.BodyHandler<String> ofString(long maxBytes, Charset charset) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("Response ceiling must be positive: " + maxBytes);
        }
        return responseInfo -> {
            long declared = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new BoundedStringSubscriber(maxBytes, charset, declared > maxBytes);
        };
    }

    /** Byte-preserving counterpart used by package services whose response media type is not known. */
    public static HttpResponse.BodyHandler<byte[]> ofByteArray(long maxBytes) {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Response ceiling must be between 1 and "
                    + Integer.MAX_VALUE + " bytes: " + maxBytes);
        }
        return responseInfo -> {
            long declared = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new BoundedByteArraySubscriber(maxBytes, declared > maxBytes);
        };
    }

    private static final class BoundedStringSubscriber implements HttpResponse.BodySubscriber<String> {
        private final long limit;
        private final Charset charset;
        private final boolean refuseImmediately;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private long seen;
        private Flow.Subscription subscription;

        BoundedStringSubscriber(long limit, Charset charset, boolean refuseImmediately) {
            this.limit = limit;
            this.charset = charset;
            this.refuseImmediately = refuseImmediately;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            this.subscription = incoming;
            if (refuseImmediately) {
                fail();
                return;
            }
            incoming.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done.get()) {
                return;
            }
            for (ByteBuffer item : items) {
                seen += item.remaining();
                if (seen > limit) {
                    fail();
                    return;
                }
                buffers.add(item);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (done.compareAndSet(false, true)) {
                body.completeExceptionally(throwable);
            }
        }

        @Override
        public void onComplete() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            int total = 0;
            for (ByteBuffer buffer : buffers) {
                total += buffer.remaining();
            }
            byte[] bytes = new byte[total];
            int offset = 0;
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                buffer.get(bytes, offset, length);
                offset += length;
            }
            buffers.clear();
            body.complete(new String(bytes, charset));
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        private void fail() {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            buffers.clear();
            if (subscription != null) {
                subscription.cancel();
            }
            body.completeExceptionally(new ResponseTooLargeException(limit));
        }
    }

    private static final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final boolean refuseImmediately;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final List<ByteBuffer> buffers = new ArrayList<>();
        private final AtomicBoolean done = new AtomicBoolean();
        private long seen;
        private Flow.Subscription subscription;

        BoundedByteArraySubscriber(long limit, boolean refuseImmediately) {
            this.limit = limit;
            this.refuseImmediately = refuseImmediately;
        }

        @Override
        public void onSubscribe(Flow.Subscription incoming) {
            subscription = incoming;
            if (refuseImmediately) {
                fail();
            } else {
                incoming.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (done.get()) return;
            for (ByteBuffer item : items) {
                seen += item.remaining();
                if (seen > limit) {
                    fail();
                    return;
                }
                buffers.add(item);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (done.compareAndSet(false, true)) body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!done.compareAndSet(false, true)) return;
            byte[] bytes = new byte[Math.toIntExact(seen)];
            int offset = 0;
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                buffer.get(bytes, offset, length);
                offset += length;
            }
            buffers.clear();
            body.complete(bytes);
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        private void fail() {
            if (!done.compareAndSet(false, true)) return;
            buffers.clear();
            if (subscription != null) subscription.cancel();
            body.completeExceptionally(new ResponseTooLargeException(limit));
        }
    }
}
