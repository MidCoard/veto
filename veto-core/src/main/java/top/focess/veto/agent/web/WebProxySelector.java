package top.focess.veto.agent.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Applies the standard HTTP proxy environment variables to Java's HTTP client. */
public final class WebProxySelector extends ProxySelector {

    private final Proxy httpProxy;
    private final Proxy httpsProxy;
    private final Proxy allProxy;
    private final @NonNull List<@NonNull String> noProxy;

    private WebProxySelector(
            Proxy httpProxy,
            Proxy httpsProxy,
            Proxy allProxy,
            @NonNull List<@NonNull String> noProxy) {
        this.httpProxy = httpProxy;
        this.httpsProxy = httpsProxy;
        this.allProxy = allProxy;
        this.noProxy = noProxy;
    }

    /** Returns the environment selector, or null when no proxy variable is configured. */
    public static ProxySelector fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static ProxySelector fromEnvironment(@NonNull Map<String, String> environment) {
        Proxy http = parseProxy(value(environment, "HTTP_PROXY"));
        Proxy https = parseProxy(value(environment, "HTTPS_PROXY"));
        Proxy all = parseProxy(value(environment, "ALL_PROXY"));
        if (http == null && https == null && all == null) {
            return null;
        }
        return new WebProxySelector(http, https, all, parseNoProxy(value(environment, "NO_PROXY")));
    }

    @Override
    public @NonNull List<@NonNull Proxy> select(@NonNull URI uri) {
        String host = uri.getHost();
        if (host == null || bypasses(host, uri.getPort())) {
            return List.of(Proxy.NO_PROXY);
        }
        Proxy selected =
                switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
                    case "http" -> httpProxy != null ? httpProxy : allProxy;
                    case "https" -> httpsProxy != null ? httpsProxy : allProxy;
                    default -> null;
                };
        return List.of(selected == null ? Proxy.NO_PROXY : selected);
    }

    @Override
    public void connectFailed(
            @NonNull URI uri, @NonNull SocketAddress sa, @NonNull IOException ioe) {
        // The HTTP client reports the original connection failure to the caller.
    }

    private boolean bypasses(@NonNull String host, int port) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        for (String entry : noProxy) {
            if ("*".equals(entry)) {
                return true;
            }
            String candidate = entry;
            int colon = candidate.lastIndexOf(':');
            if (colon > 0 && candidate.indexOf(':') == colon) {
                try {
                    int configuredPort = Integer.parseInt(candidate.substring(colon + 1));
                    if (port != configuredPort) {
                        continue;
                    }
                    candidate = candidate.substring(0, colon);
                } catch (NumberFormatException ignored) {
                    // Treat the complete entry as a hostname when its suffix is not a port.
                }
            }
            if (candidate.startsWith(".")) {
                candidate = candidate.substring(1);
            }
            if (!candidate.isEmpty()
                    && (normalizedHost.equals(candidate)
                            || normalizedHost.endsWith("." + candidate))) {
                return true;
            }
        }
        return false;
    }

    private static String value(@NonNull Map<String, String> environment, @NonNull String name) {
        String value = environment.get(name);
        return value != null ? value : environment.get(name.toLowerCase(Locale.ROOT));
    }

    private static Proxy parseProxy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.contains("://") ? value.strip() : "http://" + value.strip();
        try {
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @NonNull List<@NonNull String> parseNoProxy(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (String entry : value.split(",")) {
            String normalized = entry.strip().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                entries.add(normalized);
            }
        }
        return List.copyOf(entries);
    }
}
