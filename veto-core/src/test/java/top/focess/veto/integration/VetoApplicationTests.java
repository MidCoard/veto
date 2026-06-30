package top.focess.veto.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import top.focess.veto.VetoApplication;
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.agent.mcp.McpEngineImpl;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.memory.MemoryCaptureService;
import top.focess.veto.observability.AuditLogger;
import top.focess.veto.veto.GBNFGrammarEngine;
import top.focess.veto.veto.LlamaCppBridge;
import top.focess.veto.veto.SemanticRedactor;
import top.focess.veto.veto.VetoGateway;
import top.focess.veto.veto.VetoGatewayConfiguration;

/**
 * Full Spring Boot integration tests for Project Veto. Verifies context loading, DI wiring, and the
 * core Veto pipeline.
 */
@SpringBootTest(
        classes = VetoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "veto.veto-gateway.enabled=true",
            "veto.veto-gateway.redact-secrets=true",
            "veto.veto-gateway.enforce-structural-constraints=true",
            "veto.vault.master-key-env=veto.test.key",
            "veto.observability.encryption-enabled=false"
        })
class VetoApplicationTests {

    @LocalServerPort private int port;

    @Autowired private ApplicationContext context;

    @Autowired private VetoGateway vetoGateway;

    @Autowired private SemanticRedactor semanticRedactor;

    @Autowired private GBNFGrammarEngine grammarEngine;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired private AuditLogger auditLogger;

    public VetoApplicationTests() {
        // Don't throw exceptions on non-2xx responses - we test error codes
        restTemplate.setErrorHandler(
                new org.springframework.web.client.DefaultResponseErrorHandler() {
                    @Override
                    public boolean hasError(
                            org.springframework.http.client.ClientHttpResponse response) {
                        return false;
                    }
                });
    }

    @Test
    void contextLoads() {
        assertNotNull(context, "Application context should load");
        assertNotNull(vetoGateway, "VetoGateway should be injected");
        assertNotNull(semanticRedactor, "SemanticRedactor should be injected");
        assertNotNull(grammarEngine, "GBNFGrammarEngine should be injected");
    }

    @Test
    void mcpEngineImplIsActive() {
        McpEngine engine = context.getBean(McpEngine.class);
        assertNotNull(engine, "McpEngine bean should exist");
        assertInstanceOf(
                McpEngineImpl.class, engine, "McpEngineImpl should win over DefaultMcpEngine");
    }

    @Test
    void deltaBrokerIsInjected() {
        DeltaBroker broker = context.getBean(DeltaBroker.class);
        assertNotNull(broker, "DeltaBroker should be injected as a Spring bean");
    }

    @Test
    void memoryCaptureServiceIsInjected() {
        MemoryCaptureService capture = context.getBean(MemoryCaptureService.class);
        assertNotNull(capture, "MemoryCaptureService should be injected as a Spring bean");
    }

    @Test
    void vetoGatewayProcessesCleanPayload() {
        String cleanPayload = "{\"action\":\"compile\",\"files\":[\"main.cpp\"]}";
        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(cleanPayload, "it-dag-1", "it-req-1", "IT-Integration");

        assertNotNull(result, "Result should not be null");
        assertTrue(result.isAllowed(), "Clean payload should be allowed");
        assertEquals(
                cleanPayload,
                result.processedPayload(),
                "Clean payload should pass through unchanged");
    }

    @Test
    void vetoGatewayRedactsSensitiveData() {
        String sensitivePayload =
                "Server: 192.168.1.100, API key: abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";
        VetoGateway.VetoResult result =
                vetoGateway.processOutbound(
                        sensitivePayload, "it-dag-2", "it-req-2", "IT-Integration");

        assertNotNull(result, "Result should not be null");
        assertTrue(result.redactionCount() > 0, "Sensitive data should trigger redactions");
        assertFalse(
                result.processedPayload().contains("192.168.1.100"),
                "Redacted payload should not contain original IP");
        assertFalse(
                result.processedPayload().contains("abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"),
                "Redacted payload should not contain original API key");
    }

    @Test
    void vetoGatewayDisabledState() {
        VetoGatewayConfiguration disabledConfig = new VetoGatewayConfiguration();
        disabledConfig.setEnabled(false);

        VetoGateway disabledGateway =
                new VetoGateway(
                        disabledConfig,
                        context.getBean(LlamaCppBridge.class),
                        semanticRedactor,
                        grammarEngine,
                        context.getBean(AuditLogger.class));

        String sensitive = "Secret: my-api-key";
        VetoGateway.VetoResult result =
                disabledGateway.processOutbound(sensitive, "it-dag-3", "it-req-3", "IT-Disabled");

        assertEquals(
                VetoGateway.VetoDecision.PASS,
                result.decision(),
                "Disabled gateway should always pass");
        assertEquals(
                sensitive, result.processedPayload(), "Disabled gateway should not modify payload");
    }

    @Test
    void vetoGatewayStats() {
        vetoGateway.processOutbound("clean data", "stats-1", "stats-1", "IT");
        vetoGateway.processOutbound("IP: 10.0.0.1", "stats-2", "stats-2", "IT");
        vetoGateway.processOutbound("more clean", "stats-3", "stats-3", "IT");
        vetoGateway.processOutbound("Email: test@example.external.com", "stats-4", "stats-4", "IT");

        assertTrue(vetoGateway.getTotalVetoes() >= 2, "Should have vetoed at least 2 payloads");
        assertTrue(vetoGateway.getTotalRedactions() >= 2, "Should have at least 2 redactions");
    }

    @Test
    void semanticRedactorPatternDetection() {
        String multiSecret =
                "IP: 10.0.0.50, Email: admin@internal.corp, SSH: -----BEGIN OPENSSH PRIVATE KEY-----test-----END OPENSSH PRIVATE KEY-----";
        SemanticRedactor.RedactionReport report = semanticRedactor.deterministicRedact(multiSecret);

        assertTrue(report.wasModified(), "Payload with multiple secrets should be modified");
        assertTrue(report.getTotalRedactions() >= 3, "Should detect at least 3 types of secrets");
    }

    @Test
    void grammarEngineLoaded() {
        String defaultGrammar = grammarEngine.getDefaultVetoGrammar();
        assertNotNull(defaultGrammar);
        assertTrue(
                defaultGrammar.contains("veto_decision"),
                "Default grammar should define veto_decision");
        assertTrue(defaultGrammar.contains("\"pass\""), "Default grammar should allow pass");
        assertTrue(defaultGrammar.contains("\"block\""), "Default grammar should allow block");

        String codeGrammar = grammarEngine.getCodeConstraintGrammar();
        assertNotNull(codeGrammar);
        assertTrue(codeGrammar.contains("violations"), "Code grammar should define violations");
    }

    @Test
    void restEndpointVetoStatus() {
        String url = "http://localhost:" + port + "/api/veto/status";
        ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "Status endpoint should return 200");
        assertNotNull(response.getBody());
        assertEquals("ok", response.getBody().get("status"));
        assertNotNull(response.getBody().get("totalVetoes"));
        assertNotNull(response.getBody().get("enabled"));
    }

    @Test
    void restEndpointVetoProcess() {
        String url = "http://localhost:" + port + "/api/veto/process";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(Map.of("payload", "Test IP: 10.0.0.55 for processing"), headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "Process endpoint should return 200");
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("decision"));
        assertTrue(response.getBody().containsKey("processedPayload"));
    }

    @Test
    void restEndpointVetoProcessRejectsEmptyPayload() {
        String url = "http://localhost:" + port + "/api/veto/process";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("payload", ""), headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        assertEquals(
                HttpStatus.BAD_REQUEST,
                response.getStatusCode(),
                "Empty payload should return 400");
    }

    @Test
    void restEndpointTaskLifecycle() {
        String createUrl = "http://localhost:" + port + "/api/tasks";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> createRequest =
                new HttpEntity<>(
                        Map.of(
                                "taskType",
                                "integration_test",
                                "parameters",
                                Map.of("key", "value")),
                        headers);

        ResponseEntity<Map> createResponse =
                restTemplate.postForEntity(createUrl, createRequest, Map.class);
        assertEquals(HttpStatus.OK, createResponse.getStatusCode());
        assertNotNull(createResponse.getBody());
        String taskId = (String) createResponse.getBody().get("id");
        assertNotNull(taskId, "Created task should have an ID");

        String getUrl = "http://localhost:" + port + "/api/tasks/" + taskId;
        ResponseEntity<Map> getResponse = restTemplate.getForEntity(getUrl, Map.class);
        assertEquals(HttpStatus.OK, getResponse.getStatusCode());
        assertNotNull(getResponse.getBody());
        assertEquals(taskId, getResponse.getBody().get("id"));

        ResponseEntity<Map> listResponse = restTemplate.getForEntity(createUrl, Map.class);
        assertEquals(HttpStatus.OK, listResponse.getStatusCode());

        ResponseEntity<Map> deleteResponse =
                restTemplate.exchange(getUrl, HttpMethod.DELETE, null, Map.class);
        assertEquals(HttpStatus.OK, deleteResponse.getStatusCode());

        ResponseEntity<Map> notFoundResponse =
                restTemplate.getForEntity(
                        "http://localhost:" + port + "/api/tasks/nonexistent", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, notFoundResponse.getStatusCode());
    }
}
