package top.focess.veto.session;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.VetoVersion;
import top.focess.veto.agent.ProtectedInputException;
import top.focess.veto.agent.RequestHandle;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.api.agent.AgentState;
import top.focess.veto.api.llm.LlmBinding;
import top.focess.veto.api.plugin.Scope;
import top.focess.veto.api.util.ByteBoundedQueue;
import top.focess.veto.command.CommandRegistry;
import top.focess.veto.command.SessionCommandService;
import top.focess.veto.command.VetoCommandSender;
import top.focess.veto.contract.Delta;
import top.focess.veto.contract.Frame;
import top.focess.veto.contract.FrameCodec;
import top.focess.veto.vault.UserEntity;

/**
 * Backend protocol endpoint shared by TCP and WebSocket peers. Receives decoded frames from the
 * transport server and dispatches them to agent sessions. Owns the shared output queue and
 * serializes delivery on the server-owned executor; that executor also owns request preparation and
 * control handlers. The transport only writes complete Frames. This thread-safe connection's
 * monitor owns exchanges and terminal command order; AgentSessions owns attachments. Preparation
 * and command execution run outside the monitor. Agent completion uses result/settlement callbacks.
 * Capacity waits and socket writes happen outside the monitor; output is reauthorized immediately
 * before delivery. Servers close all endpoints before joining their executor, including tasks
 * belonging to endpoints already removed from their registry.
 */
public final class ProtocolConnection implements AutoCloseable {
    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.session.ProtocolConnection");
    private static final int MAX_PENDING = 64;
    private static final int MAX_QUEUED_FRAMES = 256;
    private static final int MAX_QUEUED_BYTES = 64 * 1024 * 1024;
    // Every allocated exchange remains reserved for this incarnation. A finite budget bounds
    // replay tracking without assuming allocation and delivery occur in the same order.
    private static final int MAX_SEQUENCES = 65_536;
    private final @NonNull String id;
    private final @NonNull AgentSessions runtime;
    private final @NonNull SessionService sessions;
    private final @NonNull SessionCommandService commands;
    private final CommandRegistry registry;
    private final @NonNull BooleanSupplier authenticated;
    private final @NonNull Predicate<@NonNull BooleanSupplier> authorization;
    private final @NonNull ProtocolOutput output;
    private final @NonNull Executor workers;
    private final @NonNull Consumer<@NonNull CloseReason> onClose;
    private final @NonNull ByteBoundedQueue<@NonNull OutboundFrame> outbox =
            new ByteBoundedQueue<>(MAX_QUEUED_FRAMES, MAX_QUEUED_BYTES);
    private boolean writing;
    private final @NonNull Locale locale;
    private final @NonNull Map<@NonNull Long, @NonNull ProtocolOperation> operations =
            new HashMap<>();
    private final @NonNull ArrayDeque<@NonNull ProtocolOperation> queued = new ArrayDeque<>();
    private final @NonNull ThreadLocal<@Nullable ProtocolOperation> execution = new ThreadLocal<>();
    private final @NonNull AtomicLong outputSequence = new AtomicLong();
    // Authority changes exclude committed agent admission and durable approval decisions. This
    // gate is separate from the connection monitor so slow approval storage cannot block IO.
    // Lock order: authority gate -> login-token guard -> connection monitor -> runner/attachments.
    private final @NonNull ReentrantReadWriteLock authority = new ReentrantReadWriteLock();
    private volatile @NonNull ConnectionIdentity identity;
    private volatile boolean closed;
    private volatile boolean draining;
    private boolean ready;
    private final @NonNull Set<@NonNull Long> receivedSequences = new HashSet<>();
    private ProtocolOperation active;
    private final @NonNull Semaphore controlSlots = new Semaphore(MAX_PENDING);
    private VetoCommandSender sender;

    public ProtocolConnection(
            @NonNull String id,
            @NonNull AgentSessions runtime,
            @NonNull SessionService sessions,
            @NonNull SessionCommandService commands,
            CommandRegistry registry,
            UUID user,
            @NonNull BooleanSupplier authenticated,
            @NonNull Predicate<@NonNull BooleanSupplier> authorization,
            @NonNull Executor workers,
            @NonNull ProtocolOutput output,
            @NonNull Consumer<@NonNull CloseReason> onClose,
            @NonNull Locale locale) {
        this.id = id;
        this.runtime = runtime;
        this.sessions = sessions;
        this.commands = commands;
        this.registry = registry;
        this.identity = new ConnectionIdentity(user, null, 0);
        this.authenticated = authenticated;
        this.authorization = authorization;
        this.workers = workers;
        this.output = output;
        this.onClose = onClose;
        this.locale = locale;
    }

    public UUID userId() {
        return identity.user();
    }

    public String username() {
        return identity.username();
    }

    public @NonNull String id() {
        return id;
    }

    public @NonNull Locale locale() {
        return locale;
    }

    public long authenticationVersion() {
        return identity.version();
    }

    /** A command may change its own authority; revocation from another thread cannot do so. */
    public void setUser(UserEntity user) {
        authority.writeLock().lock();
        try {
            changeIdentity(user);
        } finally {
            authority.writeLock().unlock();
        }
    }

    /**
     * Rechecks the account under the same gate as login; a stale revocation cannot log out a new
     * user.
     */
    public void revokeUser(@NonNull UUID user) {
        authority.writeLock().lock();
        try {
            if (user.equals(identity.user())) changeIdentity(null);
        } finally {
            authority.writeLock().unlock();
        }
    }

    /** Caller owns the authority write gate; only the short state update needs this monitor. */
    private void changeIdentity(UserEntity user) {
        List<@NonNull ProtocolOperation> pending;
        ProtocolOperation current;
        synchronized (this) {
            identity =
                    new ConnectionIdentity(
                            user == null ? null : user.getUserId(),
                            user == null ? null : user.getUsername(),
                            identity.version() + 1);
            current = execution.get();
            if (current != null) current.authority = identity;
            pending = List.copyOf(operations.values());
            if (registry != null) runtime.detach(id);
        }
        for (var operation : pending) {
            var input = operation.input;
            if (operation != current && input != null)
                input.completeExceptionally(new CancellationException("Authentication changed"));
        }
    }

    /**
     * The transport invokes this in receive order; other public operations may run concurrently.
     */
    public void receive(Frame.@NonNull ClientFrame frame) {
        if (closed) return;
        if (!authenticated.getAsBoolean()) {
            close(CloseReason.UNAUTHORIZED);
            return;
        }
        if (frame instanceof Frame.Hello hello) {
            hello(hello);
            return;
        }
        String protocolError = null;
        synchronized (this) {
            if (closed) return;
            if (!ready) protocolError = "Hello required before application frames";
            else if (frame instanceof Frame.SeqRequest request) {
                if (receivedSequences.contains(request.seq()))
                    protocolError = "Sequence has already been used; reconnect";
                else if (receivedSequences.size() >= MAX_SEQUENCES)
                    protocolError = "Connection sequence budget exhausted; reconnect";
                else receivedSequences.add(request.seq());
            }
        }
        if (protocolError != null) {
            // A correlated rejection could falsely settle the original operation. Closing also
            // prevents replay; release this monitor before transport or attachment cleanup.
            send(new Frame.Error(protocolError, 0));
            finish(CloseReason.PROTOCOL_ERROR, true);
            return;
        }
        switch (frame) {
            case Frame.Bye ignored -> close();
            case Frame.Heartbeat ignored -> send(new Frame.HeartbeatAck(Instant.now()));
            case Frame.Request request -> admit(request.seq(), request);
            case Frame.DeclineVetoes decline -> admit(decline.seq(), decline);
            case Frame.Cancel cancel -> cancel(cancel);
            case Frame.Input input -> receiveInput(input);
            case Frame.VetoResult result -> asyncControl(() -> resolveVeto(result), 0);
            case Frame.Complete complete ->
                    asyncControl(
                            () -> {
                                var terminal = requireSender();
                                var commandRegistry = requireRegistry();
                                send(
                                        new Frame.CompleteResult(
                                                commandRegistry.complete(terminal, complete.raw()),
                                                complete.seq()));
                            },
                            complete.seq());
            case Frame.Hint hint ->
                    asyncControl(
                            () -> {
                                var terminal = requireSender();
                                send(
                                        new Frame.HintResult(
                                                requireRegistry().hint(terminal, hint.raw()),
                                                hint.seq()));
                            },
                            hint.seq());
            default -> send(new Frame.Error("Unsupported frame", 0));
        }
    }

    private void hello(Frame.@NonNull Hello hello) {
        boolean invalid;
        synchronized (this) {
            invalid = ready || hello.version() != Frame.PROTOCOL_VERSION;
            if (!invalid) {
                if (registry != null)
                    sender = new VetoCommandSender(this, hello.productVersion(), hello.cwd());
                receivedSequences.add(hello.seq());
            }
        }
        if (invalid) {
            send(new Frame.Error("Invalid protocol handshake", hello.seq()));
            finish(CloseReason.PROTOCOL_ERROR, true);
            return;
        }
        if (!send(new Frame.Welcome(Frame.PROTOCOL_VERSION, hello.seq(), VetoVersion.VERSION)))
            return;
        synchronized (this) {
            if (closed) return;
            ready = true;
        }
        if (registry == null) runtime.connectBrowser(this);
        else runtime.connectTerminal(id, this);
        if (closed) runtime.disconnect(this);
    }

    private @NonNull VetoCommandSender requireSender() {
        var current = sender;
        if (current == null) throw new IllegalStateException("Terminal command unavailable");
        return current;
    }

    private @NonNull CommandRegistry requireRegistry() {
        var current = registry;
        if (current == null) throw new IllegalStateException("Terminal command unavailable");
        return current;
    }

    private void admit(long seq, Frame.@NonNull ClientFrame frame) {
        ProtocolOperation start = null;
        boolean full;
        synchronized (this) {
            if (closed) return;
            full = operations.size() >= MAX_PENDING;
            if (!full) {
                var operation = new ProtocolOperation(seq, frame, identity);
                operations.put(seq, operation);
                if (registry == null || !(frame instanceof Frame.Request)) start = operation;
                else {
                    queued.addLast(operation);
                    if (active == null) {
                        active = queued.pollFirst();
                        start = active;
                    }
                }
            }
        }
        if (full) send(new Frame.Error("Too many pending requests", seq));
        else if (start != null) start(start);
    }

    private void start(@NonNull ProtocolOperation operation) {
        synchronized (this) {
            if (closed || operations.get(operation.seq) != operation) return;
            // Queued terminal commands resolve identity at execution, after preceding login/logout.
            if (registry != null) operation.authority = identity;
            workers.execute(() -> execute(operation));
        }
    }

    private void execute(@NonNull ProtocolOperation operation) {
        synchronized (this) {
            if (closed || operations.get(operation.seq) != operation) return;
            operation.worker = Thread.currentThread();
        }
        execution.set(operation);
        Frame.ServerFrame response =
                new Frame.Done(new Scope.GlobalScope(), null, -1, null, null, operation.seq);
        var context = new ConnectionExecutionScope(operation.authority.user(), locale);
        try (context) {
            if (!valid(operation) || operation.cancelled) throw new CancellationException();
            if (operation.frame instanceof Frame.DeclineVetoes decline) {
                var user = requireUser(operation);
                var session = runtime.requireOwned(decline.sessionId().toString(), user);
                operation.scope = session.scope();
                if (!authorizeControl(
                        operation,
                        () -> {
                            session.declineAllVetoes(user);
                            return true;
                        })) throw new IllegalStateException("Connection authority expired");
                response = new Frame.Done(session.scope(), null, -1, null, null, operation.seq);
            } else if (operation.frame instanceof Frame.Request request) {
                var target = request.sessionId();
                if (target == null) {
                    var terminal = requireSender();
                    response = requireRegistry().dispatch(terminal, request.raw());
                    if (response instanceof Frame.TurnOutcome outcome)
                        operation.scope =
                                requireRegistry().terminalOutcome(terminal, outcome).scope();
                } else submitRequest(operation, request, target);
            }
        } catch (ProtectedInputException rejected) {
            response =
                    new Frame.Error(
                            operation.scope,
                            null,
                            -1,
                            "Protected input could not be processed; retry or use credential settings",
                            operation.seq,
                            null,
                            "PROTECTED_INPUT_UNAVAILABLE");
        } catch (CancellationException cancelled) {
            operation.cancelled = true;
        } catch (Throwable failure) {
            log.warn("Connection request failed ({})", failure.getClass().getSimpleName());
            response =
                    new Frame.Error(
                            operation.scope,
                            null,
                            -1,
                            "Request could not be submitted",
                            operation.seq,
                            null,
                            "REQUEST_REJECTED");
        } finally {
            execution.remove();
            synchronized (this) {
                operation.worker = null;
            }
        }
        var handle = operation.handle;
        if (handle == null) {
            if (response instanceof Frame.Done
                    && operation.frame instanceof Frame.Request
                    && !operation.cancelled)
                send(
                        new Frame.RequestAccepted(operation.seq, operation.scope, null),
                        operation.authority.version(),
                        () -> true);
            finish(operation, response);
        } else {
            // Register only after acceptance and command/admission cleanup. Neither future alone
            // proves that the agent has stopped producing effects.
            CompletableFuture.allOf(
                            handle.result().handle((value, failure) -> null),
                            handle.settled().handle((value, failure) -> null))
                    .whenComplete((ignored, failure) -> completeAgent(operation, handle));
        }
    }

    private void submitRequest(
            @NonNull ProtocolOperation operation,
            Frame.@NonNull Request request,
            @NonNull UUID target) {
        var user = requireUser(operation);
        if (request.raw().isBlank()) throw new IllegalArgumentException("Prompt must not be empty");
        var config =
                sessions.prepare(target.toString(), user)
                        .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        var session = runtime.requireOwned(target.toString(), user);
        var targetAgent = request.agentId();
        var agent = targetAgent == null ? session.primary(user) : session.agent(user, targetAgent);
        if (agent.state() == AgentState.TERMINATED)
            throw new IllegalStateException("Agent has terminated");
        if (targetAgent != null && !agent.userInteractionEnabled())
            throw new IllegalStateException("Agent is read-only");
        if (commands.supports(request.raw())) {
            var primary = session.primary(user);
            submitAgent(
                    session,
                    new Scope.AgentScope(user, config.sessionId(), primary.id()),
                    admission -> session.compact(user, locale, admission));
            if (targetAgent != null)
                commands.primaryNotice(user, config.sessionId(), targetAgent, locale);
        } else {
            var model = config.config();
            var binding =
                    new LlmBinding(
                            model.provider(),
                            model.model(),
                            model.credKey(),
                            model.options(),
                            model.baseUrl());
            submitAgent(
                    session,
                    new Scope.AgentScope(user, config.sessionId(), agent.id()),
                    admission ->
                            targetAgent == null
                                    ? session.submit(
                                            user, request.raw(), binding, locale, admission)
                                    : session.submit(
                                            user, targetAgent, request.raw(), locale, admission));
            commands.notice(user, config.sessionId(), agent.id(), request.raw(), locale);
        }
    }

    /** Binds a command's actual agent request; the command worker returns after admission. */
    public void submitAgent(
            @NonNull AgentSession session,
            Scope.@NonNull AgentScope scope,
            @NonNull Function<RequestHandle.@NonNull Admission, @NonNull RequestHandle> submit) {
        var operation = execution.get();
        if (operation == null) throw new IllegalStateException("No connection request");
        if (!valid(operation) || operation.cancelled)
            throw new CancellationException("Connection request is no longer active");
        session.requireAttached(this);
        var submitted =
                submit.apply(
                        commit -> {
                            var admitted = new AtomicReference<RequestHandle>();
                            if (!authorizeRunner(
                                    operation,
                                    () -> {
                                        session.requireAttached(this);
                                        var handle = commit.get();
                                        operation.handle = handle;
                                        operation.scope = scope;
                                        admitted.set(handle);
                                        return true;
                                    }))
                                throw new CancellationException("Connection authority expired");
                            var handle = admitted.get();
                            if (handle == null)
                                throw new IllegalStateException("Request was not admitted");
                            return handle;
                        });
        if (operation.handle != submitted)
            throw new IllegalStateException("Request was not admitted");
        send(
                new Frame.RequestAccepted(operation.seq, scope, submitted.requestId()),
                operation.authority.version(),
                () -> true);
    }

    /** Final runner enqueue is short and must also serialize with cancellation state. */
    private boolean authorizeRunner(
            @NonNull ProtocolOperation operation, @NonNull BooleanSupplier commit) {
        return authorizeControl(
                operation,
                () -> {
                    synchronized (this) {
                        return valid(operation) && !operation.cancelled && commit.getAsBoolean();
                    }
                });
    }

    /**
     * Durable approvals may wait for storage while authority remains valid. Never acquire the
     * connection monitor around that work: heartbeat, cancellation and admission must stay live.
     */
    private boolean authorizeControl(
            @NonNull ProtocolOperation operation, @NonNull BooleanSupplier commit) {
        authority.readLock().lock();
        try {
            return authorization.test(
                    () -> valid(operation) && !operation.cancelled && commit.getAsBoolean());
        } finally {
            authority.readLock().unlock();
        }
    }

    private void completeAgent(
            @NonNull ProtocolOperation operation, @NonNull RequestHandle handle) {
        Frame.ServerFrame outcome;
        try {
            var result = handle.result().join();
            if (result == null) throw new IllegalStateException("Missing result");
            var turns = result.metadata().get("turns");
            int turnNumber = turns instanceof Number count ? count.intValue() : 0;
            outcome =
                    result.success() || Boolean.TRUE.equals(result.metadata().get("breakerTrip"))
                            ? new Frame.Done(
                                    operation.scope,
                                    handle.requestId(),
                                    turnNumber,
                                    null,
                                    null,
                                    operation.seq)
                            : new Frame.Error(
                                    operation.scope,
                                    handle.requestId(),
                                    turnNumber,
                                    result.message(),
                                    operation.seq,
                                    null,
                                    null);
        } catch (CompletionException | IllegalStateException failure) {
            outcome =
                    new Frame.Error(
                            operation.scope,
                            handle.requestId(),
                            -1,
                            "Agent failed",
                            operation.seq,
                            null,
                            null);
        }
        finish(operation, outcome);
    }

    private void finish(@NonNull ProtocolOperation operation, Frame.@NonNull ServerFrame response) {
        ProtocolOperation next = null;
        CompletableFuture<Void> acknowledgement;
        boolean deferred;
        synchronized (this) {
            acknowledgement = operation.cancelAcknowledged;
            deferred = !acknowledgement.isDone();
            if (!deferred) {
                if (operations.remove(operation.seq) != operation) return;
                queued.remove(operation);
            }
        }
        if (deferred) {
            var pendingResponse = response;
            acknowledgement.thenRun(() -> finish(operation, pendingResponse));
            return;
        }
        if (operation.input != null)
            operation.input.completeExceptionally(new CancellationException());
        if (operation.cancelled)
            response = new Frame.Done(operation.scope, null, -1, null, null, operation.seq);
        else if (response instanceof Frame.Done done)
            response =
                    new Frame.Done(
                            done.scope(),
                            done.requestId(),
                            done.turnNumber(),
                            done.content(),
                            done.username(),
                            operation.seq);
        else if (response instanceof Frame.Error error)
            response =
                    new Frame.Error(
                            error.scope(),
                            error.requestId(),
                            error.turnNumber(),
                            error.content(),
                            operation.seq,
                            error.username(),
                            error.code());
        send(response, operation.authority.version(), () -> true);
        synchronized (this) {
            if (active == operation) {
                active = queued.pollFirst();
                next = active;
            }
        }
        if (response instanceof Frame.Terminate) finish(CloseReason.PROTOCOL_ERROR, true);
        else if (next != null) start(next);
    }

    private void cancel(Frame.@NonNull Cancel request) {
        var acknowledged = new CompletableFuture<Void>();
        synchronized (this) {
            var operation = operations.get(request.targetSeq());
            if (operation != null) operation.cancelAcknowledged = acknowledged;
        }
        try {
            send(new Frame.CancelResult(cancel(request.targetSeq()), request.seq()));
        } finally {
            acknowledged.complete(null);
        }
    }

    private Frame.@NonNull CancelStatus cancel(long target) {
        ProtocolOperation operation;
        RequestHandle handle;
        Thread worker;
        synchronized (this) {
            operation = operations.get(target);
            if (operation == null) return Frame.CancelStatus.NO_ACTIVE_REQUEST;
            if (!valid(operation)) return Frame.CancelStatus.REJECTED;
            var input = operation.input;
            if (input != null && !input.isDone()) {
                input.completeExceptionally(new CancellationException("Input cancelled"));
                return Frame.CancelStatus.ACCEPTED;
            }
            if (operation.cancelled) return Frame.CancelStatus.ACCEPTED;
            handle = operation.handle;
            worker = operation.worker;
            if (handle == null) {
                operation.cancelled = true;
            }
        }
        if (handle == null) {
            if (worker != null) worker.interrupt();
            else
                finish(
                        operation,
                        new Frame.Done(new Scope.GlobalScope(), null, -1, null, null, target));
            return Frame.CancelStatus.ACCEPTED;
        }
        try {
            if (!handle.requestCancellation()) return Frame.CancelStatus.REJECTED;
            synchronized (this) {
                operation.cancelled = true;
            }
            return Frame.CancelStatus.ACCEPTED;
        } catch (RuntimeException failure) {
            return Frame.CancelStatus.REJECTED;
        }
    }

    public @NonNull CompletableFuture<String> input(
            @NonNull String text, boolean mask, long timeoutMillis) {
        var operation = execution.get();
        if (operation == null) throw new IllegalStateException("No active command");
        var future = new CompletableFuture<String>();
        synchronized (this) {
            if (!valid(operation)) throw new CancellationException("Connection authority expired");
            if (operation.input != null && !operation.input.isDone())
                throw new IllegalStateException("Input already pending");
            operation.input = future;
        }
        send(new Frame.Prompt(text, mask, operation.seq));
        return future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private synchronized void receiveInput(Frame.@NonNull Input input) {
        var operation = operations.get(input.seq());
        if (operation != null && valid(operation) && operation.input != null)
            operation.input.complete(input.raw());
    }

    private void resolveVeto(Frame.@NonNull VetoResult result) {
        var user = userId();
        if (user == null || !user.equals(result.scope().userId()))
            throw new IllegalArgumentException("Approval not found");
        var session = runtime.requireOwned(result.scope().session(), user);
        session.requireAttached(this);
        var operation = execution.get();
        if (operation == null
                || !authorizeControl(
                        operation,
                        () -> {
                            session.requireAttached(this);
                            return session.resolveVeto(user, result);
                        })) throw new IllegalStateException("Approval no longer pending");
    }

    private void asyncControl(@NonNull Runnable action, long seq) {
        synchronized (this) {
            if (closed) return;
            if (controlSlots.tryAcquire()) {
                var admittedAuthority = identity;
                workers.execute(
                        () -> {
                            var control =
                                    new ProtocolOperation(
                                            seq, new Frame.Heartbeat(), admittedAuthority);
                            execution.set(control);
                            var context =
                                    new ConnectionExecutionScope(admittedAuthority.user(), locale);
                            try (context) {
                                if (valid(control)) action.run();
                            } catch (Throwable failure) {
                                send(new Frame.Error("Control could not be processed", seq));
                            } finally {
                                execution.remove();
                                controlSlots.release();
                            }
                        });
                return;
            }
        }
        send(new Frame.Error("Too many pending controls", seq));
    }

    private boolean valid(@NonNull ProtocolOperation operation) {
        return !closed
                && identity.version() == operation.authority.version()
                && authenticated.getAsBoolean();
    }

    private @NonNull UUID requireUser(@NonNull ProtocolOperation operation) {
        var user = operation.authority.user();
        if (user == null || !valid(operation))
            throw new IllegalStateException("Authentication required");
        return user;
    }

    public void output(@NonNull String message) {
        send(
                new Delta(
                        new Scope.GlobalScope(),
                        outputSequence.incrementAndGet(),
                        Instant.now(),
                        new Delta.Message(message, -1)));
    }

    public boolean send(Frame.@NonNull ServerFrame frame) {
        return send(frame, () -> true);
    }

    public boolean send(Frame.@NonNull ServerFrame frame, @NonNull BooleanSupplier attached) {
        var operation = execution.get();
        return send(
                frame,
                operation == null ? identity.version() : operation.authority.version(),
                attached);
    }

    private boolean send(
            Frame.@NonNull ServerFrame frame, long version, @NonNull BooleanSupplier attached) {
        if (closed) return false;
        if (!authenticated.getAsBoolean()) {
            close(CloseReason.UNAUTHORIZED);
            return false;
        }
        var permitted = authorizedOutput(frame, version, attached);
        if (permitted == null) return false;
        try {
            int bytes = FrameCodec.encode(permitted).length;
            if (bytes > FrameCodec.MAX_ENCODED_FRAME_BYTES
                    || !outbox.offer(
                            new OutboundFrame(permitted, version, attached),
                            bytes,
                            10,
                            TimeUnit.SECONDS)) {
                if (closed) return false;
                close(CloseReason.OUTPUT_LIMIT);
                return false;
            }
            synchronized (this) {
                if (closed) return false;
                if (!writing) {
                    writing = true;
                    workers.execute(this::drainOutput);
                }
            }
            return true;
        } catch (InterruptedException interrupted) {
            // Agent cancellation and connection shutdown interrupt output producers.
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean sendSession(
            Frame.@NonNull ServerFrame frame, @NonNull BooleanSupplier attached) {
        return send(frame, identity.version(), attached);
    }

    /** Rechecked by this endpoint immediately before writing queued output. */
    private Frame.ServerFrame authorizedOutput(
            Frame.@NonNull ServerFrame frame, long version, @NonNull BooleanSupplier attached) {
        if (closed
                && !(draining
                        && (frame instanceof Frame.Terminate || frame instanceof Frame.Error)))
            return null;
        if (!attached.getAsBoolean()) return null;
        if (version != identity.version()) {
            if (registry != null
                    && frame instanceof Frame.TurnOutcome outcome
                    && outcome.seq() != 0)
                frame =
                        new Frame.Done(
                                new Scope.GlobalScope(), null, -1, null, null, outcome.seq());
            else return null;
        }
        if (frame instanceof Frame.ScopedFrame scoped) {
            var owner = scoped.scope().userId();
            if (owner != null && !owner.equals(userId())) return null;
        }
        var terminal = sender;
        var commandRegistry = registry;
        if (terminal != null
                && commandRegistry != null
                && frame instanceof Frame.TurnOutcome outcome
                && outcome.seq() != 0) return commandRegistry.terminalOutcome(terminal, outcome);
        return frame;
    }

    public boolean send(
            Scope.@NonNull AgentScope scope,
            @NonNull VetoPrompt prompt,
            @NonNull BooleanSupplier attached) {
        if (!scope.agent().equals(prompt.agentId()))
            throw new IllegalArgumentException("Approval belongs to another agent");
        return sendSession(vetoFrame(scope, prompt), attached);
    }

    public static Frame.@NonNull Veto vetoFrame(
            Scope.@NonNull AgentScope scope, @NonNull VetoPrompt prompt) {
        return new Frame.Veto(
                scope,
                prompt.callId(),
                prompt.tool(),
                prompt.scenario().name(),
                prompt.options().stream().map(Enum::name).toList(),
                prompt.args(),
                prompt.danger(),
                prompt.relevance(),
                prompt.seq());
    }

    @Override
    public void close() {
        close(CloseReason.NORMAL);
    }

    public void close(@NonNull CloseReason reason) {
        finish(reason, false);
    }

    public boolean isClosed() {
        return closed;
    }

    private void finish(@NonNull CloseReason reason, boolean drain) {
        List<@NonNull ProtocolOperation> closing;
        boolean closeOutput;
        synchronized (this) {
            if (closed && (!draining || drain)) return;
            draining = drain;
            closed = true;
            closing = List.copyOf(operations.values());
            operations.clear();
            queued.clear();
            active = null;
            outbox.close(drain);
            closeOutput = !drain || (!writing && outbox.isEmpty());
            if (closeOutput) draining = false;
            else if (!writing) {
                writing = true;
                workers.execute(this::drainOutput);
            }
        }
        runtime.disconnect(this);
        for (var operation : closing) {
            // Already admitted agent execution survives a frontend disconnect. Preparation and
            // blocking terminal input do not own an agent and can be released immediately.
            if (operation.input != null)
                operation.input.completeExceptionally(
                        new CancellationException("Connection closed"));
            var worker = operation.worker;
            if (worker != null) worker.interrupt();
        }
        if (closeOutput) onClose.accept(reason);
    }

    private void drainOutput() {
        try {
            while (true) {
                OutboundFrame pending;
                boolean drained;
                synchronized (this) {
                    pending = outbox.poll();
                    drained = pending == null && draining;
                    if (pending == null) {
                        writing = false;
                        if (drained) draining = false;
                    }
                }
                if (pending == null) {
                    if (drained) onClose.accept(CloseReason.PROTOCOL_ERROR);
                    return;
                }
                if (!authenticated.getAsBoolean()) {
                    close(CloseReason.UNAUTHORIZED);
                    break;
                }
                var frame =
                        authorizedOutput(pending.frame(), pending.version(), pending.attached());
                if (frame != null) output.send(frame);
            }
        } catch (Throwable failure) {
            if (!closed) log.warn("Protocol output failed for '{}'", id, failure);
            close(CloseReason.IO_ERROR);
        }
        synchronized (this) {
            writing = false;
        }
    }

    /** Transport-independent reason supplied to the server that owns the native connection. */
    public enum CloseReason {
        NORMAL,
        PROTOCOL_ERROR,
        IO_ERROR,
        OUTPUT_LIMIT,
        SERVER_SHUTDOWN,
        UNAUTHORIZED,
        FRAME_TOO_LARGE
    }

    private record OutboundFrame(
            Frame.@NonNull ServerFrame frame, long version, @NonNull BooleanSupplier attached) {}

    /**
     * Immutable authentication snapshot; the version changes whenever connection authority changes.
     */
    private record ConnectionIdentity(UUID user, String username, long version) {}

    /**
     * Execution state for one correlated protocol request. ProtocolConnection owns admission,
     * cancellation and settlement; this state is not independently thread-safe. The volatile
     * cancellation flag is also read by the executing worker.
     */
    private static final class ProtocolOperation {
        private final long seq;
        private final Frame.@NonNull ClientFrame frame;
        private @NonNull ConnectionIdentity authority;
        private Thread worker;
        private RequestHandle handle;
        private @NonNull Scope scope = new Scope.GlobalScope();
        private CompletableFuture<String> input;
        private volatile boolean cancelled;
        private @NonNull CompletableFuture<Void> cancelAcknowledged =
                CompletableFuture.completedFuture(null);

        private ProtocolOperation(
                long seq, Frame.@NonNull ClientFrame frame, @NonNull ConnectionIdentity authority) {
            this.seq = seq;
            this.frame = frame;
            this.authority = authority;
        }
    }
}
