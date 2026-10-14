package top.focess.veto.integration;

import static org.apache.tomcat.websocket.Constants.BLOCKING_SEND_TIMEOUT_PROPERTY;
import static org.apache.tomcat.websocket.Constants.READ_IDLE_TIMEOUT_MS;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.tomcat.websocket.WsSession;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import top.focess.veto.VetoApplication;
import top.focess.veto.agent.tool.ToolEngine;
import top.focess.veto.agent.tool.ToolEngineImpl;
import top.focess.veto.bus.VetoWebSocketAuthInterceptor;
import top.focess.veto.bus.VetoWebSocketHandler;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.contract.Version;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.memory.TurnLogService;
import top.focess.veto.observability.AuditLogger;
import top.focess.veto.session.AgentSessions;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.UserRegistry;
import top.focess.veto.veto.GBNFGrammarEngine;
import top.focess.veto.veto.LlamaCppBridge;
import top.focess.veto.veto.SlmConfiguration;
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
            "veto.veto-gateway.enforce-structural-constraints=true",
            "veto.slm.enabled=true",
            "veto.vault.master-key-env=veto.test.key",
            "veto.observability.encryption-enabled=false"
        })
@SuppressWarnings("initialization.fields.uninitialized")
class VetoApplicationTests {

    private static final @NonNull ParameterizedTypeReference<Map<String, Object>> MAP_RESPONSE =
            new ParameterizedTypeReference<>() {};

    @LocalServerPort private int port;

    @Autowired private @NonNull ApplicationContext context;

    @Autowired private @NonNull VetoGateway vetoGateway;

    @Autowired private @NonNull GBNFGrammarEngine grammarEngine;

    @Autowired private @NonNull LlamaCppBridge llamaCppBridge;

    @Autowired private @NonNull SlmConfiguration slmConfiguration;

    private final @NonNull RestTemplate restTemplate = new RestTemplate();

    @Autowired private @NonNull AuditLogger auditLogger;

    public VetoApplicationTests() {
        // Don't throw exceptions on non-2xx responses - we test error codes
        restTemplate.setErrorHandler(
                new DefaultResponseErrorHandler() {
                    @Override
                    public boolean hasError(ClientHttpResponse response) {
                        return false;
                    }
                });
    }

    @Test
    void contextLoads() {
        assertNotNull(context, "Application context should load");
        assertNotNull(vetoGateway, "VetoGateway should be injected");
        assertNotNull(grammarEngine, "GBNFGrammarEngine should be injected");
    }

    @Test
    void mcpEngineImplIsActive() {
        ToolEngine engine = context.getBean(ToolEngine.class);
        assertNotNull(engine, "ToolEngine bean should exist");
        assertInstanceOf(
                ToolEngineImpl.class,
                engine,
                "The application must use the production tool engine");
    }

    @Test
    void runtimeSessionsAreInjected() {
        AgentSessions sessions = context.getBean(AgentSessions.class);
        assertNotNull(sessions, "Runtime session owner should be a Spring bean");
    }

    @Test
    void turnLogServiceIsInjected() {
        TurnLogService turnLog = context.getBean(TurnLogService.class);
        assertNotNull(turnLog, "TurnLogService should be injected as a Spring bean");
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
                        context.getBean(PluginManager.class),
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
    void outboundMaskingUsesThePluginObservationMiddleware() {
        String multiSecret =
                "IP: 10.0.0.50, Email: admin@internal.corp, SSH: -----BEGIN OPENSSH PRIVATE"
                        + " KEY-----test-----END OPENSSH PRIVATE KEY-----";
        var result = vetoGateway.processOutbound(multiSecret, "mask-1", "mask-1", "IT");

        assertEquals(VetoGateway.VetoDecision.REDACT, result.decision());
        assertFalse(result.processedPayload().contains("10.0.0.50"));
        assertFalse(result.processedPayload().contains("admin@internal.corp"));
        assertFalse(result.processedPayload().contains("PRIVATE KEY-----test"));
    }

    @Test
    void grammarEngineLoaded() {
        String defaultGrammar = grammarEngine.getDefaultVetoGrammar();
        assertNotNull(defaultGrammar);
        assertTrue(
                defaultGrammar.contains("veto_decision"),
                "Default grammar should define veto_decision");
        assertTrue(
                defaultGrammar.contains("\"\\\"pass\\\"\""),
                "Default grammar should allow the JSON string pass");
        assertTrue(
                defaultGrammar.contains("\"\\\"block\\\"\""),
                "Default grammar should allow the JSON string block");

        String codeGrammar = grammarEngine.getCodeConstraintGrammar();
        assertNotNull(codeGrammar);
        assertTrue(codeGrammar.contains("violations"), "Code grammar should define violations");
    }

    @Test
    void localSlmLoadsWhenTheModelArtifactIsPresent() {
        Path model = slmConfiguration.resolvePath(slmConfiguration.getModelPath());
        Assumptions.assumeTrue(Files.isRegularFile(model), "local GGUF model is not installed");
        assertTrue(
                llamaCppBridge.isAvailable(),
                "llama.cpp must load the configured local model when the artifact is present");
    }

    private @NonNull HttpHeaders authenticatedHeaders() {
        var user =
                context.getBean(UserRegistry.class)
                        .create("http-security-" + UUID.randomUUID(), "test-password", "USER");
        var headers = new HttpHeaders();
        headers.set(
                "X-Veto-Session-Token",
                context.getBean(LoginSessionManager.class)
                        .createLoginSession(user.getUserId(), user.getUsername()));
        return headers;
    }

    @Test
    void securityBoundaryProtectsRestAndPreservesBrowserWebSocketHandshake() throws Exception {
        String base = "http://localhost:" + port;
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                restTemplate
                        .exchange(base + "/api/veto/status", HttpMethod.GET, null, MAP_RESPONSE)
                        .getStatusCode());
        assertEquals(
                HttpStatus.OK,
                restTemplate
                        .exchange(base + "/api/auth/status", HttpMethod.GET, null, MAP_RESPONSE)
                        .getStatusCode());
        var headers = authenticatedHeaders();
        String token = headers.getFirst("X-Veto-Session-Token");
        if (token == null) throw new AssertionError("Missing test session token");
        try (var client = HttpClient.newHttpClient()) {
            var socket =
                    Objects.requireNonNull(
                            client.newWebSocketBuilder()
                                    .header("Origin", "http://localhost:5177")
                                    .buildAsync(
                                            URI.create(
                                                    "ws://localhost:"
                                                            + port
                                                            + "/ws/veto/bus?token="
                                                            + token),
                                            new WebSocket.Listener() {})
                                    .get(10, TimeUnit.SECONDS));
            try {
                assertFalse(socket.isOutputClosed());
                context.getBean(LoginSessionManager.class).revokeToken(token);
                assertEquals(
                        HttpStatus.UNAUTHORIZED,
                        restTemplate
                                .exchange(
                                        base + "/api/veto/status",
                                        HttpMethod.GET,
                                        new HttpEntity<>(headers),
                                        MAP_RESPONSE)
                                .getStatusCode());
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void websocketReadTimeoutTracksHeartbeatsAndExpiresDespiteServerOutput() throws Exception {
        var token = authenticatedHeaders().getFirst("X-Veto-Session-Token");
        if (token == null) throw new AssertionError("Missing test token");
        var messages = new LinkedBlockingQueue<@NonNull String>();
        var closed = new CompletableFuture<Integer>();
        try (var client = HttpClient.newHttpClient()) {
            var socket =
                    Objects.requireNonNull(
                            client.newWebSocketBuilder()
                                    .header("Origin", "http://localhost:5177")
                                    .buildAsync(
                                            URI.create(
                                                    "ws://localhost:"
                                                            + port
                                                            + "/ws/veto/bus?token="
                                                            + token),
                                            new WebSocket.Listener() {
                                                @Override
                                                public @NonNull CompletionStage<?> onText(
                                                        @NonNull WebSocket webSocket,
                                                        @NonNull CharSequence data,
                                                        boolean last) {
                                                    assertTrue(last);
                                                    assertTrue(messages.offer(data.toString()));
                                                    webSocket.request(1);
                                                    return CompletableFuture.completedFuture(null);
                                                }

                                                @Override
                                                public @NonNull CompletionStage<?> onClose(
                                                        @NonNull WebSocket webSocket,
                                                        int statusCode,
                                                        @NonNull String reason) {
                                                    assertTrue(closed.complete(statusCode));
                                                    return CompletableFuture.completedFuture(null);
                                                }
                                            })
                                    .get(10, TimeUnit.SECONDS));
            try {
                socket.sendText(
                                FrameCodec.encodeString(
                                        new Frame.Hello(
                                                Frame.PROTOCOL_VERSION, 1, Version.UNKNOWN, "")),
                                true)
                        .get(5, TimeUnit.SECONDS);
                assertInstanceOf(Frame.Welcome.class, nextWebSocketFrame(messages));
                var session = nativeWebSocket(token);
                assertEquals(90_000L, session.getUserProperties().get(READ_IDLE_TIMEOUT_MS));
                assertEquals(
                        10_000L, session.getUserProperties().get(BLOCKING_SEND_TIMEOUT_PROPERTY));
                long oldRead = System.currentTimeMillis() - 80_000;
                ReflectionTestUtils.setField(session, "lastActiveRead", oldRead);
                socket.sendText(FrameCodec.encodeString(new Frame.Heartbeat()), true)
                        .get(5, TimeUnit.SECONDS);
                assertInstanceOf(Frame.HeartbeatAck.class, nextWebSocketFrame(messages));
                var refreshed = ReflectionTestUtils.getField(session, "lastActiveRead");
                assertTrue(refreshed instanceof Long timestamp && timestamp > oldRead);
                assertNull(ReflectionTestUtils.invokeMethod(session, "checkExpiration"));
                assertTrue(session.isOpen());

                // Age the real container timestamp instead of adding test-only timeout
                // configuration.
                ReflectionTestUtils.setField(
                        session, "lastActiveRead", System.currentTimeMillis() - 91_000);
                session.getBasicRemote()
                        .sendText(FrameCodec.encodeString(new Frame.HeartbeatAck(Instant.now())));
                assertInstanceOf(Frame.HeartbeatAck.class, nextWebSocketFrame(messages));
                assertNull(ReflectionTestUtils.invokeMethod(session, "checkExpiration"));
                assertEquals(1001, closed.get(5, TimeUnit.SECONDS));
                assertFalse(session.isOpen());
                assertThrows(AssertionError.class, () -> nativeWebSocket(token));
            } finally {
                socket.abort();
            }
        }
    }

    private static Frame nextWebSocketFrame(@NonNull LinkedBlockingQueue<@NonNull String> messages)
            throws InterruptedException {
        var payload = messages.poll(5, TimeUnit.SECONDS);
        if (payload == null) throw new AssertionError("Missing server frame");
        return FrameCodec.decode(payload);
    }

    private @NonNull WsSession nativeWebSocket(@NonNull String token) {
        var value =
                ReflectionTestUtils.getField(
                        context.getBean(VetoWebSocketHandler.class), "connections");
        if (!(value instanceof Map<?, ?> connections))
            throw new AssertionError("Missing connections");
        for (var connection : connections.values()) {
            if (connection == null) continue;
            var candidate = ReflectionTestUtils.getField(connection, "socket");
            if (candidate instanceof WebSocketSession socket
                    && token.equals(
                            socket.getAttributes()
                                    .get(VetoWebSocketAuthInterceptor.LOGIN_TOKEN_ATTRIBUTE))) {
                if (socket instanceof NativeWebSocketSession nativeSocket
                        && nativeSocket.getNativeSession() instanceof WsSession session)
                    return session;
            }
        }
        throw new AssertionError("Missing authenticated Tomcat session");
    }

    @Test
    void restEndpointVetoStatus() {
        String url = "http://localhost:" + port + "/api/veto/status";
        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        new HttpEntity<>(authenticatedHeaders()),
                        MAP_RESPONSE);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "Status endpoint should return 200");
        Map<String, Object> body = requireBody(response);
        assertEquals("ok", requireMapValue(body, "status"));
        requireMapValue(body, "totalVetoes");
        requireMapValue(body, "enabled");
    }

    @Test
    void restEndpointVetoProcess() {
        String url = "http://localhost:" + port + "/api/veto/process";

        HttpHeaders headers = authenticatedHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(Map.of("payload", "Test IP: 10.0.0.55 for processing"), headers);

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(url, HttpMethod.POST, request, MAP_RESPONSE);

        assertEquals(HttpStatus.OK, response.getStatusCode(), "Process endpoint should return 200");
        Map<String, Object> body = requireBody(response);
        assertTrue(body.containsKey("decision"));
        assertTrue(body.containsKey("processedPayload"));
    }

    @Test
    void restEndpointVetoProcessRejectsEmptyPayload() {
        String url = "http://localhost:" + port + "/api/veto/process";

        HttpHeaders headers = authenticatedHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of("payload", ""), headers);

        ResponseEntity<Map<String, Object>> response =
                restTemplate.exchange(url, HttpMethod.POST, request, MAP_RESPONSE);

        assertEquals(
                HttpStatus.BAD_REQUEST,
                response.getStatusCode(),
                "Empty payload should return 400");
    }

    @Test
    void restAcceptsNullOptionalVetoFields() {
        HttpHeaders headers = authenticatedHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request =
                new HttpEntity<>(
                        "{\"payload\":\"hello\",\"dagPayloadId\":null,\"requestId\":null,\"componentSource\":null}",
                        headers);
        assertEquals(
                HttpStatus.OK,
                restTemplate
                        .exchange(
                                "http://localhost:" + port + "/api/veto/process",
                                HttpMethod.POST,
                                request,
                                MAP_RESPONSE)
                        .getStatusCode());
    }

    @Test
    void restRejectsMalformedJsonAtTheBoundary() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var sessions = context.getBean(LoginSessionManager.class);
        var boundaryUser =
                context.getBean(UserRegistry.class)
                        .create("json-boundary-owner", "test-password", "USER");
        headers.set(
                "X-Veto-Session-Token",
                sessions.createLoginSession(boundaryUser.getUserId(), boundaryUser.getUsername()));
        for (String path : List.of("/api/auth/setup", "/api/auth/login")) {
            HttpEntity<String> request =
                    new HttpEntity<>("{\"username\":42,\"password\":\"password123\"}", headers);
            assertEquals(
                    HttpStatus.BAD_REQUEST,
                    restTemplate
                            .exchange(
                                    "http://localhost:" + port + path,
                                    HttpMethod.POST,
                                    request,
                                    MAP_RESPONSE)
                            .getStatusCode());
        }
        for (String path : List.of("/api/veto/process", "/api/veto/check")) {
            for (String body :
                    List.of(
                            "{}",
                            "{\"payload\":null}",
                            "{\"payload\":42}",
                            "{\"payload\":true}",
                            "{\"payload\":{}}",
                            "{\"payload\":\"first\",\"payload\":\"second\"}",
                            "{\"payload\":\"first\"} {}")) {
                HttpEntity<String> request = new HttpEntity<>(body, headers);
                assertEquals(
                        HttpStatus.BAD_REQUEST,
                        restTemplate
                                .exchange(
                                        "http://localhost:" + port + path,
                                        HttpMethod.POST,
                                        request,
                                        MAP_RESPONSE)
                                .getStatusCode(),
                        body);
            }
        }
    }

    @Test
    void corsAllowsLocalUiToCallTheConfiguredBackendPort()
            throws IOException, InterruptedException {
        String origin = "http://localhost:5173";
        String url = "http://localhost:" + port + "/api/auth/status";
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .header(
                                "Access-Control-Request-Headers",
                                "X-Veto-Session-Token, Content-Type, Accept-Language")
                        .build();

        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpStatus.OK.value(), response.statusCode());
        assertEquals(
                origin, response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertTrue(
                response.headers()
                        .firstValue("Access-Control-Allow-Headers")
                        .orElseThrow()
                        .contains("X-Veto-Session-Token"));
    }

    private static <T extends @NonNull Object> @NonNull T requireBody(
            @NonNull ResponseEntity<T> response) {
        T body = response.getBody();
        if (body == null) throw new AssertionError("Response body should not be null");
        return body;
    }

    private static @NonNull Object requireMapValue(
            @NonNull Map<String, Object> body, @NonNull String key) {
        Object value = body.get(key);
        if (value == null) throw new AssertionError("Response field should not be null: " + key);
        return value;
    }
}
