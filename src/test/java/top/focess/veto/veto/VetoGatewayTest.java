package top.focess.veto.veto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.focess.veto.observability.AuditLogger;

@ExtendWith(MockitoExtension.class)
class VetoGatewayTest {

  @Mock private LlamaCppBridge llamaCppBridge;
  @Mock private GBNFGrammarEngine grammarEngine;
  @Mock private AuditLogger auditLogger;

  private VetoGatewayConfiguration config;
  private SemanticRedactor semanticRedactor;
  private VetoGateway vetoGateway;

  @BeforeEach
  void setUp() {
    config = new VetoGatewayConfiguration();
    config.setEnabled(true);
    config.setInterceptAllOutbound(true);
    config.setRedactSecrets(true);
    config.setEnforceStructuralConstraints(true);

    semanticRedactor = new SemanticRedactor();
    vetoGateway =
        new VetoGateway(config, llamaCppBridge, semanticRedactor, grammarEngine, auditLogger);
  }

  @Test
  void testProcessOutboundPassesCleanData() {
    String cleanPayload = "{\"action\":\"compile\",\"files\":[\"main.cpp\"]}";
    VetoGateway.VetoResult result =
        vetoGateway.processOutbound(cleanPayload, "dag-1", "req-1", "C6-Sandbox");

    assertEquals(VetoGateway.VetoDecision.PASS, result.decision());
    assertTrue(result.isAllowed());
    assertEquals(cleanPayload, result.processedPayload());
  }

  @Test
  void testProcessOutboundRedactsIPs() {
    String payload = "Server configured at 10.0.0.50 with SSH key";
    VetoGateway.VetoResult result =
        vetoGateway.processOutbound(payload, "dag-2", "req-2", "C6-Sandbox");

    assertEquals(VetoGateway.VetoDecision.REDACT, result.decision());
    assertTrue(result.isAllowed()); // REDACT is still allowed (safe to send)
    assertTrue(result.redactionCount() > 0);
    assertFalse(result.processedPayload().contains("10.0.0.50"));
  }

  @Test
  void testProcessOutboundWithMultipleRedactions() {
    String payload =
        "IP: 192.168.1.1, Email: admin@internal.corp, Key: abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";
    VetoGateway.VetoResult result =
        vetoGateway.processOutbound(payload, "dag-3", "req-3", "C4-MCP");

    assertEquals(VetoGateway.VetoDecision.REDACT, result.decision());
    assertTrue(result.redactionCount() >= 3);
    assertFalse(result.processedPayload().contains("192.168.1.1"));
    assertFalse(result.processedPayload().contains("admin@internal.corp"));
  }

  @Test
  void testDisablePassesEverything() {
    VetoGatewayConfiguration disabledConfig = new VetoGatewayConfiguration();
    disabledConfig.setEnabled(false);

    VetoGateway disabledGateway =
        new VetoGateway(
            disabledConfig, llamaCppBridge, semanticRedactor, grammarEngine, auditLogger);

    String sensitivePayload = "Secret: my-api-key-12345";
    VetoGateway.VetoResult result =
        disabledGateway.processOutbound(sensitivePayload, "dag-4", "req-4", "C6-Sandbox");

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
