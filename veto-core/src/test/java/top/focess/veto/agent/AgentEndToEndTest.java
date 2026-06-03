package top.focess.veto.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import top.focess.veto.llm.core.*;
import top.focess.veto.vault.*;

/**
 * End-to-end test: full login flow → credential binding → agent → LLM call.
 */
@SpringBootTest
@ActiveProfiles("local")
class AgentEndToEndTest {

    // Safe to expose in test code — these are local test credentials
    private static final String TEST_USER = "test-user";
    private static final String TEST_PASSWORD = "test-password-123";

    @Autowired
    private UniformLLMCaller caller;
    @Autowired
    private CredentialVault vault;
    @Autowired
    private VaultKeyManager vaultKeyManager;
    @Autowired
    private UserRegistry userRegistry;

    @Value("${veto.test.deepseek.api-key:}")
    private String apiKey;

    @Value("${veto.test.deepseek.model:deepseek-v4-pro}")
    private String model;

    @BeforeEach
    void setupUserAndVault() {
        assumeTrue(
                apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("sk-placeholder"),
                "Skipped: set veto.test.deepseek.api-key in application-local.yml");

        if (vault.isUnlocked()) {
            return;
        }

        // ── 1. Create test user (first run) or login (subsequent) ──────────
        if (!userRegistry.anyUserExists()) {
            var testUser = userRegistry.create(TEST_USER, TEST_PASSWORD, UserRegistry.Role.ADMIN);

            // Generate Vault Key and wrap it for this user
            SecretKey masterKey =
                    vaultKeyManager.deriveMasterKey(
                            TEST_USER, TEST_PASSWORD, testUser.getPasswordSalt());
            SecretKey vaultKey = vaultKeyManager.generateVaultKey();
            vaultKeyManager.wrapVaultKey(vaultKey, masterKey, TEST_USER);
        }

        // ── 2. Authenticate ────────────────────────────────────────────────
        var user = userRegistry.authenticate(TEST_USER, TEST_PASSWORD);
        assertTrue(user.isPresent(), "test user should authenticate successfully");
        assertEquals("ADMIN", user.get().getRole());

        // ── 3. Derive Master Key from password + per-user salt ─────────
        SecretKey masterKey =
                vaultKeyManager.deriveMasterKey(
                        TEST_USER, TEST_PASSWORD, user.get().getPasswordSalt());

        // ── 4. Unwrap the Vault Key ────────────────────────────────────────
        SecretKey vaultKey = vaultKeyManager.unwrapVaultKey(masterKey, TEST_USER);
        assertNotNull(vaultKey, "vault key should be recoverable");

        // ── 5. Unlock vault for this user ──────────────────────────────────
        vault.unlock(vaultKey, TEST_USER);
        assertTrue(vault.isUnlocked());
        assertEquals(TEST_USER, vault.getCurrentUser());

        // ── 6. Store API key + model binding ───────────────────────────────
        vault.store("deepseek-key", apiKey);
        vault.store("model:" + model, "deepseek-key");

        // ── 7. Verify credential is retrievable ────────────────────────────
        var resolved = vault.retrieve("deepseek-key");
        assertTrue(resolved.isPresent());
        assertEquals(apiKey, resolved.get());
    }

    @Test
    void fullLoginAndAgentFlow() {
        // ── 1. Create Agent ────────────────────────────────────────────────
        Agent agent =
                Agent.builder()
                        .name("e2e-agent")
                        .description("End-to-end test agent")
                        .systemPrompt("You are a helpful coding assistant. Be concise.")
                        .sessionId("e2e-session-1")
                        .build();

        assertEquals("e2e-agent", agent.name());
        assertEquals(AgentState.IDLE, agent.state());
        assertTrue(agent.turns().isEmpty());

        // ── 2. IDLE → RUNNING ──────────────────────────────────────────────
        agent = agent.withState(AgentState.RUNNING);

        // ── 3. Call LLM via UniformLLMCaller ───────────────────────────────
        VetoResponse response =
                caller.call(
                        new VetoRequest(
                                agent.systemPrompt()
                                        + "\n\nRespond in JSON: {\"thought\":\"...\", \"call\":null, \"is_finished\":true}",
                                "What is 2 + 2? Answer with only the number.",
                                List.of(),
                                ProviderType.DEEPSEEK,
                                model,
                                "deepseek-key",
                                new LlmOptions(0.0, null, 1024, Duration.ofSeconds(60))));

        // ── 4. Verify response ─────────────────────────────────────────────
        assertNotNull(response.thought(), "thought should not be null");
        assertFalse(response.thought().isBlank(), "thought should not be blank");
        assertTrue(response.isFinished());

        // ── 5. Record the turn ─────────────────────────────────────────────
        TurnRecord turn =
                new TurnRecord(
                        agent.nextTurnNumber(),
                        response.thought(),
                        response.call() != null ? response.call().toolName() : null,
                        response.call() != null ? response.call().args() : null,
                        null,
                        null);
        agent = agent.appendTurn(turn);

        assertEquals(1, agent.turns().size());
        assertEquals(response.thought(), agent.turns().get(0).thought());
    }

    @Test
    void agentLifecycleAndStateTransitions() {
        Agent agent =
                Agent.builder()
                        .name("lifecycle-agent")
                        .systemPrompt("You are a helpful assistant.")
                        .sessionId("e2e-lifecycle")
                        .build();

        assertEquals(AgentState.IDLE, agent.state());

        agent = agent.withState(AgentState.RUNNING);
        assertEquals(AgentState.RUNNING, agent.state());

        agent = agent.withState(AgentState.WAITING);
        assertEquals(AgentState.WAITING, agent.state());

        agent = agent.withState(AgentState.RUNNING);
        assertEquals(AgentState.RUNNING, agent.state());

        agent = agent.withState(AgentState.HALTED);
        assertEquals(AgentState.HALTED, agent.state());

        // Immutability
        Agent original = Agent.builder().name("immutable-test").systemPrompt("test").build();
        Agent modified = original.withState(AgentState.RUNNING);
        assertEquals(AgentState.IDLE, original.state());
        assertEquals(AgentState.RUNNING, modified.state());
        assertNotSame(original, modified);
    }
}
