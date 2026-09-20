package com.jobsearchassistant.integrations;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider;

/** Shared bounded transport. Only administrator-selected protocol code supplies the destination. */
final class HttpGroundedDraftingProvider implements GroundedDraftingProvider, AutoCloseable {
    static final int REQUEST_BYTES_MAX = 256 * 1024;
    static final int RESPONSE_BYTES_MAX = 128 * 1024;
    private final DraftingProtocol protocol;
    private final String model;
    private final String credential;
    private final HttpClient client;
    private final Duration timeout;
    private final Semaphore capacity = new Semaphore(2);
    private final AtomicBoolean closed = new AtomicBoolean();

    HttpGroundedDraftingProvider(DraftingProtocol protocol, String model, String credential,
            HttpClient client, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(TIMEOUT) > 0) {
            throw new IllegalArgumentException("invalid_drafting_timeout");
        }
        this.protocol = protocol;
        this.model = model;
        this.credential = credential;
        this.client = client;
        this.timeout = timeout;
    }

    @Override public Outcome suggest(Request request) {
        if (Thread.currentThread().isInterrupted()) return Failure.CANCELLED;
        if (closed.get() || !capacity.tryAcquire()) return Failure.UNAVAILABLE;
        long started = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        LimitedBody body = new LimitedBody();
        try {
            if (request == null) return Failure.INVALID_RESPONSE;
            byte[] payload = DraftingJson.MAPPER.writeValueAsBytes(protocol.encode(request, model));
            if (payload.length > REQUEST_BYTES_MAX) return Failure.INVALID_RESPONSE;
            HttpRequest.Builder builder = HttpRequest.newBuilder(protocol.endpoint())
                    .timeout(Duration.ofNanos(remaining(started)))
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .header("Accept-Encoding", "identity")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            protocol.authenticate(builder, credential);
            pending = client.sendAsync(builder.build(), info -> {
                // Error bodies are not read, parsed, retained or surfaced. Redirects are failures, never followed.
                if (info.statusCode() != 200) throw new TransportFailure(Failure.UNAVAILABLE);
                String type = info.headers().firstValue("Content-Type").orElse("");
                if (!type.matches("(?i)application/json(?:\\s*;\\s*charset=\\\"?utf-8\\\"?)?\\s*")
                        || !info.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")) {
                    throw new TransportFailure(Failure.INVALID_RESPONSE);
                }
                try {
                    if (info.headers().firstValueAsLong("Content-Length").orElse(0) > RESPONSE_BYTES_MAX) {
                        throw new TransportFailure(Failure.INVALID_RESPONSE);
                    }
                } catch (NumberFormatException invalidLength) { throw new TransportFailure(Failure.INVALID_RESPONSE); }
                return body;
            });
            byte[] response = pending.get(remaining(started), TimeUnit.NANOSECONDS).body();
            var parsed = DraftingJson.MAPPER.readTree(response);
            if (parsed == null || !parsed.isObject()) return Failure.INVALID_RESPONSE;
            Outcome outcome = GroundedDraftingProvider.validate(request, protocol.decode(parsed, request));
            remaining(started);
            return Thread.currentThread().isInterrupted() ? Failure.CANCELLED : outcome;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Failure.CANCELLED;
        } catch (TimeoutException timeoutFailure) {
            return Failure.TIMEOUT;
        } catch (ExecutionException transportFailure) {
            return failure(transportFailure);
        } catch (java.io.IOException invalidJson) {
            return Failure.INVALID_RESPONSE;
        } catch (RuntimeException safeFailure) {
            return failure(safeFailure);
        } finally {
            body.cancel();
            if (pending != null && !pending.isDone()) pending.cancel(true);
            capacity.release();
        }
    }

    private long remaining(long started) throws TimeoutException {
        long nanos = timeout.toNanos() - (System.nanoTime() - started);
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }

    private static Failure failure(Throwable error) {
        // Inspect types only. Never log or retain exception messages or raw provider bodies.
        for (int depth = 0; error != null && depth < 10; depth++, error = error.getCause()) {
            if (error instanceof TransportFailure transport) return transport.outcome;
            if (error instanceof HttpTimeoutException) return Failure.TIMEOUT;
            if (error instanceof java.util.concurrent.CancellationException) return Failure.CANCELLED;
        }
        return Failure.UNAVAILABLE;
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) client.shutdownNow();
    }

    @Override public String toString() { return "HttpGroundedDraftingProvider[redacted]"; }

    private static final class TransportFailure extends RuntimeException {
        private final Failure outcome;
        private TransportFailure(Failure outcome) {
            super("drafting_transport_failure", null, false, false);
            this.outcome = outcome;
        }
    }

    /** Bounds bytes as they arrive; does not buffer an unbounded ofByteArray() response. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public synchronized void onSubscribe(Flow.Subscription incoming) {
            if (subscription != null || result.isDone()) { incoming.cancel(); return; }
            subscription = incoming;
            incoming.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > RESPONSE_BYTES_MAX - bytes.size()) {
                    subscription.cancel();
                    bytes.reset();
                    result.completeExceptionally(new TransportFailure(Failure.INVALID_RESPONSE));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error) {
            bytes.reset();
            result.completeExceptionally(new TransportFailure(failure(error)));
        }
        @Override public synchronized void onComplete() {
            if (!result.isDone()) result.complete(bytes.toByteArray());
            bytes.reset();
        }
        synchronized void cancel() {
            if (subscription != null) subscription.cancel();
            bytes.reset();
            result.completeExceptionally(new TransportFailure(Failure.CANCELLED));
        }
    }
}
