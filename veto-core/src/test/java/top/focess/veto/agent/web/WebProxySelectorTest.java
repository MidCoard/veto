package top.focess.veto.agent.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebProxySelectorTest {

    @Test
    void selectsSchemeProxyAndHonorsNoProxy() {
        ProxySelector selector =
                WebProxySelector.fromEnvironment(
                        Map.of(
                                "HTTP_PROXY", "http://proxy.local:8080",
                                "HTTPS_PROXY", "http://secure-proxy.local:8443",
                                "NO_PROXY", "localhost,.example.test"));
        if (selector == null) {
            throw new AssertionError("configured environment must create a proxy selector");
        }

        Proxy selected = selector.select(URI.create("https://duckduckgo.com/")).getFirst();
        InetSocketAddress address = (InetSocketAddress) selected.address();
        assertEquals("secure-proxy.local", address.getHostString());
        assertEquals(8443, address.getPort());
        assertEquals(
                Proxy.NO_PROXY,
                selector.select(URI.create("https://docs.example.test/")).getFirst());
    }

    @Test
    void returnsNullWithoutConfiguredProxy() {
        assertNull(WebProxySelector.fromEnvironment(Map.of()));
    }
}
