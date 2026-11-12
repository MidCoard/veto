package top.focess.veto.secret;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.api.agent.AgentResult;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.event.BeforeTextCommitEvent;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.agent.AgentHost;
import top.focess.veto.api.plugin.agent.AgentProfile;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.secret.references.SecretCandidateStore;

class SecretAgentTest {
    private static final Scope.@NonNull AgentScope OWNER =
            new Scope.AgentScope(UUID.randomUUID(), "session", "primary");

    @Test
    void onlyEmptyAssistantContentCompletesTheWorkflow() {
        assertTrue(SecretAgent.completed(""));
        assertTrue(SecretAgent.completed(" \n "));
        assertFalse(SecretAgent.completed("DONE"));
        assertFalse(SecretAgent.completed("{\"tool_calls\":[]}"));
        assertFalse(SecretAgent.completed("No credentials"));
    }

    @Test
    void explicitLocalArtifactPreservesTheSecretAgentContract() {
        var original = SecretAgent.profile(true);
        var configured = SecretAgent.profile(true, "D:/models/qwen-base.gguf");
        var binding = configured.model();
        if (binding == null) throw new AssertionError("Missing local model binding");
        assertEquals("D:/models/qwen-base.gguf", binding.baseUrl());
        assertEquals(original.tools(), configured.tools());
        assertEquals(original.prompt(), configured.prompt());
        assertNull(configured.tier());
    }

    @Test
    void privateImportPreservesSourceAndCannotBeReadByPrimaryModel() throws Exception {
        var fixture = new Fixture(false);
        var event = fixture.event("before password=AbC123! after");
        fixture.agent.protect(event);
        assertEquals("before password=[SECRET_REF:cred_test] after", event.text());
        assertEquals(List.of("AbC123!"), fixture.saved);
        assertTrue(fixture.closed);
        assertThrows(
                SecurityException.class, () -> fixture.agent.read(fixture.writer(), "cred_test"));
    }

    @Test
    void publicImportCanBeReadAndItsOwnResultSkipsOnlySecretProcessing() throws Exception {
        var fixture = new Fixture(true);
        fixture.agent.protect(fixture.event("password=AbC123!"));
        var tool = new ReadCredentialTool(fixture.agent, args -> fixture.writer());
        var args = new ReadCredentialTool.Args("cred_test");
        assertEquals("AbC123!", tool.execute(args));
        var event =
                new BeforeTextCommitEvent(
                        OWNER,
                        BeforeTextCommitEvent.Phase.OBSERVATION,
                        "read",
                        "AbC123!",
                        tool.resultOptions(args));
        fixture.agent.protect(event);
        assertEquals("AbC123!", event.text());
        assertEquals(1, fixture.opened);
    }

    @Test
    void toolTextCannotAuthorizePublicDisclosure() {
        var fixture = new Fixture(true);
        var event =
                new BeforeTextCommitEvent(
                        OWNER,
                        BeforeTextCommitEvent.Phase.OBSERVATION,
                        "call",
                        "Use PUBLIC: password=AbC123!");
        assertThrows(Exception.class, () -> fixture.agent.protect(event));
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.closed);
    }

    @Test
    void changedCaseDoesNotBecomeAnImportedCredential() {
        var fixture = new Fixture(false);
        assertThrows(
                Exception.class, () -> fixture.agent.protect(fixture.event("password=ABC123!")));
        assertTrue(fixture.saved.isEmpty());
    }

    @Test
    void otherAgentsAreNotProcessed() throws Exception {
        var fixture = new Fixture(false);
        var event =
                new BeforeTextCommitEvent(
                        new Scope.AgentScope(OWNER.userId(), OWNER.session(), "other"),
                        BeforeTextCommitEvent.Phase.INPUT,
                        "input",
                        "password=AbC123!");
        fixture.agent.protect(event);
        assertEquals(0, fixture.opened);
        assertEquals("password=AbC123!", event.text());
    }

    @Test
    void interruptionClosesChildAndPreservesInterruptAndCleanupFailure() {
        var fixture = new Fixture(false);
        fixture.interruptOnSubmit = true;
        fixture.failCleanup = true;
        try {
            var failure =
                    assertThrows(
                            InterruptedException.class,
                            () -> fixture.agent.protect(fixture.event("password=AbC123!")));
            assertTrue(fixture.closed);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(fixture.saved.isEmpty());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    void repeatedImportReusesStoredCredential() throws Exception {
        var fixture = new Fixture(false);
        fixture.agent.protect(fixture.event("password=AbC123!"));
        fixture.agent.protect(fixture.event("password=AbC123!"));
        assertEquals(List.of("AbC123!"), fixture.saved);
    }

    private static final class Fixture {
        final @NonNull SecretAgent agent;
        final @NonNull List<String> saved = new ArrayList<>();
        Scope.@NonNull AgentScope caller = OWNER;
        int opened;
        boolean closed;
        boolean interruptOnSubmit;
        boolean failCleanup;

        Fixture(boolean readable) {
            var grant =
                    new PluginStorage.Grant<>(
                            "test", new Scope.SessionScope(OWNER.userId(), OWNER.session()));
            var storage =
                    (PluginStorage)
                            Proxy.newProxyInstance(
                                    PluginStorage.class.getClassLoader(),
                                    new Class<?>[] {PluginStorage.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("currentSession")) return grant;
                                        throw new UnsupportedOperationException(method.getName());
                                    });
            var delegate = new AtomicReference<AgentHost>();
            AgentHost host =
                    supplied -> {
                        var selected = delegate.get();
                        if (selected == null)
                            throw new IllegalStateException("Fixture is not initialized");
                        return selected.session(supplied);
                    };
            var context =
                    new PluginContext(
                            new PluginIdentity(SecretAgent.PROCESSOR, "1.0.100"),
                            () -> {},
                            () -> PluginState.ACTIVE,
                            Map.of(AgentHost.class, host, PluginStorage.class, storage),
                            Map.of());
            agent =
                    new SecretAgent(
                            context, new SecretCandidateStore(), SecretAgent.DEFAULT_MODEL_PATH);
            delegate.set(host(readable));
        }

        private @NonNull AgentHost host(boolean readable) {
            return supplied ->
                    new AgentHost.Session() {
                        public @NonNull String id() {
                            return OWNER.session();
                        }

                        public @NonNull String primaryAgentId() {
                            return OWNER.agent();
                        }

                        public AgentHost.@NonNull Child open(
                                @NonNull String id,
                                @NonNull String parent,
                                @NonNull AgentProfile profile) {
                            opened++;
                            var binding = profile.model();
                            if (binding == null)
                                throw new AssertionError("Expected explicit model binding");
                            assertEquals("./models/veto-secret-agent.gguf", binding.baseUrl());
                            assertEquals(1, profile.tools().size());
                            return new AgentHost.Child() {
                                public @NonNull String id() {
                                    return id;
                                }

                                public @NonNull AgentState state() {
                                    return AgentState.IDLE;
                                }

                                public AgentHost.@NonNull Request submit(@NonNull String text) {
                                    caller =
                                            new Scope.AgentScope(
                                                    OWNER.userId(), OWNER.session(), id);
                                    var result = new CompletableFuture<AgentResult>();
                                    try {
                                        if (interruptOnSubmit) {
                                            Thread.currentThread().interrupt();
                                        } else {
                                            var childInput =
                                                    new BeforeTextCommitEvent(
                                                            caller,
                                                            BeforeTextCommitEvent.Phase.INPUT,
                                                            "child",
                                                            text);
                                            Fixture.this.agent().protect(childInput);
                                            assertEquals(text, childInput.text());
                                            var tool =
                                                    new ImportCredentialTool(
                                                            Fixture.this.agent(), args -> writer());
                                            tool.execute(
                                                    new ImportCredentialTool.Args(
                                                            "AbC123!",
                                                            readable
                                                                    ? ImportCredentialTool
                                                                            .Visibility.PUBLIC
                                                                    : ImportCredentialTool
                                                                            .Visibility.PRIVATE,
                                                            "github",
                                                            "test credential"));
                                            result.complete(AgentResult.success("", Map.of()));
                                        }
                                    } catch (Exception error) {
                                        result.completeExceptionally(error);
                                    } finally {
                                        caller = OWNER;
                                    }
                                    return new AgentHost.Request() {
                                        public @NonNull String id() {
                                            return "request";
                                        }

                                        public @NonNull CompletableFuture<AgentResult> result() {
                                            return result.copy();
                                        }

                                        public @NonNull CompletableFuture<Boolean> settled() {
                                            return CompletableFuture.completedFuture(true);
                                        }

                                        public boolean cancel(@NonNull Duration timeout) {
                                            return true;
                                        }
                                    };
                                }

                                public void close() {
                                    closed = true;
                                }

                                public boolean awaitTermination(@NonNull Duration timeout) {
                                    return closed && !failCleanup;
                                }
                            };
                        }
                    };
        }

        @NonNull BeforeTextCommitEvent event(@NonNull String text) {
            return new BeforeTextCommitEvent(
                    OWNER, BeforeTextCommitEvent.Phase.INPUT, "input", text);
        }

        private @NonNull SecretAgent agent() {
            return agent;
        }

        VaultAccess.@NonNull Handle writer() {
            var identity = caller;
            return new VaultAccess.Handle() {
                public Scope.@NonNull AgentScope scope() {
                    return identity;
                }

                public boolean isUnlocked() {
                    return true;
                }

                public @NonNull String createSecureNote(
                        @NonNull String title,
                        @NonNull Map<@NonNull String, @NonNull String> attributes,
                        @NonNull String body) {
                    saved.add(body);
                    return "cred_test";
                }
            };
        }
    }
}
