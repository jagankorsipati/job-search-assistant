package testfixture.drafting;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** Loopback substitution exists only in tests; no production URL override or mock credential is installed. */
public final class LocalProviderServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient delegate;
    private final AtomicInteger count = new AtomicInteger();
    private final LinkedBlockingQueue<Captured> captures = new LinkedBlockingQueue<>();
    private volatile HttpHandler handler = exchange -> respond(exchange, 200, "{}", false);

    public LocalProviderServer() throws IOException {
        // Set before the first JDK client initializes its static transport policies in this test JVM.
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        delegate = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1).build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                count.incrementAndGet();
                var headers = new java.util.HashMap<String, List<String>>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
                captures.add(new Captured(exchange.getRequestURI().getPath(), Map.copyOf(headers),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                handler.handle(exchange);
            } catch (IOException clientCancelled) { /* Expected for cancellation/oversize tests. Never log bodies. */ }
        });
        server.start();
    }

    public void handle(HttpHandler handler) { this.handler = handler; }
    public int requests() { return count.get(); }

    /** Test-only access to package-private adapters for Documents' real database regression tests. */
    public com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider provider(String id) throws Exception {
        String className = switch (id) {
            case "openai" -> "OpenAiDraftingProtocol";
            case "anthropic" -> "ClaudeDraftingProtocol";
            default -> throw new IllegalArgumentException("unknown_test_provider");
        };
        Class<?> protocolType = Class.forName("com.jobsearchassistant.integrations.DraftingProtocol");
        var protocolConstructor = Class.forName("com.jobsearchassistant.integrations." + className).getDeclaredConstructor();
        protocolConstructor.setAccessible(true);
        Object protocol = protocolConstructor.newInstance();
        var endpointMethod = protocolType.getDeclaredMethod("endpoint");
        endpointMethod.setAccessible(true);
        var constructor = Class.forName("com.jobsearchassistant.integrations.HttpGroundedDraftingProvider")
                .getDeclaredConstructor(protocolType, String.class, String.class, HttpClient.class, Duration.class);
        constructor.setAccessible(true);
        return (com.jobsearchassistant.integrations.drafting.GroundedDraftingProvider) constructor.newInstance(
                protocol, "synthetic-model", "synthetic-test-credential", client((URI) endpointMethod.invoke(protocol)), Duration.ofSeconds(5));
    }
    public Captured take() throws InterruptedException {
        Captured captured = captures.poll(5, TimeUnit.SECONDS);
        if (captured == null) throw new AssertionError("No local provider request");
        return captured;
    }

    public static void respond(HttpExchange exchange, int status, String body, boolean chunked) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public HttpClient client(URI approvedDestination) {
        return new HttpClient() {
            private HttpRequest local(HttpRequest request) {
                if (!request.uri().equals(approvedDestination) || !"https".equals(request.uri().getScheme())) {
                    throw new AssertionError("Unexpected production provider destination");
                }
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + request.uri().getPath()))
                        .method(request.method(), request.bodyPublisher().orElseThrow());
                request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
                request.timeout().ifPresent(builder::timeout);
                return builder.build();
            }
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                return delegate.sendAsync(local(request), handler);
            }
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                    HttpResponse.PushPromiseHandler<T> pushHandler) { throw new AssertionError("No push requests"); }
            @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
                throw new AssertionError("Use cancellable asynchronous transport");
            }
            @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
            @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
            @Override public Redirect followRedirects() { return delegate.followRedirects(); }
            @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
            @Override public SSLContext sslContext() { return delegate.sslContext(); }
            @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
            @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
            @Override public Version version() { return delegate.version(); }
            @Override public Optional<Executor> executor() { return delegate.executor(); }
            @Override public void shutdownNow() { delegate.shutdownNow(); }
        };
    }

    @Override public void close() throws InterruptedException {
        delegate.shutdownNow();
        server.stop(0);
        executor.shutdownNow();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS) || !delegate.awaitTermination(Duration.ofSeconds(5))) {
            throw new AssertionError("Local provider resources did not stop");
        }
    }

    public record Captured(String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .findFirst().map(entry -> entry.getValue().getFirst()).orElse(null);
        }
        @Override public String toString() { return "CapturedProviderRequest[redacted]"; }
    }
}
