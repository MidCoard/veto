package top.focess.veto.integration.plugins;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.SessionAgentRegistry;
import top.focess.veto.api.agent.tool.ToolPreparation;
import top.focess.veto.api.plugin.PluginHost;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.plugin.storage.PluginStorage;
import top.focess.veto.api.process.Command;
import top.focess.veto.api.process.ProcessHost;
import top.focess.veto.integration.plugins.storage.PluginStorageFactory;
import top.focess.veto.sandbox.SandboxHandle;
import top.focess.veto.sandbox.SandboxManager;

/** Live admitted process; input serializes on inputLock and stop is idempotent. */
final class RunningPluginProcess implements ProcessHost.Running {
    final @NonNull PluginProcessHost owner;
    private final @NonNull SandboxManager sandbox;
    private final @NonNull PluginStorageFactory scopes;
    private final @NonNull SessionAgentRegistry agents;
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.integration.plugins.RunningPluginProcess");
    private final PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant;
    private final @NonNull UUID id;
    final PluginHost.@NonNull Invocation invocation;
    private final ToolPreparation.@NonNull ProcessIntent intent;
    private final @NonNull String cwd;
    final @NonNull Process process;
    private final @NonNull String sandboxId;
    final @NonNull Object inputLock = new Object();
    private final @NonNull AtomicBoolean stopped = new AtomicBoolean();
    volatile boolean stdinClosed;
    private volatile boolean timedOut;

    private ScheduledFuture<?> deadline;

    RunningPluginProcess(
            @NonNull PluginProcessHost owner,
            PluginStorage.@NonNull Grant<Scope.@NonNull SessionScope> grant,
            @NonNull UUID id,
            PluginHost.@NonNull Invocation invocation,
            ToolPreparation.@NonNull ProcessIntent intent,
            @NonNull String cwd,
            @NonNull Process process,
            @NonNull String sandboxId,
            @NonNull SandboxManager sandbox,
            @NonNull PluginStorageFactory scopes,
            @NonNull SessionAgentRegistry agents) {
        this.owner = owner;
        this.sandbox = sandbox;
        this.scopes = scopes;
        this.agents = agents;
        this.grant = grant;
        this.id = id;
        this.invocation = invocation;
        this.intent = intent;
        this.cwd = cwd;
        this.process = process;
        this.sandboxId = sandboxId;
    }

    @SuppressWarnings(
            "resource") // WHY: the timer is shut down from the process exit callback below
    void startDeadline(@NonNull SandboxHandle handle) {
        var timer =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon(true).name("process-deadline-" + id).factory());
        try {
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
            sandbox.substrate()
                    .onExit(handle, process)
                    .whenComplete(
                            (ignored, failure) -> {
                                var scheduled = deadline;
                                if (scheduled != null) scheduled.cancel(false);
                                timer.shutdown();
                                try {
                                    if (failure == null) sandbox.deprovision(sandboxId);
                                    else
                                        log.error(
                                                "Native process-tree retirement failed; workspace masks retained",
                                                failure);
                                } finally {
                                    owner.plugin.releaseResource(this);
                                }
                            });
        } catch (Throwable failure) {
            var scheduled = deadline;
            if (scheduled != null) scheduled.cancel(false);
            timer.shutdown();
            throw failure;
        }
    }

    void admitted() {
        if (owner.plugin.state() != PluginState.ACTIVE)
            throw new SecurityException("Process plugin is unavailable");
        scopes.authorizeSession(owner.storage, grant);
    }

    void checkInput() {
        admitted();
        if (stopped.get()
                || !process.isAlive()
                || stdinClosed
                || agents.agents(UUID.fromString(invocation.sessionId())).stream()
                        .noneMatch(entry -> entry.agent().id().equals(invocation.agentId())))
            throw new SecurityException("Process input is unavailable");
    }

    void stop() {
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

            public int read(byte @NonNull [] buffer, int offset, int length) throws IOException {
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
