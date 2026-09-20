package ai.ravenroot.core.runner;

import ai.ravenroot.core.security.egress.BoundedBodyHandlers;
import java.io.IOException;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** A deadline covers receipt of the bounded body, not merely arrival of response headers. */
final class RunnerHttp {
    private RunnerHttp() { }
    static HttpResponse<byte[]> send(HttpClient client, HttpRequest request, long bytes, Duration timeout)
            throws IOException, InterruptedException {
        var pending = client.sendAsync(request, BoundedBodyHandlers.ofByteArray(bytes));
        return await(pending, timeout);
    }
    static HttpResponse<byte[]> await(CompletableFuture<HttpResponse<byte[]>> pending, Duration timeout)
            throws IOException, InterruptedException {
        try { return pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException expired) { throw new HttpTimeoutException("runner HTTP body deadline exceeded"); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof CancellationException cancelled) throw cancelled;
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException("runner HTTP exchange failed");
        } finally { if (!pending.isDone()) pending.cancel(true); }
    }
}
