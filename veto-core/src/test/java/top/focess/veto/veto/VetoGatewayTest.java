package top.focess.veto.veto;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.focess.veto.integration.plugins.PluginTestSupport;
import top.focess.veto.observability.AuditLogger;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("initialization.field.uninitialized")
class VetoGatewayTest {

    @Mock private @NonNull LlamaCppBridge llamaCppBridge;
    @Mock private @NonNull AuditLogger auditLogger;

    private @NonNull VetoGatewayConfiguration config;
    private @NonNull VetoGateway vetoGateway;

    @BeforeEach
    void setUp() {
        config = new VetoGatewayConfiguration();
        config.setEnabled(true);
        config.setEnforceStructuralConstraints(true);

        // No plugin manager bound: masking degrades to identity.
        vetoGateway =
                new VetoGateway(
                        config, llamaCppBridge, PluginTestSupport.providerOf(null), auditLogger);
    }

    @Test
    void testProcessOutboundPassesCleanData() {
        String cleanPayload = "{\"action\":\"compile\",\"files\":[\"main.cpp\"]}";
        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(cleanPayload, "dag-1", "req-1", "sandbox");

        assertEquals(VetoGateway.VetoDecision.PASS, result.decision());
        assertTrue(result.isAllowed());
        assertEquals(cleanPayload, result.processedPayload());
    }

    @Test
    void testProcessOutboundPassesCredentialVocabularyWithoutAPath() {
        String cleanPayload = "Summarize this harmless sentence without revealing any credentials.";
        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(cleanPayload, "dag-safe", "req-safe", "sandbox");

        assertEquals(VetoGateway.VetoDecision.PASS, result.decision());
        assertEquals(0, result.redactionCount());
        assertEquals(cleanPayload, result.processedPayload());
    }

    @Test
    void testProcessOutboundWithoutPluginsLeavesThePayloadUnmasked() {
        String payload = "Server configured at 10.0.0.50 with SSH key";
        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(payload, "dag-identity", "req-identity", "sandbox");

        assertEquals(VetoGateway.VetoDecision.PASS, result.decision());
        assertEquals(payload, result.processedPayload());
    }

    @Test
    void testProcessOutboundRedactsIpsThroughThePluginMiddleware() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var gateway = new VetoGateway(config, llamaCppBridge, plugins, auditLogger);
            String payload = "Server configured at 10.0.0.50 with SSH key";
            VetoGateway.VetoResult result =
                    gateway.processOutbound(payload, "dag-2", "req-2", "sandbox");

            assertEquals(VetoGateway.VetoDecision.REDACT, result.decision());
            assertTrue(result.isAllowed()); // REDACT is still allowed (safe to send)
            assertEquals(1, result.redactionCount());
            assertFalse(result.processedPayload().contains("10.0.0.50"));
        }
    }

    @Test
    void testProcessOutboundMasksMultipleSensitiveClassesThroughThePlugin() throws Exception {
        try (var plugins = PluginTestSupport.manager()) {
            var gateway = new VetoGateway(config, llamaCppBridge, plugins, auditLogger);
            String payload =
                    "IP: 192.168.1.1, Email: admin@internal.corp, Key: abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";
            VetoGateway.VetoResult result =
                    gateway.processOutbound(payload, "dag-3", "req-3", "mcp");

            assertEquals(VetoGateway.VetoDecision.REDACT, result.decision());
            // redactionCount counts changed payload lines, not individual rule hits.
            assertEquals(1, result.redactionCount());
            assertFalse(result.processedPayload().contains("192.168.1.1"));
            assertFalse(result.processedPayload().contains("admin@internal.corp"));
            assertFalse(
                    result.processedPayload()
                            .contains("abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"));
        }
    }

    @Test
    void testDisablePassesEverything() {
        VetoGatewayConfiguration disabledConfig = new VetoGatewayConfiguration();
        disabledConfig.setEnabled(false);

        VetoGateway disabledGateway =
                new VetoGateway(
                        disabledConfig,
                        llamaCppBridge,
                        PluginTestSupport.providerOf(null),
                        auditLogger);

        String sensitivePayload = "Secret: my-api-key-12345";
        VetoGateway.VetoResult result =
                disabledGateway.processOutbound(sensitivePayload, "dag-4", "req-4", "sandbox");

        assertEquals(VetoGateway.VetoDecision.PASS, result.decision());
        assertEquals(sensitivePayload, result.processedPayload()); // No redaction
    }

    @Test
    void testBlockResult() {
        VetoGateway.VetoResult blocked = VetoGateway.VetoResult.block("Reason");
        assertEquals(VetoGateway.VetoDecision.BLOCK, blocked.decision());
        assertFalse(blocked.isAllowed());
    }

    @Test
    void testPassResult() {
        VetoGateway.VetoResult passed = VetoGateway.VetoResult.pass("data", "OK");
        assertEquals(VetoGateway.VetoDecision.PASS, passed.decision());
        assertTrue(passed.isAllowed());
    }
}
