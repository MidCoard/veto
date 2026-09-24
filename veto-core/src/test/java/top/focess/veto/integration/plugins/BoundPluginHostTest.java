package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.Provenance;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCallContextHolder;
import top.focess.veto.agent.workspace.PathMode;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.api.agent.screening.Danger;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.workflow.PluginAwait;
import top.focess.veto.api.llm.ToolCall;
import top.focess.veto.api.llm.ToolResultPresentationMode;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.storage.PluginStorage;

@DefaultQualifier(
        value = NonNull.class,
        locations = {
            TypeUseLocation.FIELD,
            TypeUseLocation.PARAMETER,
            TypeUseLocation.RETURN,
            TypeUseLocation.UPPER_BOUND
        })
class BoundPluginHostTest {
    @Test
    void invocationAndAwaitResolveLocalNamesWithoutExpandingThePermit() throws Exception {
        for (String name : List.of("plugin_test_plugin__operation", "custom_operation")) {
            try (var fixture = new PluginAgentHostsTest.Fixture()) {
                var configuration = new PluginHostConfiguration();
                var services =
                        configuration.runtimeHostServices(
                                PluginTestSupport.providerOf(null),
                                        PluginTestSupport.providerOf(null),
                                PluginTestSupport.providerOf(null),
                                        PluginTestSupport.providerOf(null));
                var delegate = (PluginHost) services.services().get(PluginHost.class);
                if (delegate == null) throw new AssertionError("Missing host");
                when(fixture.storage.scopes(PluginStorage.Kind.SESSION, null, 200))
                        .thenReturn(new PluginStorage.Page<>(List.of(fixture.scope), null));
                var host =
                        new BoundPluginHost(
                                delegate,
                                fixture.plugin,
                                fixture.storage,
                                fixture.scopes,
                                local -> local.equals("operation") ? name : "another");
                for (String binding :
                        List.of(fixture.plugin.bindingId(), "foreign-plugin-instance")) {
                    var definition =
                            new NativeToolDefinition(
                                    name,
                                    "operation",
                                    ToolCapability.PLUGIN_LOCAL,
                                    Danger.SAFE,
                                    false,
                                    ToolDocs.nonNullClass(BoundPluginHostTest.class),
                                    ToolDocs.nonNullClass(BoundPluginHostTest.class),
                                    Map.of(),
                                    new Provenance("test.plugin", binding, "1.0.0", "operation"));
                    var user = UUID.randomUUID();
                    var session = UUID.fromString(fixture.session.getId());
                    var permit =
                            ToolExecutionPermit.capture(
                                            new ToolCall(name, Map.of(), "call"),
                                            definition,
                                            Workspace.single(Path.of("."), PathMode.REAL))
                                    .withCaller(fixture.childId, user, "owner", session);
                    ToolCallContextHolder.set(
                            new ToolCallContext(
                                    fixture.childId,
                                    user,
                                    "owner",
                                    session,
                                    ToolResultPresentationMode.BASIC,
                                    permit,
                                    "request"));
                    ReflectionTestUtils.invokeMethod(
                            ToolCallContextHolder.class, "setCurrentCallId", "call");
                    try {
                        if (binding.equals(fixture.plugin.bindingId())) {
                            assertEquals(
                                    fixture.session.getId(),
                                    host.invocation("operation").sessionId());
                            assertThrows(SecurityException.class, () -> host.invocation("another"));
                            assertEquals("call", host.invocation("operation").callId());
                            host.await(
                                    "operation",
                                    new PluginAwait("ready", new CompletableFuture<>()));
                            var result = ToolCallContextHolder.drainResponse();
                            assertTrue(
                                    result
                                            instanceof
                                            ToolCallContextHolder.ResponseDirective.Await);
                            if (result
                                    instanceof
                                    ToolCallContextHolder.ResponseDirective.Await awaiting)
                                assertEquals("request", awaiting.requestId());
                            ReflectionTestUtils.invokeMethod(
                                    ToolCallContextHolder.class, "setCurrentCallId", "stale-call");
                            assertThrows(
                                    SecurityException.class, () -> host.invocation("operation"));
                        } else {
                            assertThrows(
                                    SecurityException.class, () -> host.invocation("operation"));
                            assertThrows(
                                    SecurityException.class,
                                    () ->
                                            host.await(
                                                    "operation",
                                                    new PluginAwait(
                                                            "ready", new CompletableFuture<>())));
                        }
                    } finally {
                        ToolCallContextHolder.clear();
                    }
                }
            }
        }
    }

    @Test
    void effectsRequireSelectedSessionAndCurrentOwnerEvenForCachedScope() throws Exception {
        try (var fixture = new PluginAgentHostsTest.Fixture()) {
            @NonNull PluginHost delegate = mock();
            when(fixture.storage.scopes(PluginStorage.Kind.SESSION, null, 200))
                    .thenReturn(new PluginStorage.Page<>(List.of(fixture.scope), null));
            var host =
                    new BoundPluginHost(delegate, fixture.plugin, fixture.storage, fixture.scopes);
            host.invalidate(fixture.session.getId(), "groups");
            host.wake("owner", fixture.session.getId(), fixture.childId);
            verify(delegate).invalidate(fixture.session.getId(), "groups");
            verify(delegate).wake("owner", fixture.session.getId(), fixture.childId);
            assertThrows(SecurityException.class, () -> host.invalidate("foreign", "groups"));
            assertThrows(
                    SecurityException.class,
                    () -> host.wake("foreign-owner", fixture.session.getId(), fixture.childId));
            assertThrows(
                    SecurityException.class, () -> host.wake("owner", "foreign", fixture.childId));
            when(fixture.scopes.authorizeSession(fixture.storage, fixture.scope))
                    .thenThrow(new SecurityException("deselected"));
            assertThrows(
                    SecurityException.class,
                    () -> host.invalidate(fixture.session.getId(), "groups"));
            verifyNoMoreInteractions(delegate);
        }
    }

    @Test
    void readinessWaitsForApplicationAndRunsOnceWhilePluginIsActive() throws Exception {
        try (var fixture = new PluginAgentHostsTest.Fixture()) {
            var configuration = new PluginHostConfiguration();
            var services =
                    configuration.runtimeHostServices(
                            PluginTestSupport.providerOf(null),
                            PluginTestSupport.providerOf(null),
                            PluginTestSupport.providerOf(null),
                            PluginTestSupport.providerOf(null));
            var delegate = (PluginHost) services.services().get(PluginHost.class);
            if (delegate == null) throw new AssertionError("Missing host delegate");
            var host =
                    new BoundPluginHost(delegate, fixture.plugin, fixture.storage, fixture.scopes);
            var completed = new CompletableFuture<Boolean>();
            var calls = new AtomicInteger();
            host.whenReady(
                    () -> {
                        calls.incrementAndGet();
                        completed.complete(true);
                    });
            assertFalse(completed.isDone());
            configuration.ready();
            assertTrue(completed.get(5, TimeUnit.SECONDS));
            configuration.ready();
            assertEquals(1, calls.get());
            var late = new CompletableFuture<Boolean>();
            host.whenReady(() -> late.complete(true));
            assertTrue(late.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stopBeforeApplicationReadyRevokesPendingCallback() throws Exception {
        try (var fixture = new PluginAgentHostsTest.Fixture()) {
            var configuration = new PluginHostConfiguration();
            var services =
                    configuration.runtimeHostServices(
                            PluginTestSupport.providerOf(null),
                            PluginTestSupport.providerOf(null),
                            PluginTestSupport.providerOf(null),
                            PluginTestSupport.providerOf(null));
            var delegate = (PluginHost) services.services().get(PluginHost.class);
            if (delegate == null) throw new AssertionError("Missing host delegate");
            var host =
                    new BoundPluginHost(delegate, fixture.plugin, fixture.storage, fixture.scopes);
            @NonNull Runnable callback = mock();
            host.whenReady(callback);
            fixture.plugin.close();
            configuration.ready();
            verify(callback, after(150).never()).run();
        }
    }
}
