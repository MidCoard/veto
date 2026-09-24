package top.focess.veto.integration.plugins;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.agent.capability.CapabilityAccess;
import top.focess.veto.agent.tool.PreparedInvocation;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.api.agent.tool.ToolCapability;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.CommandResult;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.plugin.runtime.ManagedPlugin;
import top.focess.veto.sandbox.SandboxManager;
import top.focess.veto.sandbox.SandboxProfile;

/** Generic admitted process resources. Feature registries and task policy are outside core. */
@Component
public final class PluginProcessHosts implements PluginProcessHostFactory {
    private final @NonNull SandboxManager sandbox;
    private final @NonNull PluginStorageFactory scopes;
    private final @NonNull SessionAgentRegistry agents;

    public PluginProcessHosts(
            @NonNull SandboxManager sandbox,
            @NonNull PluginStorageFactory scopes,
            @NonNull SessionAgentRegistry agents) {
        this.sandbox = sandbox;
        this.scopes = scopes;
        this.agents = agents;
    }

    @Bean
    public @NonNull PluginHostServices processHostServices() {
        return new PluginHostServices(Map.of(PluginProcessHostFactory.class, this));
    }

    public @NonNull ProcessHost bind(
            @NonNull ManagedPlugin plugin, @NonNull PluginStorage storage) {
        return new Bound(plugin, storage);
    }

    public static void validateInput(
            @NonNull ManagedPlugin plugin,
            PluginHost.@NonNull Invocation invocation,
            ProcessHost.@NonNull Running running) {
        if (!(running instanceof RunningProcess process)
                || process.owner.plugin != plugin
                || !process.invocation.owner().equals(invocation.owner())
                || !process.invocation.sessionId().equals(invocation.sessionId())
                || !process.invocation.agentId().equals(invocation.agentId()))
            throw new SecurityException("Process belongs to another scope");
        process.checkInput();
    }

    private final class Bound implements ProcessHost {
        private final @NonNull ManagedPlugin plugin;
        private final @NonNull PluginStorage storage;

        private Bound(@NonNull ManagedPlugin plugin, @NonNull PluginStorage storage) {
            this.plugin = plugin;
            this.storage = storage;
        }

        private @NonNull ToolCallContext context() {
            var context = CapabilityAccess.require(ToolCapability.PROCESS_EXECUTION);
            IsolatedExecutions.requireNonIsolatedParent(context);
            if (plugin.state() != PluginState.ACTIVE
                    || !plugin.bindingId().equals(context.executionPermit().remoteServerName()))
                throw new SecurityException("Process invocation belongs to another plugin");
            var scope = storage.currentSession();
            if (!scope.userId().equals(context.owner())
                    || !scope.sessionId().equals(String.valueOf(context.sessionId())))
                throw new SecurityException("Process scope mismatch");
            scopes.authorizeSession(storage, scope);
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
            var scope = storage.currentSession();
            var id = UUID.randomUUID();
            var sandboxId = "execution-" + id;
            var handle =
                    sandbox.provision(
                            sandboxId,
                            SandboxProfile.forExecution(
                                    context.executionPermit().requireExecutionRoot(),
                                    context.executionPermit().protectedPaths(),
                                    intent.network()));
            try {
                var process =
                        sandbox.substrate()
                                .startBackground(
                                        handle,
                                        intent.commands().getFirst(),
                                        context.executionPermit().requireExecutionRoot());
                var invocation =
                        new PluginHost.Invocation(
                                scope.userId(),
                                scope.sessionId(),
                                context.agentId(),
                                context.requestId(),
                                context.executionPermit().callId());
                var running =
                        new RunningProcess(
                                this,
                                scope,
                                id,
                                invocation,
                                intent,
                                context.executionPermit().requireExecutionRoot().toString(),
                                process,
                                sandboxId);
                try {
                    plugin.ownStoppingResource(running, running::stop);
                    running.startDeadline();
                    return running;
                } catch (RuntimeException failure) {
                    running.stop();
                    throw failure;
                }
            } catch (RuntimeException failure) {
                sandbox.deprovision(sandboxId);
                throw failure;
            }
        }

        public @NonNull InputWrite prepareInput(@NonNull Running running) {
            var context = context();
            var prepared = prepared(context);
            if (!(prepared.intent() instanceof ToolPreparation.InputIntent intent)
                    || intent.process() != running)
                throw new SecurityException("Input target changed after admission");
            var owner = context.owner();
            if (owner == null) throw new SecurityException("Input caller owner unavailable");
            prepared.consume();
            validateInput(
                    plugin,
                    new PluginHost.Invocation(
                            owner,
                            String.valueOf(context.sessionId()),
                            context.agentId(),
                            context.requestId(),
                            context.executionPermit().callId()),
                    running);
            var target = (RunningProcess) running;
            byte[] bytes = intent.bytes();
            boolean eof = intent.closeStdin();
            return new InputWrite() {
                private final AtomicBoolean consumed = new AtomicBoolean();

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

    private final class RunningProcess implements ProcessHost.Running {
        private final @NonNull Bound owner;
        private final PluginStorage.@NonNull SessionScope scope;
        private final @NonNull UUID id;
        private final PluginHost.@NonNull Invocation invocation;
        private final ToolPreparation.@NonNull ProcessIntent intent;
        private final @NonNull String cwd;
        private final @NonNull Process process;
        private final @NonNull String sandboxId;
        private final @NonNull Object inputLock = new Object();
        private final @NonNull AtomicBoolean stopped = new AtomicBoolean();
        private volatile boolean stdinClosed;
        private volatile boolean timedOut;
        private @Nullable ScheduledFuture<?> deadline;

        private RunningProcess(
                @NonNull Bound owner,
                PluginStorage.@NonNull SessionScope scope,
                @NonNull UUID id,
                PluginHost.@NonNull Invocation invocation,
                ToolPreparation.@NonNull ProcessIntent intent,
                @NonNull String cwd,
                @NonNull Process process,
                @NonNull String sandboxId) {
            this.owner = owner;
            this.scope = scope;
            this.id = id;
            this.invocation = invocation;
            this.intent = intent;
            this.cwd = cwd;
            this.process = process;
            this.sandboxId = sandboxId;
        }

        private void startDeadline() {
            var timer =
                    Executors.newSingleThreadScheduledExecutor(
                            Thread.ofPlatform()
                                    .daemon(true)
                                    .name("process-deadline-" + id)
                                    .factory());
            deadline =
                    timer.schedule(
                            () -> {
                                if (process.isAlive()) {
                                    timedOut = true;
                                    stop();
                                }
                            },
                            intent.timeout().toMillis(),
                            TimeUnit.MILLISECONDS);
            process.onExit()
                    .whenComplete(
                            (ignored, failure) -> {
                                var scheduled = deadline;
                                if (scheduled != null) scheduled.cancel(false);
                                timer.shutdown();
                                sandbox.deprovision(sandboxId);
                                owner.plugin.releaseResource(this);
                            });
        }

        private void admitted() {
            if (owner.plugin.state() != PluginState.ACTIVE)
                throw new SecurityException("Process plugin is unavailable");
            scopes.authorizeSession(owner.storage, scope);
        }

        private void checkInput() {
            admitted();
            if (stopped.get()
                    || !process.isAlive()
                    || stdinClosed
                    || agents.agents(UUID.fromString(invocation.sessionId())).stream()
                            .noneMatch(entry -> entry.agent().id().equals(invocation.agentId())))
                throw new SecurityException("Process input is unavailable");
        }

        private void stop() {
            if (stopped.compareAndSet(false, true)) {
                stdinClosed = true;
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }

        public @NonNull UUID id() {
            return id;
        }

        public PluginHost.@NonNull Invocation invocation() {
            return invocation;
        }

        public @NonNull Command command() {
            return intent.commands().getFirst();
        }

        public @NonNull String cwd() {
            return cwd;
        }

        public boolean networkAllowed() {
            return intent.network();
        }

        public @NonNull Duration maxRuntime() {
            return intent.timeout();
        }

        public long pid() {
            return process.pid();
        }

        public @NonNull InputStream output() {
            admitted();
            return new FilterInputStream(process.getInputStream()) {
                private void readable() throws IOException {
                    try {
                        admitted();
                    } catch (SecurityException denied) {
                        throw new IOException("Process output scope is unavailable");
                    }
                }

                public int read() throws IOException {
                    readable();
                    int value = in.read();
                    readable();
                    return value;
                }

                public int read(byte @NonNull [] buffer, int offset, int length)
                        throws IOException {
                    readable();
                    int count = in.read(buffer, offset, length);
                    try {
                        readable();
                    } catch (IOException denied) {
                        if (count > 0) Arrays.fill(buffer, offset, offset + count, (byte) 0);
                        throw denied;
                    }
                    return count;
                }

                public long skip(long count) throws IOException {
                    readable();
                    long skipped = in.skip(count);
                    readable();
                    return skipped;
                }

                public int available() throws IOException {
                    readable();
                    return in.available();
                }
            };
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public int waitFor() throws InterruptedException {
            return process.waitFor();
        }

        public boolean awaitExit(@NonNull Duration timeout) throws InterruptedException {
            return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        public int exitValue() {
            return process.exitValue();
        }

        public boolean timedOut() {
            return timedOut;
        }

        public void close() {
            if (stopped.get() || !process.isAlive()) return;
            admitted();
            stop();
        }
    }
}
