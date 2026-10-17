package top.focess.veto.integration.plugins;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.tool.PreparedInvocation;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.CommandResult;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.api.util.Resources;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.SandboxProfile;

/** Process API bound to one plugin activation and its authenticated storage grants. */
final class PluginProcessHost implements ProcessHost {
    final @NonNull ManagedPlugin plugin;
    final @NonNull PluginStorage storage;
    private final @NonNull SandboxManager sandbox;
    private final @NonNull PluginStorageFactory scopes;
    private final @NonNull SessionAgentRegistry agents;

    PluginProcessHost(
            @NonNull ManagedPlugin plugin,
            @NonNull PluginStorage storage,
            @NonNull SandboxManager sandbox,
            @NonNull PluginStorageFactory scopes,
            @NonNull SessionAgentRegistry agents) {
        this.plugin = plugin;
        this.sandbox = sandbox;
        this.scopes = scopes;
        this.agents = agents;
        this.storage = storage;
    }

    private @NonNull ToolCallContext context() {
        var context = CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION);
        IsolatedExecutions.requireNonIsolatedParent(context);
        if (plugin.state() != PluginState.ACTIVE
                || !plugin.bindingId().equals(context.executionPermit().remoteServerName()))
            throw new SecurityException("Process invocation belongs to another plugin");
        var grant = storage.currentSession();
        if (!scopes.authorizeSession(storage, grant).equals(context.userId())
                || !grant.scope().session().equals(String.valueOf(context.sessionId())))
            throw new SecurityException("Process scope mismatch");
        return context;
    }

    private @NonNull PreparedInvocation prepared(@NonNull ToolCallContext context) {
        var prepared = context.executionPermit().preparation();
        if (prepared == null) throw new SecurityException("No admitted process intent");
        prepared.authorize(plugin, context);
        return prepared;
    }

    public @NonNull Duration maxRuntime() {
        var intent = prepared(context()).intent();
        return intent instanceof ToolPreparation.ProcessIntent process
                ? process.timeout()
                : Duration.ofMinutes(10);
    }

    public @NonNull CommandResult runApproved() {
        var context = context();
        var prepared = prepared(context);
        if (!(prepared.intent() instanceof ToolPreparation.ProcessIntent intent))
            throw new SecurityException("No admitted command intent");
        prepared.consume();
        var id = "execution-" + UUID.randomUUID();
        var profile =
                SandboxProfile.forExecution(
                        context.executionPermit().requireExecutionRoot(),
                        context.executionPermit().protectedPaths(),
                        context.executionPermit().occupiedRoots(),
                        intent.network());
        var handle = sandbox.provision(id, profile);
        var thread = Thread.currentThread();
        var identity = new Object();
        try {
            plugin.ownStoppingResource(identity, thread::interrupt);
            return sandbox.substrate()
                    .runCommands(
                            handle,
                            intent.commands(),
                            context.executionPermit().requireExecutionRoot(),
                            intent.mode(),
                            intent.timeout());
        } finally {
            plugin.releaseResource(identity);
            sandbox.deprovision(id);
        }
    }

    public @NonNull Running startApproved() {
        var context = context();
        var prepared = prepared(context);
        if (!(prepared.intent() instanceof ToolPreparation.ProcessIntent intent)
                || intent.commands().size() != 1)
            throw new SecurityException("A background process requires one admitted command");
        prepared.consume();
        var grant = storage.currentSession();
        var invocation =
                new PluginHost.Invocation(
                        scopes.authorizeSession(storage, grant),
                        grant.scope().session(),
                        context.agentId(),
                        context.requestId(),
                        context.executionPermit().callId());
        var id = UUID.randomUUID();
        var sandboxId = "execution-" + id;
        var handle =
                sandbox.provision(
                        sandboxId,
                        SandboxProfile.forExecution(
                                context.executionPermit().requireExecutionRoot(),
                                context.executionPermit().protectedPaths(),
                                context.executionPermit().occupiedRoots(),
                                intent.network()));
        try {
            var process =
                    sandbox.substrate()
                            .startBackground(
                                    handle,
                                    intent.commands().getFirst(),
                                    context.executionPermit().requireExecutionRoot());
            var running =
                    new RunningPluginProcess(
                            this,
                            grant,
                            id,
                            invocation,
                            intent,
                            context.executionPermit().requireExecutionRoot().toString(),
                            process,
                            sandboxId,
                            sandbox,
                            scopes,
                            agents);
            try {
                plugin.ownStoppingResource(running, running::stop);
                running.admitted();
                running.startDeadline(handle);
                return running;
            } catch (Throwable failure) {
                Resources.closeOnFailure(running::stop, failure);
                plugin.releaseResource(running);
                throw failure;
            }
        } catch (Throwable failure) {
            Resources.closeOnFailure(() -> sandbox.deprovision(sandboxId), failure);
            throw failure;
        }
    }

    @SuppressWarnings(
            "resource") // WHY: the Running handle is owned by the caller, closed elsewhere
    public @NonNull InputWrite prepareInput(@NonNull Running running) {
        var context = context();
        var prepared = prepared(context);
        if (!(prepared.intent() instanceof ToolPreparation.InputIntent intent)
                || intent.process() != running)
            throw new SecurityException("Input target changed after admission");
        var owner = context.userId();
        if (owner == null) throw new SecurityException("Input caller owner unavailable");
        prepared.consume();
        PluginProcessHosts.validateInput(
                plugin,
                new PluginHost.Invocation(
                        owner,
                        String.valueOf(context.sessionId()),
                        context.agentId(),
                        context.requestId(),
                        context.executionPermit().callId()),
                running);
        var target = (RunningPluginProcess) running;
        byte[] bytes = intent.bytes();
        boolean eof = intent.closeStdin();
        return new InputWrite() {
            private final @NonNull AtomicBoolean consumed = new AtomicBoolean();

            public int byteCount() {
                return bytes.length;
            }

            public boolean closeStdin() {
                return eof;
            }

            public void write() throws IOException {
                if (!consumed.compareAndSet(false, true))
                    throw new SecurityException("Input delivery already consumed");
                synchronized (target.inputLock) {
                    try {
                        target.checkInput();
                        var stream = target.process.getOutputStream();
                        stream.write(bytes);
                        stream.flush();
                        if (eof) {
                            stream.close();
                            target.stdinClosed = true;
                        }
                    } finally {
                        Arrays.fill(bytes, (byte) 0);
                    }
                }
            }
        };
    }
}
