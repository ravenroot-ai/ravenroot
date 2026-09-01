package ai.ravenroot.server.assistant.oauth;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * A fake {@link HttpClient} that answers from a script and records what it was asked.
 *
 * <p>The device flow is a <em>sequence</em> of calls whose bodies differ — begin, then one or more
 * polls — so unlike {@code AnthropicWireFormatTest}'s single-response double this one pops a queue.
 * It also captures the request <b>body</b>, not only the request: the form fields are the thing
 * RFC 8628 specifies, and a test that could not read them could not check conformance at all.</p>
 *
 * <p>No network, as everywhere else in this suite. See {@code AssistantDeviceAuthorizationTest} for
 * what that does and does not establish.</p>
 *
 * <p><b>Public so {@code AssistantCompositionTest} can
 * drive the device flow the composition root actually assembles. Copying it into that package would
 * have produced a second double that could drift from this one on the very thing it exists to
 * exercise: reading the response through the caller's own bounded body handler.</p>
 */
public final class ScriptedHttpClient extends HttpClient {

    private final List<HttpRequest> requests;
    private final List<String> bodies;
    private final Deque<String> responses;
    private final Surfacing surfacing;

    public ScriptedHttpClient(List<HttpRequest> requests, List<String> bodies, Deque<String> responses) {
        this(requests, bodies, responses, Surfacing.WRAPPED);
    }

    ScriptedHttpClient(List<HttpRequest> requests, List<String> bodies, Deque<String> responses,
                       Surfacing surfacing) {
        this.requests = requests;
        this.bodies = bodies;
        this.responses = responses;
        this.surfacing = surfacing;
    }

    /**
     * How a body subscriber's {@link IOException} reaches the caller of {@code send}.
     *
     * <p>This is not a stylistic knob. {@code AssistantDeviceAuthorization} has to recognise an
     * over-budget refusal — a {@code BoundedBodyHandlers.ResponseTooLargeException} — to say so
     * instead of reporting an unreachable endpoint, and it can only recognise it in the shape it
     * arrives in. The two shapes are therefore both worth a test, and a double that offers only one
     * of them silently decides which half of that recognition is covered.</p>
     */
    enum Surfacing {
        /**
         * <b>The measured behaviour of the JDK's own client, and the default here.</b> On 21.0.11,
         * HTTP/1.1 over loopback, synchronous and asynchronous, with the length declared and with it
         * chunked, a body subscriber's {@code IOException} always arrived as the <em>cause</em> of a
         * fresh {@code IOException} — never as itself.
         */
        WRAPPED {
            @Override
            IOException surface(IOException failed) {
                return new IOException(failed);
            }
        },
        /**
         * <b>The bare shape, which that measurement did not produce.</b> Kept because the measurement
         * covers one JDK minor and one protocol version, and the contract of {@code send} does not
         * promise the wrapping: HTTP/2, another vendor's client or another minor may hand the
         * subscriber's exception straight through. Production handles both; this exists so both are
         * exercised rather than one being assumed.
         */
        BARE {
            @Override
            IOException surface(IOException failed) {
                return failed;
            }
        };

        abstract IOException surface(IOException failed);
    }

    /**
     * Answers from the script <b>through the caller's own {@link HttpResponse.BodyHandler}</b>.
     *
     * <p>An earlier revision fabricated the body object itself — first a {@code String}, then an
     * {@code InputStream} — which meant the production body handler was never applied and whatever
     * bound it carried was never exercised. {@code AssistantDeviceAuthorization} now reads through
     * {@code BoundedBodyHandlers.ofByteArray}, and that ceiling <em>is</em> the security control
     * added: a double that bypassed it would leave the control untested by construction, however many
     * tests were written. So the scripted text is driven into the real subscriber, and the response
     * body is whatever that subscriber produced — including no body at all, when it refused.</p>
     *
     * <p><b>No {@code Content-Length} is declared</b>, deliberately. {@code BoundedBodyHandlers} has
     * two refusal paths: a declared length over the ceiling, refused before a byte is read, and an
     * absent or understated one, caught while streaming with the subscription cancelled at that point.
     * The second is the adversarial case — a hostile endpoint controls what it declares, not what it
     * sends — so it is the one this double exercises.</p>
     */
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        requests.add(request);
        bodies.add(readBody(request));
        String scripted = responses.isEmpty() ? "{}" : responses.removeFirst();
        byte[] payload = scripted.getBytes(StandardCharsets.UTF_8);

        HttpResponse.BodySubscriber<T> subscriber = handler.apply(new CannedResponseInfo());
        var subscription = new OneShotSubscription();
        subscriber.onSubscribe(subscription);
        if (!subscription.cancelled()) {
            // onComplete follows onNext even when onNext refused and cancelled mid-buffer, which a
            // real client would not do -- it stops at the cancellation. Harmless, because every
            // BoundedBodyHandlers subscriber latches on a `done` flag and ignores the late call, and
            // left as it is because the alternative is for this double to track a cancellation the
            // subscriber has already recorded itself.
            subscriber.onNext(List.of(ByteBuffer.wrap(payload)));
            subscriber.onComplete();
        }
        T body;
        try {
            body = subscriber.getBody().toCompletableFuture().join();
        } catch (CompletionException refused) {
            // Surfaced in the shape a real client uses, which is NOT a bare rethrow -- see
            // Surfacing.WRAPPED for the measurement. An earlier revision of this double rethrew the
            // subscriber's exception bare while a comment here claimed it reproduced the real client.
            // It did not, and the cost was precise: AssistantDeviceAuthorization#overBudget examines
            // both the exception and its cause, so every test went through the branch production
            // never takes, and deleting the branch production does take would have left the suite
            // green while the authored budget message silently became "could not be reached".
            if (refused.getCause() instanceof IOException failed) {
                throw surfacing.surface(failed);
            }
            throw refused;
        }
        return new CannedResponse<>(request, body);
    }

    /** Requests everything at once, as a real subscription does, and records a cancellation. */
    private static final class OneShotSubscription implements Flow.Subscription {
        private volatile boolean cancelled;

        @Override
        public void request(long n) {
            // Nothing to schedule: send() pushes the one buffer itself, once onSubscribe returns.
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        boolean cancelled() {
            return cancelled;
        }
    }

    /** The half of a response a {@link HttpResponse.BodyHandler} sees before it chooses a subscriber. */
    private static final class CannedResponseInfo implements HttpResponse.ResponseInfo {
        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("application/json")),
                    (name, value) -> true);
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    /**
     * Drains the request's body publisher into a string.
     *
     * <p>{@code HttpRequest.BodyPublisher} is a {@link Flow.Publisher}, so there is no getter — the
     * only way to see what would have been sent is to subscribe to it, which is what a real client
     * does too.</p>
     */
    private static String readBody(HttpRequest request) {
        return request.bodyPublisher().map(publisher -> {
            var collected = new StringBuilder();
            var done = new CountDownLatch(1);
            publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    byte[] bytes = new byte[item.remaining()];
                    item.get(bytes);
                    collected.append(new String(bytes, StandardCharsets.UTF_8));
                }

                @Override
                public void onError(Throwable throwable) {
                    done.countDown();
                }

                @Override
                public void onComplete() {
                    done.countDown();
                }
            });
            try {
                done.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return collected.toString();
        }).orElse("");
    }

    /**
     * Not a record: {@code HttpResponse#body()} returns {@code T}, so a {@code body} component would
     * generate an accessor that clashes with the interface method it is meant to implement.
     *
     * <p>The body is <b>whatever the caller's own body handler produced</b>, never a value this
     * double chose. That is what makes the production read path — including its ceiling — the thing
     * under test, and it is also why {@code body()} cannot return null: the handler either completes
     * with a value or completes exceptionally, and the exceptional case never reaches here.</p>
     */
    private static final class CannedResponse<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final T body;

        CannedResponse(HttpRequest request, T body) {
            this.request = request;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("application/json")),
                    (name, value) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> handler) {
        try {
            return CompletableFuture.completedFuture(send(request, handler));
        } catch (IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                            HttpResponse.BodyHandler<T> handler,
                                                            HttpResponse.PushPromiseHandler<T> promises) {
        return sendAsync(request, handler);
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        try {
            return SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    @Override
    public WebSocket.Builder newWebSocketBuilder() {
        throw new UnsupportedOperationException("no websockets in this double");
    }
}
