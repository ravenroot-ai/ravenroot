package ai.ravenroot.core.security.egress;

import ai.ravenroot.api.node.service.ExternalIoLimits;
import ai.ravenroot.api.node.service.OutboundHttpRepresentationPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Streaming HTTP response gates that enforce finite external-I/O limits before accumulation. */
public final class BoundedBodyHandlers {
    /** Raised when encoded or decoded response data exceeds its finite ceiling. */
    public static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        public ResponseTooLargeException(long limit) {
            this("transport", limit);
        }

        private ResponseTooLargeException(String dimension, long limit) {
            super("Response " + dimension + " exceeds its limit of " + limit + " bytes");
        }
    }

    /** Raised when a response declares a media type outside the caller's immutable allowlist. */
    public static final class ResponseMediaTypeException extends IOException {
        private static final long serialVersionUID = 1L;
        private ResponseMediaTypeException() { super("Response media type is not permitted"); }
    }

    /** Raised when a response uses an unsupported or ambiguous content encoding. */
    public static final class ResponseEncodingException extends IOException {
        private static final long serialVersionUID = 1L;
        private ResponseEncodingException() { super("Response content encoding is not permitted"); }
    }

    private BoundedBodyHandlers() { }

    /** Returns a bounded string handler for an opaque, identity-encoded response. */
    public static HttpResponse.BodyHandler<String> ofString(long maxBytes, Charset charset) {
        ExternalIoLimits limits = identityLimits(maxBytes);
        return info -> HttpResponse.BodySubscribers.mapping(subscriber(info.headers(), limits, true),
                bytes -> new String(bytes, charset));
    }

    /** Returns a bounded byte handler for an opaque, identity-encoded response. */
    public static HttpResponse.BodyHandler<byte[]> ofByteArray(long maxBytes) {
        return withLimits(identityLimits(maxBytes));
    }

    /**
     * Returns a handler enforcing media type, encoded bytes, decoded output, and decompression ratio.
     * Header refusal happens before the body subscription requests data.
     */
    public static HttpResponse.BodyHandler<byte[]> withLimits(ExternalIoLimits limits) {
        java.util.Objects.requireNonNull(limits, "limits");
        return info -> subscriber(info.headers(), limits, true);
    }

    /**
     * Returns a bounded handler that applies the media-type allowlist only to successful responses.
     * Error bodies remain subject to encoding and byte ceilings so callers can classify their status
     * without parsing or trusting the remote representation.
     */
    public static HttpResponse.BodyHandler<byte[]> withLimitsForSuccess(ExternalIoLimits limits) {
        return withLimits(limits, OutboundHttpRepresentationPolicy.SUCCESS_ONLY);
    }

    /**
     * Returns a bounded handler that validates media types for the statuses selected by a finite
     * immutable protocol policy. Encoding and byte bounds apply to every status.
     */
    public static HttpResponse.BodyHandler<byte[]> withLimits(ExternalIoLimits limits,
                                                               OutboundHttpRepresentationPolicy policy) {
        java.util.Objects.requireNonNull(limits, "limits");
        java.util.Objects.requireNonNull(policy, "policy");
        return info -> subscriber(info.headers(), limits, policy.validates(info.statusCode()));
    }

    private static ExternalIoLimits identityLimits(long maxBytes) {
        return new ExternalIoLimits(maxBytes, maxBytes, maxBytes, maxBytes, 1,
                java.time.Duration.ofSeconds(30), java.time.Duration.ofSeconds(2),
                java.util.Set.of(), java.util.Set.of("identity"));
    }

    private static HttpResponse.BodySubscriber<byte[]> subscriber(HttpHeaders headers, ExternalIoLimits limits,
                                                                   boolean validateRepresentation) {
        IOException headerFailure = validateHeaders(headers, limits, validateRepresentation);
        boolean missingMediaType = validateRepresentation && !limits.acceptedMediaTypes().isEmpty()
                && headers.allValues("Content-Type").isEmpty();
        return new LimitedSubscriber(limits, encoding(headers), headerFailure, missingMediaType);
    }

    private static IOException validateHeaders(HttpHeaders headers, ExternalIoLimits limits,
                                                boolean validateRepresentation) {
        String encoding = encoding(headers);
        if (encoding == null || !limits.acceptedContentEncodings().contains(encoding)) {
            return new ResponseEncodingException();
        }
        long declaredCeiling = "identity".equals(encoding) ? identityCeiling(limits)
                : limits.maximumEncodedResponseBytes();
        List<String> lengths = headers.allValues("Content-Length");
        if (lengths.size() > 1) return new ResponseTooLargeException("transport", declaredCeiling);
        if (!lengths.isEmpty()) {
            try {
                long declared = Long.parseLong(lengths.getFirst());
                if (declared < 0 || declared > declaredCeiling) {
                    return new ResponseTooLargeException("transport", declaredCeiling);
                }
            } catch (NumberFormatException invalid) {
                return new ResponseTooLargeException("transport", declaredCeiling);
            }
        }
        if (validateRepresentation && !limits.acceptedMediaTypes().isEmpty()) {
            List<String> values = headers.allValues("Content-Type");
            if (!values.isEmpty()) {
                if (values.size() != 1) return new ResponseMediaTypeException();
                String value = values.getFirst();
                int semicolon = value.indexOf(';');
                String media = (semicolon < 0 ? value : value.substring(0, semicolon)).strip()
                        .toLowerCase(Locale.ROOT);
                if (!limits.acceptedMediaTypes().contains(media)) return new ResponseMediaTypeException();
            }
        }
        return null;
    }

    private static String encoding(HttpHeaders headers) {
        List<String> values = headers.allValues("Content-Encoding");
        if (values.isEmpty()) return "identity";
        if (values.size() != 1 || values.getFirst().indexOf(',') >= 0) return null;
        String value = values.getFirst().strip().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? null : value;
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final ExternalIoLimits limits;
        private final String encoding;
        private final IOException initialFailure;
        private final boolean missingMediaType;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        private final AtomicBoolean done = new AtomicBoolean();
        private Flow.Subscription subscription;

        LimitedSubscriber(ExternalIoLimits limits, String encoding, IOException initialFailure,
                          boolean missingMediaType) {
            this.limits = limits;
            this.encoding = encoding;
            this.initialFailure = initialFailure;
            this.missingMediaType = missingMediaType;
        }

        @Override public void onSubscribe(Flow.Subscription incoming) {
            subscription = incoming;
            if (initialFailure != null) fail(initialFailure);
            else incoming.request(1);
        }

        @Override public void onNext(List<ByteBuffer> items) {
            if (done.get()) return;
            try {
                for (ByteBuffer item : items) {
                    int length = item.remaining();
                    if (missingMediaType && length > 0) throw new ResponseMediaTypeException();
                    long ceiling = "identity".equals(encoding) ? identityCeiling(limits)
                            : limits.maximumEncodedResponseBytes();
                    if ((long) encoded.size() + length > ceiling) {
                        throw new ResponseTooLargeException("transport", ceiling);
                    }
                    byte[] copy = new byte[length];
                    item.get(copy);
                    encoded.write(copy);
                }
                subscription.request(1);
            } catch (IOException failure) {
                fail(failure);
            }
        }

        @Override public void onError(Throwable throwable) {
            if (done.compareAndSet(false, true)) {
                encoded.reset();
                body.completeExceptionally(throwable);
            }
        }

        @Override public void onComplete() {
            if (!done.compareAndSet(false, true)) return;
            try {
                byte[] raw = encoded.toByteArray();
                encoded.reset();
                if (missingMediaType && raw.length != 0) throw new ResponseMediaTypeException();
                byte[] output = "gzip".equals(encoding) ? inflate(raw, limits) : raw;
                if (output.length > limits.maximumDecodedResponseBytes()) {
                    throw new ResponseTooLargeException("decoded output",
                            limits.maximumDecodedResponseBytes());
                }
                body.complete(output);
            } catch (IOException failure) {
                body.completeExceptionally(failure);
            }
        }

        @Override public CompletionStage<byte[]> getBody() { return body; }

        private void fail(IOException failure) {
            if (!done.compareAndSet(false, true)) return;
            encoded.reset();
            if (subscription != null) subscription.cancel();
            body.completeExceptionally(failure);
        }
    }

    private static byte[] inflate(byte[] raw, ExternalIoLimits limits) throws IOException {
        int offset = gzipHeader(raw);
        long ratioCeiling;
        try {
            ratioCeiling = Math.multiplyExact(Math.max(1L, raw.length), limits.maximumDecompressionRatio());
        } catch (ArithmeticException overflow) {
            ratioCeiling = Long.MAX_VALUE;
        }
        long ceiling = Math.min(limits.maximumDecodedResponseBytes(), ratioCeiling);
        Inflater inflater = new Inflater(true);
        inflater.setInput(raw, offset, raw.length - offset);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            while (!inflater.finished()) {
                int read;
                try { read = inflater.inflate(chunk); }
                catch (DataFormatException invalid) { throw new ResponseEncodingException(); }
                if (read == 0) {
                    if (inflater.needsDictionary() || inflater.needsInput()) throw new ResponseEncodingException();
                    continue;
                }
                if ((long) output.size() + read > ceiling) {
                    throw new ResponseTooLargeException("decoded output", ceiling);
                }
                output.write(chunk, 0, read);
            }
            if (inflater.getRemaining() != 8) throw new ResponseEncodingException();
            int trailer = raw.length - 8;
            byte[] decoded = output.toByteArray();
            CRC32 crc = new CRC32();
            crc.update(decoded);
            if (littleEndianInt(raw, trailer) != crc.getValue()
                    || littleEndianInt(raw, trailer + 4) != Integer.toUnsignedLong(decoded.length)) {
                throw new ResponseEncodingException();
            }
            return decoded;
        } finally {
            inflater.end();
        }
    }

    private static int gzipHeader(byte[] raw) throws IOException {
        if (raw.length < 18 || (raw[0] & 0xff) != 0x1f || (raw[1] & 0xff) != 0x8b
                || (raw[2] & 0xff) != 8 || (raw[3] & 0xe0) != 0) throw new ResponseEncodingException();
        int flags = raw[3] & 0xff;
        // Header CRCs are intentionally unsupported: accepting the flag without validating the
        // checksum would make malformed gzip look trustworthy.
        if ((flags & 2) != 0) throw new ResponseEncodingException();
        int cursor = 10;
        if ((flags & 4) != 0) {
            if (cursor + 2 > raw.length - 8) throw new ResponseEncodingException();
            int length = (raw[cursor] & 0xff) | (raw[cursor + 1] & 0xff) << 8;
            cursor += 2 + length;
            if (cursor > raw.length - 8) throw new ResponseEncodingException();
        }
        if ((flags & 8) != 0) cursor = zeroTerminated(raw, cursor);
        if ((flags & 16) != 0) cursor = zeroTerminated(raw, cursor);
        if (cursor > raw.length - 8) throw new ResponseEncodingException();
        return cursor;
    }

    private static int zeroTerminated(byte[] raw, int cursor) throws IOException {
        if (cursor < 0 || cursor >= raw.length - 8) throw new ResponseEncodingException();
        while (cursor < raw.length - 8 && raw[cursor++] != 0) { }
        if (cursor >= raw.length - 8 && raw[cursor - 1] != 0) throw new ResponseEncodingException();
        return cursor;
    }

    private static long littleEndianInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong((bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16 | (bytes[offset + 3] & 0xff) << 24);
    }

    private static long identityCeiling(ExternalIoLimits limits) {
        return Math.min(limits.maximumEncodedResponseBytes(), limits.maximumDecodedResponseBytes());
    }
}
