package top.focess.veto.agent.capability;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolErrors;
import top.focess.veto.agent.tool.ToolExecutionException;
import top.focess.veto.agent.web.FetchedPage;
import top.focess.veto.agent.web.SearchOptions;
import top.focess.veto.agent.web.SearchProvider;
import top.focess.veto.agent.web.SearchResult;
import top.focess.veto.agent.web.WebProxySelector;
import top.focess.veto.agent.web.WebReader;

@Component
public final class NetworkEgressCapabilityImpl implements NetworkEgressCapability {
    private final @NonNull SearchProvider provider;
    private final @NonNull WebReader reader;

    private static final int MAX_REDIRECTS = 5;

    private static final @NonNull String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0 Safari/537.36";

    private final @NonNull HttpClient httpClient;
    private final int timeoutSeconds;
    private final int maxChars;
    private final boolean allowPrivateAddresses;

    @Autowired
    public NetworkEgressCapabilityImpl(
            @NonNull SearchProvider provider,
            @NonNull WebReader reader,
            @Value("${veto.webfetch.fetch.timeout-seconds}") int timeoutSeconds,
            @Value("${veto.webfetch.fetch.max-chars}") int maxChars,
            @Value("${veto.webfetch.fetch.allow-private-addresses}")
                    boolean allowPrivateAddresses) {
        this.provider = provider;
        this.reader = reader;
        this.timeoutSeconds = timeoutSeconds;
        this.maxChars = maxChars;
        if (timeoutSeconds <= 0 || maxChars <= 0) {
            throw new IllegalArgumentException(
                    "Web reading fetch timeout-seconds and max-chars must both be positive");
        }
        this.allowPrivateAddresses = allowPrivateAddresses;
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 15)))
                        .followRedirects(HttpClient.Redirect.NEVER);
        ProxySelector proxySelector = WebProxySelector.fromEnvironment();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }
        this.httpClient = builder.build();
    }

    @Override
    public @NonNull String searchProviderName() {
        return provider.name();
    }

    @Override
    public @NonNull List<SearchResult> search(@NonNull String query, @NonNull SearchOptions options)
            throws Exception {
        CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, "web_search");
        return provider.search(query, options);
    }

    @Override
    public @NonNull WebReadCapability openReader(@NonNull URI uri) {
        var parent = CapabilityAccess.require(ToolCapability.NETWORK_EGRESS, "web_fetch");
        Object approvedUrl = parent.executionPermit().call().args().get("url");
        if (!(approvedUrl instanceof String value) || !uri.equals(URI.create(value.trim()))) {
            throw new SecurityException("Reader URL differs from the approved destination.");
        }
        return new WebReadCapability(deadline -> fetchDocument(uri, deadline), parent, reader);
    }

    private @NonNull FetchedPage fetchDocument(@NonNull URI uri, long readerDeadline) {
        long deadline =
                Math.min(
                        readerDeadline,
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds));
        String validationError = validateUri(uri);
        if (validationError != null) {
            return ToolErrors.failure(validationError);
        }
        try {
            URI current = uri;
            for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(current)
                                .timeout(Duration.ofNanos(remainingNanos(deadline)))
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "text/html, application/json, text/plain, */*")
                                .GET()
                                .build();
                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (isRedirect(status)) {
                    closeBody(response);
                    String location = response.headers().firstValue("Location").orElse("");
                    if (location.isBlank()) {
                        return ToolErrors.failure(
                                "HTTP " + status + " without Location for " + current);
                    }
                    if (redirectCount == MAX_REDIRECTS) {
                        return ToolErrors.failure("too many redirects for " + uri);
                    }
                    URI next;
                    try {
                        next = current.resolve(location);
                    } catch (IllegalArgumentException e) {
                        return ToolErrors.failure("invalid redirect target from " + current);
                    }
                    String redirectError = validateUri(next);
                    if (redirectError != null) {
                        return ToolErrors.failure("redirect rejected: " + redirectError);
                    }
                    if (!sameOrigin(uri, next)) {
                        return ToolErrors.failure(
                                "cross-origin redirect requires a separately approved tool call: "
                                        + next);
                    }
                    current = next;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    closeBody(response);
                    return ToolErrors.failure("HTTP " + status + " for " + current);
                }
                String contentType =
                        response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                BoundedBody bounded;
                try (InputStream body = response.body()) {
                    bounded = readBounded(body, deadline);
                }
                String content = new String(bounded.bytes(), StandardCharsets.UTF_8);
                return new FetchedPage(
                        current, status, contentType, content, bounded.truncated(), maxChars);
            }
            return ToolErrors.failure("too many redirects for " + uri);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            return ToolErrors.failure("timed out after " + timeoutSeconds + "s fetching " + uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolErrors.failure("fetch interrupted for " + uri);
        } catch (Exception e) {
            return ToolErrors.failure(
                    "could not fetch " + uri + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Closes an unconsumed response body so redirects and error pages never enter memory. */
    private static void closeBody(@NonNull HttpResponse<InputStream> response) throws IOException {
        response.body().close();
    }

    private @NonNull BoundedBody readBounded(@NonNull InputStream body, long deadline)
            throws IOException, InterruptedException {
        FutureTask<@NonNull BoundedBody> read = new FutureTask<>(() -> readBounded(body));
        Thread.ofVirtual().start(read);
        try {
            return read.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new HttpTimeoutException("Response body deadline exceeded");
        } catch (ExecutionException e) {
            throw new IOException("Could not read response body", e.getCause());
        } finally {
            read.cancel(true);
        }
    }

    private static long remainingNanos(long deadline) throws HttpTimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new HttpTimeoutException("Fetch deadline exceeded");
        }
        return remaining;
    }

    private @NonNull BoundedBody readBounded(@NonNull InputStream body) throws IOException {
        long requested = Math.max(1L, (long) maxChars * 4L + 1L);
        int byteLimit = (int) Math.min(Integer.MAX_VALUE, requested);
        byte[] bytes = body.readNBytes(byteLimit);
        if (bytes.length == byteLimit) {
            int kept = Math.max(0, byteLimit - 1);
            return new BoundedBody(Arrays.copyOf(bytes, kept), true);
        }
        return new BoundedBody(bytes, false);
    }

    private record BoundedBody(byte @NonNull [] bytes, boolean truncated) {}

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private String validateUri(@NonNull URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "only http/https URLs are allowed (got scheme: " + scheme + ")";
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return "URL must contain a host";
        }
        if (uri.getUserInfo() != null) {
            return "URLs containing credentials are not allowed";
        }
        if (!allowPrivateAddresses) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (isPrivateAddress(address)) {
                        return "private, loopback, link-local, or multicast destinations are not"
                                + " allowed";
                    }
                }
            } catch (UnknownHostException e) {
                return "host could not be resolved";
            }
        }
        return null;
    }

    private static boolean isPrivateAddress(@NonNull InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address) {
            byte[] raw = address.getAddress();
            return raw.length > 0 && (raw[0] & 0xfe) == 0xfc;
        }
        return false;
    }

    private static boolean sameOrigin(@NonNull URI first, @NonNull URI second) {
        return first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(@NonNull URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** Converts HTML to clean readable text (title + main body; scripts/styles/nav removed). */
}
