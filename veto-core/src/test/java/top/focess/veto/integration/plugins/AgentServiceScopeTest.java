package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;

class AgentServiceScopeTest {
    private final @NonNull PluginStorageFactory factory = mock();
    private final @NonNull PluginStorage caller = mock();
    private final @NonNull UUID session = UUID.randomUUID();
    private final PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant =
            new PluginStorage.Grant<>(
                    "host-issued-token",
                    new Scope.SessionScope("immutable-user-id", session.toString()));

    @AfterEach
    void clearInvocation() {
        ToolCallContextHolder.clear();
    }

    @Test
    void agentComesFromAdmittedCallAndUsernameIsDistinctFromStorageIdentity() {
        admit("agent-one", "login-name", session);
        when(factory.authorizeSession(caller, grant)).thenReturn("login-name");
        var identity = AgentServiceScope.authorize(factory, caller, grant);
        assertEquals(
                new Scope.AgentScope("immutable-user-id", session.toString(), "agent-one"),
                identity);
        verify(factory).authorizeSession(caller, grant);
    }

    @Test
    void retainedSessionGrantDoesNotAuthorizeOutsideLiveInvocation() {
        assertThrows(
                SecurityException.class, () -> AgentServiceScope.authorize(factory, caller, grant));
        verifyNoInteractions(factory);
    }

    @Test
    void anotherSessionCannotBorrowCurrentAgent() {
        admit("agent-one", "login-name", UUID.randomUUID());
        assertThrows(
                SecurityException.class, () -> AgentServiceScope.authorize(factory, caller, grant));
        verifyNoInteractions(factory);
    }

    @Test
    void anotherOwnerCannotBorrowCurrentAgent() {
        admit("agent-one", "other-login", session);
        when(factory.authorizeSession(caller, grant)).thenReturn("login-name");
        assertThrows(
                SecurityException.class, () -> AgentServiceScope.authorize(factory, caller, grant));
    }

    @Test
    void changedAgentCannotReuseCallerBoundPermit() {
        admit("agent-one", "login-name", session);
        var previous = ToolCallContextHolder.get();
        if (previous == null) throw new AssertionError("Missing admitted test invocation");
        ToolCallContextHolder.set(
                new ToolCallContext(
                        "agent-two",
                        previous.userId(),
                        previous.owner(),
                        previous.sessionId(),
                        previous.toolResultPresentation(),
                        previous.executionPermit()));
        assertThrows(
                SecurityException.class, () -> AgentServiceScope.authorize(factory, caller, grant));
        verifyNoInteractions(factory);
    }

    @Test
    void staleCallIdCannotReusePermit() {
        admit("agent-one", "login-name", session);
        assertNull(
                ReflectionTestUtils.invokeMethod(
                        ToolCallContextHolder.class, "setCurrentCallId", "another-call"));
        assertThrows(
                SecurityException.class, () -> AgentServiceScope.authorize(factory, caller, grant));
        verifyNoInteractions(factory);
    }

    @Test
    void forgedCrossPluginOrRevokedGrantIsRejectedByIssuer() {
        admit("agent-one", "login-name", session);
        when(factory.authorizeSession(caller, grant))
                .thenThrow(new SecurityException("unrecognized grant"));
        assertThrows(
                SecurityException.class, () -> AgentServiceScope.authorize(factory, caller, grant));
    }

    private void admit(@NonNull String agent, @NonNull String owner, @NonNull UUID sessionId) {
        var user = UUID.randomUUID();
        var permit =
                new ToolExecutionPermit(
                                new ToolCall("fixture", Map.of(), "current-call"),
                                ToolCapability.PRIVILEGED,
                                null,
                                null,
                                Map.of(),
                                List.of(),
                                null,
                                DeployerPolicy.FULL_ACCESS,
                                Set.of(),
                                null)
                        .withCaller(agent, user, owner, sessionId);
        ToolCallContextHolder.set(
                new ToolCallContext(
                        agent, user, owner, sessionId, ToolResultPresentationMode.BASIC, permit));
        assertNull(
                ReflectionTestUtils.invokeMethod(
                        ToolCallContextHolder.class, "setCurrentCallId", "current-call"));
    }
}
