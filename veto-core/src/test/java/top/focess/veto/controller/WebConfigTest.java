package top.focess.veto.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import top.focess.veto.bus.BusConfiguration;

class WebConfigTest {
    @Test
    void restUsesTheConfiguredBrowserOriginsWithoutAllowingOtherSites() {
        var connections = new BusConfiguration();
        connections.getWebsocket().setAllowedOriginPatterns(List.of("https://ui.example.test"));
        var registry =
                new CorsRegistry() {
                    void verify() {
                        var cors = getCorsConfigurations().get("/api/**");
                        if (cors == null) throw new AssertionError("Missing API CORS mapping");
                        assertEquals(
                                "https://ui.example.test",
                                cors.checkOrigin("https://ui.example.test"));
                        assertNull(cors.checkOrigin("https://other.example.test"));
                        assertNull(cors.checkOrigin("http://localhost:5177"));
                        assertEquals(
                                List.of("X-Veto-Session-Token"),
                                cors.checkHeaders(List.of("X-Veto-Session-Token")));
                    }
                };
        new WebConfig(connections).addCorsMappings(registry);
        registry.verify();
    }
}
