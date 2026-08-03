package top.focess.veto.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.identity.AgentPersona;
import top.focess.veto.agent.identity.Role;
import top.focess.veto.agent.intercept.ApprovalDecision;
import top.focess.veto.agent.intercept.Gateway;
import top.focess.veto.agent.intercept.GatewayResult;
import top.focess.veto.agent.intercept.HitlRegistry;
import top.focess.veto.agent.intercept.IngressDefense;
import top.focess.veto.agent.intercept.InterceptResolution;
import top.focess.veto.agent.intercept.LoopInterceptor;
import top.focess.veto.agent.intercept.VetoOption;
import top.focess.veto.agent.intercept.VetoPrompt;
import top.focess.veto.agent.loop.ActionsProgram;
import top.focess.veto.agent.loop.ActionsProgramParser;
import top.focess.veto.agent.loop.CheckEvaluator;
import top.focess.veto.agent.loop.CompiledPrompt;
import top.focess.veto.agent.loop.GenerateAction;
import top.focess.veto.agent.loop.LoopBreaker;
import top.focess.veto.agent.loop.ProgramValidator;
import top.focess.veto.agent.loop.PromptCompiler;
import top.focess.veto.agent.loop.ResponseEnforcer;
import top.focess.veto.agent.loop.Scope;
import top.focess.veto.agent.loop.StopAction;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.LlmException;
import top.focess.veto.llm.exceptions.ModelSchemaException;
import top.focess.veto.vault.UserContext;

/**
 * The execution engine — the ReAct loop running in one of two modes (guided or autonomous) on the
 * agent's virtual thread. Owned by {@link VetoAgent}; never exposed to workflows/transports. The
 * code is synchronous-style while physically non-blocking on Java 21 virtual threads.
 *
 * <p>Autonomous: think → act → observe → assess, full reasoning each step. Guided: drives a typed
 * actions program (IR) — {@code tool} actions may skip the model call; {@code generate} is the only
 * model-invoking action; {@code goto}/{@code conditional_goto}/{@code STOP} are zero-call.
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_SCHEMA_RETRIES = 2;

    // --- identity / deps ---
    private final @NonNull String agentId;
    // The persona + its tool-name view are mutable: the delegation transform re-scopes them from
    // STANDALONE to LEADER (and back on disband) in place. Volatile - written once per transform on
    // the loop thread, read on the same thread each compile; the volatile keeps the view consistent
    // for inspection from other threads.
    private volatile @NonNull AgentPersona persona;
    private volatile @NonNull Set<String> whitelistedTools;
    private final @NonNull ToolEngine mcpEngine;
    private final @NonNull Gateway gateway;
    private final @NonNull HitlRegistry hitlRegistry;
    private final @NonNull IngressDefense ingressDefense;
    private final @NonNull List<LoopInterceptor> interceptors;
    private final @NonNull PromptCompiler promptCompiler;
    private final @NonNull UniformLLMCaller caller;
    private final @NonNull ObjectMapper objectMapper;
    private final @NonNull LoopBreaker breaker;
    private final @NonNull ReadHistory readHistory;
    // The Part-8 Delta-broker seam: when present, each loop emission is published as a DeltaFrame
    // (per-session, broker-assigned sequence) so transports (WebSocket) can stream it. Nullable so
    // non-Spring callers (tests) keep working without a broker.
    private final @Nullable DeltaBroker deltaBroker;
    // The session this agent's turns belong to. Defaults to the agent's own id (a UUID) at
    // construction; the DB-backed create path overrides it with the real session id so the
    // turn_records.session_id column groups a session's 1+N agent streams correctly. Volatile: set
    // once at creation before the loop processes any turn.
    private volatile @NonNull UUID sessionId;
    // The raw-turn write-through log. Nullable — when null (tests / no durability configured),
    // appendTurn only updates the in-memory history; when present, each turn is also persisted to
    // the raw-turn audit/replay log.
    private final top.focess.veto.memory.@Nullable TurnLogService turnLogService;
    private final @NonNull UUID userId;
    // The session owner (username) whose model-tier profile resolves this agent's tier. Threaded
    // into each tool's ToolCallContext so group-spawned Mates / Leaders resolve against the user's
    // active profile (per-user model-tier configuration). Nullable in legacy/test paths that bypass
    // session activation (those tests stub the registry). Volatile: set once at creation before the
    // loop processes any tool call.
    private volatile @Nullable String owner;
    // The group this agent belongs to (the group it leads, or the group it is a Mate of); null for
    // a single-agent (STANDALONE) loop. Stamped by group-spawning code and threaded into each
    // tool's ToolCallContext so group-scoped tools (create_node, post_message, ...) resolve the
    // caller's group without a groupId argument.
    private volatile @Nullable UUID groupId;

    // --- model binding (provider/model/credential), set per prompt ---
    private volatile @NonNull LlmBinding binding;

    // ── Loop discipline (structural enforcement of the system-prompt rules) ──
    //
    // The system prompt forbids "premature stop" (emitting a final message after one tool call
    // when the user asked a question) and "duplicate calls" (re-issuing the same tool+args without
    // an intervening state change). A small/weak model can lose attention to those rules under long
    // system-prompt pressure, so the loop itself enforces them — the model prompt is the carrot,
    // the loop is the stick. Both state slices are loop-thread-only (no synchronization needed).
    //
    // recentToolCalls: a bounded ring of (toolName, args, result) for the last N read-only calls.
    // executeToolCalls consults this to short-circuit a duplicate. The result is injected as an
    // error observation so the model sees the prior body instead of re-running the tool.
    private final java.util.@NonNull Deque<RecentCall> recentToolCalls =
            new java.util.ArrayDeque<>();
    private static final int RECENT_CALL_WINDOW = 8;

    // forcedContinuations: the count of "force-progress" re-prompts the loop has issued in the
    // current episode after catching a premature stop. Capped so a model that keeps re-emitting
    // the same banned phrase can't loop forever; after the cap, the loop honors the stop.
    private int forcedContinuations = 0;
    private static final int MAX_FORCED_CONTINUATIONS = 2;

    /** Bounded record of a recent tool call + its result, for duplicate detection. */
    private record RecentCall(
            @NonNull String toolName,
            java.util.@NonNull Map<String, Object> args,
            @NonNull String body) {}

    // The pre-transform STANDALONE persona + binding, stashed when the agent transforms into a
    // Leader so disband_group can reverse the transform and restore them. Null when not leading.
    private volatile @Nullable AgentPersona preTransformPersona;
    private volatile @Nullable LlmBinding preTransformBinding;

    // --- loop state (mutated only by the runner's virtual thread) ---
    private final @NonNull BlockingQueue<AgentAction> actionQueue = new LinkedBlockingQueue<>();
    private volatile @NonNull AgentState state = AgentState.IDLE;
    private final @NonNull List<TurnRecord> history = new ArrayList<>();
    private int turnNumber = 0;
    private boolean guided = false;
    private @Nullable ActionsProgram activeProgram = null;
    private int programCounter = 0;
    private int currentSteps = 0;
    private @NonNull Scope scope;
    private @NonNull CompletableFuture<AgentResult> resultFuture =
            CompletableFuture.completedFuture(null);
    private @Nullable Consumer<AgentResult> callback;
    private volatile boolean sessionAlive = true;
    private double correctionFactor = 1.0;
    private long lastEstimatedTokens = 0;

    // User-facing message listeners (the emission seam). emitMessage notifies these so a
    // transport (the terminal PromptHandler) can forward each assistantResponse to its client as a
    // Delta while the loop runs. A JVM EventBus + ZmqServer Delta-frame broker will sit between
    // this
    // seam and the wire; until then the listener is the direct handoff.
    private final @NonNull CopyOnWriteArrayList<Consumer<String>> messageListeners =
            new CopyOnWriteArrayList<>();

    // Interim-thought listeners (parallel to messageListeners). emitThought notifies these so a
    // transport can forward each assistantThought to its client as a thought-kind Delta - rendered
    // distinct (muted/dim) from the user-facing message. Thoughts stream before the matching
    // message because appendThought runs before emitMessage in the loop.
    private final @NonNull CopyOnWriteArrayList<Consumer<String>> thoughtListeners =
            new CopyOnWriteArrayList<>();

    // HITL veto listeners (the veto emission seam, parallel to messageListeners). emitVetoRequired
    // notifies these so a transport can render a picker (an IpcFrame.Prompt with a VetoPayload) and
    // route the user's reply back to resolve the parked veto. The agent parks in HitlRegistry
    // regardless; the listener only advertises the prompt.
    private final @NonNull CopyOnWriteArrayList<Consumer<VetoPrompt>> vetoListeners =
            new CopyOnWriteArrayList<>();

    // Tool-call listeners (the transparency emission seam, parallel to messageListeners).
    // emitToolCall notifies these so a transport (the terminal PromptHandler) can forward each
    // tool call the agent is about to execute as an IpcFrame.ToolCall - analogous to Claude
    // Code's per-tool operation indicator. Fires on the agent's virtual thread inside appendTurn
    // after the durable TOOL_CALL turn is persisted, so listeners never see a turn the audit
    // log lost.
    private final @NonNull CopyOnWriteArrayList<Consumer<IpcFrame.ToolCall>> toolCallListeners =
            new CopyOnWriteArrayList<>();

    // Tool-result listeners (parallel to toolCallListeners). emitToolResult forwards the framed
    // observation text (what the model actually sees) so the terminal can render the body and
    // the user can verify exactly what was fed back to the agent. Fires after the durable
    // TOOL_RESPONSE turn is persisted.
    private final @NonNull CopyOnWriteArrayList<Consumer<IpcFrame.ToolResult>> toolResultListeners =
            new CopyOnWriteArrayList<>();

    public AgentRunner(
            @NonNull String agentId,
            @NonNull AgentPersona persona,
            @NonNull ToolEngine mcpEngine,
            @NonNull Gateway gateway,
            @NonNull HitlRegistry hitlRegistry,
            @NonNull IngressDefense ingressDefense,
            @Nullable List<LoopInterceptor> interceptors,
            @NonNull PromptCompiler promptCompiler,
            @NonNull UniformLLMCaller caller,
            @NonNull ObjectMapper objectMapper,
            long maxCallsPerEpisode,
            @NonNull LlmBinding binding,
            @Nullable DeltaBroker deltaBroker,
            @NonNull UUID userId,
            top.focess.veto.memory.@Nullable TurnLogService turnLogService) {
        this.agentId = agentId;
        this.persona = persona;
        this.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.mcpEngine = mcpEngine;
        this.gateway = gateway;
        this.hitlRegistry = hitlRegistry;
        this.ingressDefense = ingressDefense;
        this.interceptors = interceptors == null ? List.of() : interceptors;
        this.promptCompiler = promptCompiler;
        this.caller = caller;
        this.objectMapper = objectMapper;
        this.breaker = new LoopBreaker(maxCallsPerEpisode);
        this.readHistory = gateway.readHistory();
        this.binding = binding;
        this.scope = new Scope(objectMapper);
        this.deltaBroker = deltaBroker;
        // agentId is the persona id (a UUID string — see AgentService.createAgent); derive the
        // per-session frame key once. Fail-fast if a non-UUID id ever reaches here.
        this.sessionId = UUID.fromString(agentId);
        this.userId = userId;
        // Nullable: non-Spring callers (tests) pass null so turns are not logged.
        this.turnLogService = turnLogService;
    }

    // ── Virtual-thread loop ────────────────────────────────────────────────

    public void run() {
        // Stamp the session owner onto the agent's virtual thread so credential resolution on the
        // LLM-call path (CredentialResolver → KeysteadVault.currentHandle → UserContext.get) and
        // the embedder path resolve against the owner's vault rather than the single-active-handle
        // fallback. Owner is set once by AgentService before this thread starts, so a single set at
        // entry covers every turn; clear on exit so the thread never leaks a stale user.
        if (owner != null) {
            UserContext.set(owner);
        }
        try {
            while (sessionAlive && state != AgentState.TERMINATED) {
                try {
                    AgentAction action = actionQueue.take();
                    if (action instanceof AgentAction.TerminateAction) {
                        transitionTo(AgentState.TERMINATED);
                        break;
                    }
                    if (action instanceof AgentAction.PauseAction) {
                        transitionTo(AgentState.PAUSED);
                        continue;
                    }
                    if (action instanceof AgentAction.ResumeAction) {
                        transitionTo(AgentState.RUNNING);
                        continue;
                    }
                    if (action instanceof AgentAction.CompactAction) {
                        transitionTo(AgentState.RUNNING);
                        try {
                            processCompaction();
                            completeSuccess();
                        } catch (Exception e) {
                            log.error("Agent {} compaction failed", agentId, e);
                            completeFailure(e.getMessage());
                        } finally {
                            transitionTo(AgentState.IDLE);
                        }
                        continue;
                    }
                    if (action instanceof AgentAction.UserPromptAction upa) {
                        transitionTo(AgentState.RUNNING);
                        try {
                            processUserPrompt(upa.prompt());
                            completeSuccess();
                        } catch (BreakerTripException e) {
                            completeBreaker();
                        } catch (Exception e) {
                            log.error("Agent {} task failed", agentId, e);
                            completeFailure(e.getMessage());
                        } finally {
                            transitionTo(AgentState.IDLE);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            UserContext.clear();
        }
    }

    // ── Episode setup + autonomous loop ─────────────────────────────────────

    private void processUserPrompt(@NonNull String prompt) {
        // Fresh UserPromptAction: reset guided state, program counter, and the loop-discipline
        // state (recent-call ring + forced-continuation counter). The discipline state is per-
        // episode: a duplicate-call in episode N is not a duplicate in episode N+1, and a forced
        // continuation in episode N must not carry over.
        appendTurn(TurnRecord.userPrompt(++turnNumber, prompt));
        this.guided = false;
        this.activeProgram = null;
        this.programCounter = 0;
        this.breaker.newEpisode();
        this.scope = new Scope(objectMapper);
        this.recentToolCalls.clear();
        this.forcedContinuations = 0;

        if (activeProgram != null) {
            runGuided();
        } else {
            runAutonomous();
        }
    }

    private void processCompaction() {
        int lastInitIndex = -1;
        synchronized (history) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i).type() == TurnType.AGENT_INIT) {
                    lastInitIndex = i;
                    break;
                }
            }
        }
        int anchorIndex = lastInitIndex != -1 ? lastInitIndex : 0;

        List<TurnRecord> workTurns = new ArrayList<>();
        synchronized (history) {
            if (anchorIndex >= history.size() - 1) {
                emitMessage("Nothing to compact.");
                return;
            }
            for (int i = anchorIndex + 1; i < history.size(); i++) {
                workTurns.add(history.get(i));
            }
        }

        String finalSummary = computeCompactionSummary(workTurns);

        appendTurn(TurnRecord.rewind(++turnNumber, 1));
        appendTurn(TurnRecord.compactionSummary(++turnNumber, finalSummary));
        emitMessage("Compaction complete. Summarized " + workTurns.size() + " turns.");
    }

    /**
     * Summarizes a slice of work turns into a structured JSON record (chunked, then combined).
     * Shared by {@link #processCompaction} (the explicit compact action) and {@link
     * #transformToLeader} (the delegation transform carries the essence of the prior standalone
     * session forward as a COMPACTION_SUMMARY). Returns {@code "{}"} when there is nothing to
     * summarize; never null.
     */
    private @NonNull String computeCompactionSummary(@NonNull List<TurnRecord> workTurns) {
        if (workTurns.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        for (TurnRecord turn : workTurns) {
            sb.append("Turn ")
                    .append(turn.turnNumber())
                    .append(" (")
                    .append(turn.type())
                    .append("):\n");
            try {
                sb.append(objectMapper.writeValueAsString(turn.payload())).append("\n\n");
            } catch (Exception e) {
                sb.append(turn.payload().toString()).append("\n\n");
            }
        }
        String contentToCompact = sb.toString();

        List<String> chunks = new ArrayList<>();
        int chunkSize = 60000;
        for (int i = 0; i < contentToCompact.length(); i += chunkSize) {
            chunks.add(
                    contentToCompact.substring(
                            i, Math.min(i + chunkSize, contentToCompact.length())));
        }

        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            String systemPrompt =
                    "Summarize the following conversation segment into a structured record. "
                            + "This is chunk "
                            + (i + 1)
                            + " of "
                            + chunks.size()
                            + ". Preserve specific facts. Output ONLY valid JSON matching this schema:\n"
                            + "{\n"
                            + "  \"files_touched\": [\"paths\"],\n"
                            + "  \"changes_made\": [\"specific edits with file paths\"],\n"
                            + "  \"errors_encountered\": [{\"error\": \"...\", \"file\": \"...\", \"resolved\": true/false}],\n"
                            + "  \"decisions\": [\"key decisions and why\"],\n"
                            + "  \"pending\": [\"started but incomplete tasks\"],\n"
                            + "  \"user_feedback\": [\"explicit instructions, vetoes, corrections\"]\n"
                            + "}";
            String rawSummary = callCompactor(systemPrompt, chunk);
            summaries.add(rawSummary);
        }

        if (summaries.size() == 1) {
            return summaries.get(0);
        }
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            combined.append("Summary ")
                    .append(i + 1)
                    .append(":\n")
                    .append(summaries.get(i))
                    .append("\n\n");
        }
        String systemPrompt =
                "Summarize the following combined conversation summaries into a single final structured record. Output ONLY valid JSON matching this schema:\n"
                        + "{\n"
                        + "  \"files_touched\": [\"paths\"],\n"
                        + "  \"changes_made\": [\"specific edits with file paths\"],\n"
                        + "  \"errors_encountered\": [{\"error\": \"...\", \"file\": \"...\", \"resolved\": true/false}],\n"
                        + "  \"decisions\": [\"key decisions and why\"],\n"
                        + "  \"pending\": [\"started but incomplete tasks\"],\n"
                        + "  \"user_feedback\": [\"explicit instructions, vetoes, corrections\"]\n"
                        + "}";
        return callCompactor(systemPrompt, combined.toString());
    }

    private @NonNull String callCompactor(
            @NonNull String systemPrompt, @NonNull String userPrompt) {
        List<ChatMessage> messages =
                List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userPrompt));
        VetoRequest request =
                new VetoRequest(
                        systemPrompt,
                        userPrompt,
                        List.of(),
                        binding.provider(),
                        binding.model(),
                        binding.credentialKey(),
                        binding.options(),
                        messages,
                        null,
                        binding.baseUrl());
        VetoResponse response = caller.call(request);
        return response.message() != null && !response.message().isBlank()
                ? response.message()
                : (response.thought() != null ? response.thought() : "{}");
    }

    private void runAutonomous() {
        while (state == AgentState.RUNNING) {
            if (breaker.shouldTrip()) {
                emitMessage(LoopBreaker.tripNotice());
                throw new BreakerTripException();
            }
            boolean guidedSwitch = false;
            VetoResponse response = callModel(guidedSwitch);
            breaker.recordModelCall();

            // Read NEXT-status features (the mode the NEXT iteration enters).
            if (response.features() != null) {
                this.guided = response.features().guided();
            }

            // Agent requested guided mode for the next iteration → load + validate program.
            if (this.guided && response.actions() != null) {
                if (loadProgram(response.actions())) {
                    appendThought(response);
                    if (response.message() != null && !response.message().isBlank()) {
                        emitMessage(response.message());
                    }
                    runGuided();
                    return; // guided mode finished, back to idle
                }
                // invalid program → stay autonomous (rejection fed back as observation)
                appendObservation(
                        "guided_program_rejected",
                        "actions failed validation; staying autonomous.");
                continue;
            }

            appendThought(response);
            if (response.message() != null && !response.message().isBlank()) {
                emitMessage(response.message());
            }
            if (response.hasCalls()) {
                executeToolCalls(assignCallIds(response.calls()));
            } else if (isPrematureStop(response)) {
                // The model emitted a stopping message but the content matches one of the banned
                // "no task / what would you like" patterns the system prompt explicitly forbids
                // (the model lost attention to the rule under long system-prompt pressure).
                // Inject a force-progress reminder as an observation and re-call the model. Capped
                // at MAX_FORCED_CONTINUATIONS so a model that keeps re-emitting the same phrase
                // cannot loop forever; once capped, the loop honors the stop.
                if (forcedContinuations >= MAX_FORCED_CONTINUATIONS) {
                    log.warn(
                            "Agent {} hit MAX_FORCED_CONTINUATIONS={} after premature-stop"
                                    + " detections; honoring stop with last message",
                            agentId,
                            MAX_FORCED_CONTINUATIONS);
                    return;
                }
                forcedContinuations++;
                String userTask = lastUserPromptExcerpt();
                String reminder =
                        "[loop-enforcer] Your previous response did not advance the user's task."
                                + " The user asked: \""
                                + userTask
                                + "\". You must either (a) make a concrete tool call that"
                                + " advances an answer, or (b) compose a substantive message that"
                                + " DIRECTLY answers the question with the information you"
                                + " already have. Do NOT end with 'what would you like me to"
                                + " do?' or 'no task was given' — those are forbidden by the"
                                + " system prompt and the loop will keep forcing you back here"
                                + " until you do one of (a) or (b).";
                appendObservation("loop_enforcer", reminder);
                continue;
            } else {
                // No tool calls: the agent has emitted its answer with nothing further to act
                // on. Termination routes on call presence - calls absent means stop. The agent
                // can call `think` to continue its thought flow for another step when it wants
                // to reason more without a concrete action. Stop the episode here; the emitted
                // message is the final answer.
                return;
            }
        }
    }

    /**
     * Returns {@code true} when the model's stopping response is a "premature stop" — a final
     * message that contains one of the banned phrases the system prompt explicitly forbids (the
     * model has lost attention to the user request and is asking the user to restate it). The check
     * is intentionally a small, conservative phrase set so it triggers only on the patterns that
     * have actually caused premature stops in practice; a too-broad detector would mis-fire on
     * legitimate "what next?" answers where the user genuinely gave no task.
     *
     * <p>Detection is case-insensitive and operates on the model's emitted message. The thought is
     * not checked (it is private reasoning and the system prompt explicitly allows the model to say
     * "no task" there during early planning — only the user-facing {@code message} is gated).
     */
    private boolean isPrematureStop(@NonNull VetoResponse response) {
        String msg = response.message();
        if (msg == null || msg.isBlank()) {
            return false;
        }
        String lower = msg.toLowerCase();
        // Banned phrase set — each entry is a substring that, if present in the model's user-
        // facing message, signals the model has lost the user request and is asking them to
        // restate it. Phrases are kept literal (not regex) so a future tightening is mechanical.
        String[] banned = {
            "what would you like me to do",
            "what would you like to do",
            "no task was given",
            "no specific task was given",
            "no task is stated",
            "no task is stated yet",
            "i don't see a request",
            "i don't see a specific request",
            "i don't see any request",
            "i don't see a task",
            "no visible user request",
            "no visible request",
            "no specific request",
            "no request was given",
            "no request is stated",
            "let me know what you'd like",
            "let me know what you would like",
            "let me know what you need",
            "let me know what you want",
            "tell me what you'd like",
            "tell me what you would like",
            "could you clarify what",
            "could you clarify your",
            "could you please clarify",
            "please clarify what",
            "could you provide more context",
            "what would you like me to help with",
            "how can i help you",
            "how can i assist you"
        };
        for (String b : banned) {
            if (lower.contains(b)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the most recent user prompt's text (or a placeholder if no user prompt is on the
     * history yet) so the force-progress reminder can quote the user's actual task. Truncated to
     * keep the reminder short.
     */
    private @NonNull String lastUserPromptExcerpt() {
        for (int i = history.size() - 1; i >= 0; i--) {
            TurnRecord t = history.get(i);
            if (t.type() == TurnType.USER_PROMPT) {
                Object content = t.payload().get("content");
                if (content instanceof String s && !s.isBlank()) {
                    String trimmed = s.strip();
                    if (trimmed.length() > 200) {
                        return trimmed.substring(0, 197) + "...";
                    }
                    return trimmed;
                }
            }
        }
        return "(no user prompt on record)";
    }

    // ── Guided loop (drives the actions program IR) ─────────────────────────

    private void runGuided() {
        while (state == AgentState.RUNNING && activeProgram != null) {
            if (programCounter < 0 || programCounter >= activeProgram.actions().size()) {
                escapeToAutonomous("program counter out of bounds");
                return;
            }
            var action = activeProgram.actions().get(programCounter);
            currentSteps++;

            switch (action) {
                case top.focess.veto.agent.loop.ToolAction tool -> {
                    ToolCall call =
                            new ToolCall(tool.tool(), tool.resolveInputs(scope), nextCallId());
                    ToolResult result = executeOneCall(call);
                    scope.bindTool(tool.outputs(), result);
                    programCounter++;
                }
                case GenerateAction gen -> {
                    if (breaker.shouldTrip()) {
                        emitMessage(LoopBreaker.tripNotice());
                        throw new BreakerTripException();
                    }
                    VetoResponse response = callGenerate(gen);
                    breaker.recordModelCall();
                    scope.bindGenerate(gen.outputs(), response);
                    programCounter++;
                }
                case top.focess.veto.agent.loop.GotoAction gt -> programCounter = gt.index();
                case top.focess.veto.agent.loop.ConditionalGotoAction cg -> {
                    boolean passed = CheckEvaluator.evaluate(cg.check(), scope, currentSteps);
                    programCounter = cg.nextPc(passed, programCounter + 1);
                }
                case StopAction stop -> {
                    String result =
                            stop.resultBinding() != null
                                    ? scope.opt(stop.resultBinding())
                                            .map(Object::toString)
                                            .orElse(scope.synthesize())
                                    : scope.synthesize();
                    emitMessage(result);
                    escapeToAutonomous("STOP");
                    return;
                }
                default -> {
                    escapeToAutonomous("unknown action: " + action);
                    return;
                }
            }

            if (!guided) {
                escapeToAutonomous("agent voluntary deviation");
                return;
            }
        }
    }

    private @NonNull VetoResponse callGenerate(@NonNull GenerateAction gen) {
        // A generate action invokes the model within the shared conversation with its scoped
        // prompt.
        // The generate prompt is fed as a user turn; the model responds in veto_pulse.
        appendTurn(TurnRecord.userPrompt(++turnNumber, gen.resolvePrompt(scope)));
        VetoResponse response = callModel(false);
        if (gen.thought() == null || gen.thought()) {
            appendThought(response);
        }
        return response;
    }

    private boolean loadProgram(@NonNull JsonNode node) {
        try {
            ActionsProgram program = ActionsProgramParser.parse(node);
            ProgramValidator.validate(program);
            this.activeProgram = program;
            this.programCounter = 0;
            this.currentSteps = 0;
            return true;
        } catch (ProgramValidator.InvalidProgramException e) {
            log.warn("Agent {} actions program rejected: {}", agentId, e.getMessage());
            return false;
        }
    }

    private void escapeToAutonomous(@NonNull String reason) {
        this.activeProgram = null;
        this.programCounter = 0;
        this.guided = false;
        appendObservation(
                "guided_escape",
                "Guided mode exited: "
                        + reason
                        + ". Scope preserved with "
                        + scope.size()
                        + " bindings.");
    }

    // ── The model call (compile + dispatch + enforce, with schema retry) ────

    private @NonNull VetoResponse callModel(boolean guidedSwitch) {
        CompiledPrompt compiled =
                promptCompiler.compile(
                        persona,
                        gateway.workspace(),
                        binding.systemPromptBase(),
                        List.copyOf(history),
                        guidedSwitch,
                        this.correctionFactor);
        this.lastEstimatedTokens = compiled.estimatedTokens();
        VetoRequest request = buildRequest(compiled);
        ModelSchemaException last = null;
        for (int attempt = 0; attempt <= MAX_SCHEMA_RETRIES; attempt++) {
            VetoResponse response;
            try {
                response = caller.call(request);
                top.focess.veto.llm.core.LlmSystemUsage.Usage usage =
                        top.focess.veto.llm.core.LlmSystemUsage.getAndClear();
                if (usage != null && this.lastEstimatedTokens > 0) {
                    double ratio = (double) usage.promptTokens() / this.lastEstimatedTokens;
                    this.correctionFactor = this.correctionFactor * 0.9 + ratio * 0.1;
                }
            } catch (LlmException e) {
                // LLM failure → record error, break the loop ( table: LLM Error → IDLE).
                appendObservation("llm_error", e.getMessage());
                this.state = AgentState.IDLE;
                throw e;
            }
            try {
                return ResponseEnforcer.enforce(response, guidedSwitch);
            } catch (ModelSchemaException e) {
                last = e;
                log.warn(
                        "Agent {} schema violation (attempt {}): {}",
                        agentId,
                        attempt + 1,
                        e.getMessage());
                if (attempt == MAX_SCHEMA_RETRIES) {
                    throw e;
                }
                // Inject an ephemeral rejection message so the model knows what to fix on retry.
                request = injectSchemaRejection(request, e);
            }
        }
        throw last;
    }

    private @NonNull VetoRequest buildRequest(@NonNull CompiledPrompt compiled) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(compiled.systemMessage()));
        messages.addAll(compiled.messages());
        LlmBinding b = binding;
        return new VetoRequest(
                compiled.systemMessage(),
                messages.isEmpty() ? "" : messages.get(messages.size() - 1).content(),
                compiled.tools(),
                b.provider(),
                b.model(),
                b.credentialKey(),
                b.options(),
                messages,
                compiled.responseSchema(),
                b.baseUrl());
    }

    /**
     * Injects an ephemeral rejection message for a schema-violation retry. The rejection is added
     * only to the {@link VetoRequest} for the next attempt — it is never appended to {@link
     * #history}, so a crash after a successful retry loses it (acceptable). All binding fields
     * (provider/model/credential/options/schema) are preserved verbatim.
     */
    private @NonNull VetoRequest injectSchemaRejection(
            @NonNull VetoRequest request, @NonNull ModelSchemaException e) {
        String rejection =
                String.format(
                        "Your previous response was rejected due to a schema violation: %s.\n"
                                + "Expected: %s.\n"
                                + "Please regenerate a valid response matching the veto_pulse schema.",
                        e.getMessage(), getExpectedDescription(e));
        List<ChatMessage> augmented = new ArrayList<>(request.messages());
        augmented.add(ChatMessage.user(rejection));
        return new VetoRequest(
                request.systemPrompt(),
                request.userPrompt(),
                request.tools(),
                request.providerType(),
                request.modelName(),
                request.credentialKey(),
                request.options(),
                augmented,
                request.responseSchema(),
                request.baseUrl());
    }

    /**
     * Maps a {@link ModelSchemaException} message to the human-readable behavior the model should
     * adopt on retry. Substring-matched against the canonical messages {@link ResponseEnforcer}
     * emits.
     */
    private @NonNull String getExpectedDescription(@NonNull ModelSchemaException e) {
        String msg = e.getMessage();
        if (msg.contains("message required")) {
            return "message field is required when stopping (no tool calls or actions)";
        }
        if (msg.contains("mutually exclusive")) {
            return "either calls or actions, not both";
        }
        if (msg.contains("guided-switch")) {
            return "actions field is required on a guided-switch turn";
        }
        if (msg.contains("features is required")) {
            return "features field is always required";
        }
        return "valid JSON matching the veto_pulse schema";
    }

    // ── executeToolCalls — the canonical chain ─────────────────────

    private void executeToolCalls(@NonNull List<ToolCall> calls) {
        transitionTo(AgentState.WAITING);
        try {
            // 0. Duplicate-call filter. The system prompt forbids re-listing a directory the
            //    model has already listed in this session; a small/weak model loses attention to
            //    that rule under long system-prompt pressure and re-issues the same call. The
            //    loop enforces it: any read-only (tool, args) that already ran in this episode
            //    is short-circuited with a synthetic observation quoting the prior framed result
            //    (self-describing, the same string the model saw on the first call). Writes are
            //    excluded (re-execution is legitimate under the prompt's write-then-reread
            //    carve-out). Agent tools (create_node, recall_session, write_insight, think)
            //    are excluded — they are not idempotent reads; a re-call with different intent
            //    is a real call, not a mistake.
            List<ToolCall> deduped = applyDuplicateCallFilter(calls);
            if (deduped.size() != calls.size()) {
                log.info(
                        "Agent {}: dedup-filtered {} duplicate call(s) (kept {} fresh)",
                        agentId,
                        calls.size() - deduped.size(),
                        deduped.size());
            }
            if (deduped.isEmpty()) {
                // All calls in this turn were duplicates; synthetic observations were appended as
                // TOOL_RESPONSE turns and the model will re-decide on the next iteration. No
                // further dispatch is needed.
                return;
            }
            calls = deduped;

            // 1. Check phase (screen all calls first)
            List<ApprovalDecision> decisions = new ArrayList<>();
            boolean hasVeto = false;
            boolean hasRefused = false;
            for (ToolCall call : calls) {
                ToolDefinition def = mcpEngine.resolveDefinition(call.toolName());
                if (def == null || def instanceof AgentToolDefinition) {
                    decisions.add(ApprovalDecision.AUTO_APPROVE);
                } else {
                    var result = gateway.screen(call, def);
                    ApprovalDecision decision = hitlRegistry.decide(agentId, call, def, result);
                    decisions.add(decision);
                    if (decision instanceof ApprovalDecision.Prompt) {
                        hasVeto = true;
                    } else if (decision instanceof ApprovalDecision.Refused) {
                        hasRefused = true;
                    }
                }
            }

            // 2. Hold phase
            List<ToolCall> skippedCalls = new ArrayList<>();
            if (hasVeto || hasRefused) {
                boolean batchApproved = true;
                List<ToolCall> resolvedCalls = new ArrayList<>(calls);

                for (int i = 0; i < calls.size(); i++) {
                    ToolCall call = calls.get(i);
                    ApprovalDecision decision = decisions.get(i);
                    ToolDefinition def = mcpEngine.resolveDefinition(call.toolName());

                    if (decision instanceof ApprovalDecision.Refused r) {
                        emitMessage(r.reason());
                        transitionTo(AgentState.INTERCEPTED);
                        hitlRegistry.register(agentId, call.callId());
                        InterceptResolution res = hitlRegistry.await(agentId, call.callId());
                        batchApproved = false;
                        break;
                    } else if (decision instanceof ApprovalDecision.Prompt p) {
                        transitionTo(AgentState.INTERCEPTED);
                        // Register the await target BEFORE advertising the prompt: the veto
                        // listener sends the Prompt synchronously, and the user's reply could
                        // otherwise race register and resolve against a not-yet-registered future
                        // (a hang). EDIT is filtered from the offered set in v1 (a raw-string
                        // reply can't carry edited args).
                        List<VetoOption> offered = VetoOption.withoutEdit(p.options());
                        hitlRegistry.register(agentId, call.callId(), call, def, offered);
                        emitVetoRequired(call, p, offered);
                        InterceptResolution resolution = hitlRegistry.await(agentId, call.callId());

                        if (resolution.option() == VetoOption.DECLINE_AND_CONTINUE) {
                            skippedCalls.add(call);
                        } else if (resolution.isRefusal()) {
                            batchApproved = false;
                            break;
                        } else if (resolution.option() == VetoOption.EDIT
                                && resolution.editedArgs() != null) {
                            ToolCall edited =
                                    new ToolCall(
                                            call.toolName(),
                                            resolution.editedArgs(),
                                            call.callId());
                            var r2 = gateway.screen(edited, def);
                            if (r2 instanceof GatewayResult.Screened sc
                                    && sc.screening().danger() == Danger.CRITICAL) {
                                appendObservation(
                                        call.toolName(),
                                        "Edited call is CRITICAL: " + sc.screening().reason());
                                batchApproved = false;
                                break;
                            }
                            if (r2 instanceof GatewayResult.DriftResult) {
                                appendObservation(call.toolName(), "Edited call drifts.");
                                batchApproved = false;
                                break;
                            }
                            resolvedCalls.set(i, edited);
                        }
                    }
                }

                if (!batchApproved) {
                    // Synthesize ToolResponse(status=REFUSED) for all calls, no execution, go IDLE
                    for (ToolCall call : calls) {
                        appendTurn(TurnRecord.toolCall(++turnNumber, call));
                        appendTurn(
                                TurnRecord.toolResponse(
                                        ++turnNumber, call.callId(), "REFUSED", false));
                    }
                    this.state = AgentState.IDLE;
                    throw new RuntimeException("Veto execution refused");
                }

                // Batch approved! Replace calls with resolvedCalls
                calls = resolvedCalls;
            }

            // 3. Execute phase (all confirmed / skipped)
            for (ToolCall call : calls) {
                if (skippedCalls.contains(call)) {
                    appendTurn(TurnRecord.toolCall(++turnNumber, call));
                    appendTurn(
                            TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
                } else {
                    executeOneConfirmedCall(call);
                }
            }

        } finally {
            if (state == AgentState.WAITING || state == AgentState.INTERCEPTED) {
                transitionTo(AgentState.RUNNING);
            }
        }
    }

    private @NonNull ToolResult executeOneConfirmedCall(@NonNull ToolCall call) {
        ToolDefinition def = mcpEngine.resolveDefinition(call.toolName());
        if (def == null) {
            String obs = "Tool not found: " + call.toolName();
            appendTurn(TurnRecord.toolCall(++turnNumber, call));
            appendObservation(call.toolName(), obs);
            return new ToolResult(call.toolName(), call.callId(), false, obs);
        }

        appendTurn(TurnRecord.toolCall(++turnNumber, call));

        // (c) plugin preAction chain
        for (LoopInterceptor plugin : interceptors) {
            if (!plugin.preAction(agentId, call)) {
                appendObservation(call.toolName(), "Blocked by plugin.");
                return new ToolResult(call.toolName(), call.callId(), false, "blocked by plugin");
            }
        }

        // (d) execute with tool call context (agentId + userId + groupId) threaded through.
        ToolCallContextHolder.set(agentId, userId, groupId, owner);
        try {
            ToolResult result = mcpEngine.execute(call, def);

            // (e) plugin postAction chain
            ToolResult transformed = result;
            for (LoopInterceptor plugin : interceptors) {
                transformed = plugin.postAction(agentId, call, transformed);
            }

            // (f) ingress defense
            String observation =
                    ingressDefense.maskAndFrame(
                            call, def, transformed, ApprovalDecision.AUTO_APPROVE, readHistory);

            // (g) plugin preObservation chain
            for (LoopInterceptor plugin : interceptors) {
                observation = plugin.preObservation(agentId, observation);
            }

            appendTurn(
                    TurnRecord.toolResponse(
                            ++turnNumber, call.callId(), observation, transformed.success()));

            // Record the call so future duplicate-call filter hits can quote the framed
            // observation back to the model. Only read-only native tools are tracked — writes
            // are excluded (a subsequent read of the same path is a legitimate fresh call after
            // the write's side effect), and agent tools are excluded (their behavior is state-
            // dependent and a re-call with the same args may not be a duplicate).
            recordRecentCall(call, observation, def);

            appendTurn(
                    TurnRecord.toolResponse(
                            ++turnNumber, call.callId(), observation, transformed.success()));
            // Drain any turn directives the tool requested during execution (e.g. a RECALL seeded
            // by create_group to re-inject the authored brief). Each is appended with a
            // runner-assigned turn number; the pending record's placeholder turnNumber is rewritten
            // (type + payload preserved). Drained here, before clear() in the finally, so a tool
            // that threw never leaks a directive to the next call on this thread.
            for (TurnRecord pending : ToolCallContextHolder.drainPendingTurns()) {
                appendTurn(new TurnRecord(++turnNumber, pending.type(), pending.payload(), null));
            }
            // A tool may request a delegation transform (create_group) or its reverse
            // (disband_group).
            // Apply it after the pending turn directives: a forward transform's REWIND discards
            // this
            // call's response + prior turns, then re-seeds the Leader; a reverse transform restores
            // the STANDALONE persona. Drained before clear() in the finally so a throwing tool
            // leaks
            // no transform to the next call on this thread.
            ToolCallContextHolder.TransformRequest transformRequest =
                    ToolCallContextHolder.drainTransform();
            if (transformRequest instanceof ToolCallContextHolder.TransformRequest.ToLeader t) {
                transformToLeader(t.directive());
            } else if (transformRequest
                    instanceof ToolCallContextHolder.TransformRequest.ToStandalone t) {
                transformToStandalone(t.brief());
            }
            return transformed;
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    private @NonNull ToolResult executeOneCall(@NonNull ToolCall call) {
        ToolDefinition def = mcpEngine.resolveDefinition(call.toolName());
        if (def == null) {
            String obs = "Tool not found: " + call.toolName();
            appendTurn(TurnRecord.toolCall(++turnNumber, call));
            appendObservation(call.toolName(), obs);
            return new ToolResult(call.toolName(), call.callId(), false, obs);
        }

        // (a) early-route agent tools past the Gateway + HITL.
        ApprovalDecision decision = ApprovalDecision.AUTO_APPROVE;
        if (!(def instanceof AgentToolDefinition)) {
            var result = gateway.screen(call, def);
            decision = hitlRegistry.decide(agentId, call, def, result);
            if (decision instanceof ApprovalDecision.AutoBlock ab) {
                appendTurn(TurnRecord.toolCall(++turnNumber, call));
                appendObservation(call.toolName(), "Blocked: " + ab.reason());
                return new ToolResult(
                        call.toolName(), call.callId(), false, "blocked: " + ab.reason());
            }
            if (decision instanceof ApprovalDecision.Refused r) {
                emitMessage(r.reason());
                transitionTo(AgentState.INTERCEPTED);
                hitlRegistry.register(agentId, call.callId());
                InterceptResolution res = hitlRegistry.await(agentId, call.callId());

                appendTurn(TurnRecord.toolCall(++turnNumber, call));
                appendTurn(TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
                this.state = AgentState.IDLE;
                return new ToolResult(call.toolName(), call.callId(), false, "REFUSED");
            }
            if (decision instanceof ApprovalDecision.Prompt p) {
                call = awaitVeto(call, def, p);
                if (call == null) {
                    this.state = AgentState.IDLE;
                    return new ToolResult("", "", false, "declined");
                }
            }
        }

        appendTurn(TurnRecord.toolCall(++turnNumber, call));

        // (c) plugin preAction chain (transform/observe/block — plugins only).
        for (LoopInterceptor plugin : interceptors) {
            if (!plugin.preAction(agentId, call)) {
                appendObservation(call.toolName(), "Blocked by plugin.");
                return new ToolResult(call.toolName(), call.callId(), false, "blocked by plugin");
            }
        }

        // (d) execute with tool call context (agentId + userId + groupId) threaded through.
        ToolCallContextHolder.set(agentId, userId, groupId, owner);
        try {
            ToolResult result = mcpEngine.execute(call, def);

            // (e) plugin postAction chain.
            ToolResult transformed = result;
            for (LoopInterceptor plugin : interceptors) {
                transformed = plugin.postAction(agentId, call, transformed);
            }

            // (f) ingress defense (mask + frame + invalidate read-history on write).
            String observation =
                    ingressDefense.maskAndFrame(call, def, transformed, decision, readHistory);

            // (g) plugin preObservation chain.
            for (LoopInterceptor plugin : interceptors) {
                observation = plugin.preObservation(agentId, observation);
            }

            appendTurn(
                    TurnRecord.toolResponse(
                            ++turnNumber, call.callId(), observation, transformed.success()));
            // Drain any turn directives the tool requested during execution (e.g. a RECALL seeded
            // by create_group to re-inject the authored brief). Each is appended with a
            // runner-assigned turn number; the pending record's placeholder turnNumber is rewritten
            // (type + payload preserved). Drained here, before clear() in the finally, so a tool
            // that threw never leaks a directive to the next call on this thread.
            for (TurnRecord pending : ToolCallContextHolder.drainPendingTurns()) {
                appendTurn(new TurnRecord(++turnNumber, pending.type(), pending.payload(), null));
            }
            // A tool may request a delegation transform (create_group) or its reverse
            // (disband_group).
            // Apply it after the pending turn directives: a forward transform's REWIND discards
            // this
            // call's response + prior turns, then re-seeds the Leader; a reverse transform restores
            // the STANDALONE persona. Drained before clear() in the finally so a throwing tool
            // leaks
            // no transform to the next call on this thread.
            ToolCallContextHolder.TransformRequest transformRequest =
                    ToolCallContextHolder.drainTransform();
            if (transformRequest instanceof ToolCallContextHolder.TransformRequest.ToLeader t) {
                transformToLeader(t.directive());
            } else if (transformRequest
                    instanceof ToolCallContextHolder.TransformRequest.ToStandalone t) {
                transformToStandalone(t.brief());
            }
            return transformed;
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    /** Parks the virtual thread on a HITL future; applies the resolution (EDIT re-screens). */
    private @Nullable ToolCall awaitVeto(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            ApprovalDecision.@NonNull Prompt p) {
        transitionTo(AgentState.INTERCEPTED);
        // Register the await target BEFORE advertising the prompt (see executeToolCalls for the
        // race rationale). EDIT is filtered from the offered set in v1.
        List<VetoOption> offered = VetoOption.withoutEdit(p.options());
        hitlRegistry.register(agentId, call.callId(), call, def, offered);
        emitVetoRequired(call, p, offered);
        top.focess.veto.agent.intercept.InterceptResolution resolution =
                hitlRegistry.await(agentId, call.callId());
        transitionTo(AgentState.WAITING);
        if (resolution.isRefusal()) {
            appendTurn(TurnRecord.toolCall(++turnNumber, call));
            appendTurn(TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
            return null;
        }
        if (resolution.option() == VetoOption.EDIT && resolution.editedArgs() != null) {
            ToolCall edited = new ToolCall(call.toolName(), resolution.editedArgs(), call.callId());
            // re-screen the edited call.
            var r2 = gateway.screen(edited, def);
            if (r2 instanceof GatewayResult.Screened sc
                    && sc.screening().danger() == Danger.CRITICAL) {
                appendTurn(TurnRecord.toolCall(++turnNumber, call));
                appendTurn(TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
                return null;
            }
            if (r2 instanceof GatewayResult.DriftResult) {
                appendTurn(TurnRecord.toolCall(++turnNumber, call));
                appendTurn(TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
                return null;
            }
            return edited;
        }
        return call;
    }

    private void emitVetoRequired(
            @NonNull ToolCall call,
            ApprovalDecision.@NonNull Prompt p,
            @NonNull List<VetoOption> offered) {
        log.info(
                "VETO_REQUIRED agent={} callId={} tool={} scenario={} options={}",
                agentId,
                call.callId(),
                call.toolName(),
                p.scenario(),
                offered);
        // Notify the veto emission seam: a transport renders a picker (a Prompt with a
        // VetoPayload) and routes the user's reply back to resolve the parked veto. The agent
        // parks in HitlRegistry regardless; a throwing listener is logged, not propagated.
        if (!vetoListeners.isEmpty()) {
            VetoPrompt vp =
                    new VetoPrompt(
                            agentId,
                            call.callId(),
                            call.toolName(),
                            p.scenario(),
                            offered,
                            call.args());
            for (Consumer<VetoPrompt> listener : vetoListeners) {
                try {
                    listener.accept(vp);
                } catch (RuntimeException e) {
                    log.warn("Agent {} veto listener threw", agentId, e);
                }
            }
        }
    }

    // ── Turn history + messaging ────────────────────────────────────────────

    private void appendThought(@NonNull VetoResponse response) {
        if (response.thought() != null && !response.thought().isBlank()) {
            try {
                String raw = objectMapper.writeValueAsString(response);
                appendTurn(TurnRecord.assistantThought(++turnNumber, raw));
            } catch (Exception e) {
                appendTurn(TurnRecord.assistantThought(++turnNumber, response.thought()));
            }
            // Stream the thought to transports now (after it is durably recorded). The terminal
            // renders it dimmed/muted ahead of the user-facing message that follows, so the user
            // can follow the reasoning without it competing with the answer.
            emitThought(response.thought());
        }
    }

    private void emitMessage(@NonNull String message) {
        appendTurn(TurnRecord.assistantResponse(++turnNumber, message));
        lastMessage = message;
        // emission seam: forward each user-facing message to subscribed transports so they
        // stream it while the loop runs (the terminal PromptHandler forwards as a Delta). Part
        // 8's JVM EventBus + ZmqServer broker will sit between this seam and the wire.
        if (!messageListeners.isEmpty()) {
            for (Consumer<String> listener : messageListeners) {
                try {
                    listener.accept(message);
                } catch (RuntimeException e) {
                    log.warn("Agent {} message listener threw", agentId, e);
                }
            }
        }
        // Part-8 emission seam: publish a DeltaFrame to the broker so transports (the WebSocket
        // bus via DeltaBusBridge) can stream each user-facing message. The broker assigns the
        // per-session sequence; the frame text is the message verbatim.
        if (deltaBroker != null) {
            try {
                deltaBroker.publish(
                        DeltaFrame.builder()
                                .sessionId(sessionId)
                                .kind(DeltaFrame.Kind.ASSISTANT_MESSAGE)
                                .text(message)
                                .build());
            } catch (RuntimeException e) {
                log.warn("Agent {} delta-broker publish failed", agentId, e);
            }
        }
    }

    /**
     * Emission seam for interim thoughts, parallel to {@link #emitMessage}: forwards the thought
     * text to each subscribed transport (the terminal renders it distinct from a message) and
     * publishes an {@link DeltaFrame.Kind#ASSISTANT_THOUGHT} to the broker so the web UI gets the
     * same stream. Unlike {@code emitMessage} this does NOT advance the turn history - the thought
     * is already recorded by {@link #appendThought} before calling here.
     */
    private void emitThought(@NonNull String thought) {
        if (thought == null || thought.isBlank()) return;
        if (!thoughtListeners.isEmpty()) {
            for (Consumer<String> listener : thoughtListeners) {
                try {
                    listener.accept(thought);
                } catch (RuntimeException e) {
                    log.warn("Agent {} thought listener threw", agentId, e);
                }
            }
        }
        if (deltaBroker != null) {
            try {
                deltaBroker.publish(
                        DeltaFrame.builder()
                                .sessionId(sessionId)
                                .kind(DeltaFrame.Kind.ASSISTANT_THOUGHT)
                                .text(thought)
                                .build());
            } catch (RuntimeException e) {
                log.warn("Agent {} delta-broker publish failed (thought)", agentId, e);
            }
        }
    }

    private void appendObservation(@NonNull String toolName, @NonNull String content) {
        appendTurn(TurnRecord.toolResponse(++turnNumber, null, content, false));
    }

    private void appendTurn(TurnRecord turn) {
        TurnRecord numbered;
        synchronized (this) {
            // turn_number is the durable unique key (uk_turn_records_agent_turn on
            // session_id, agent_id, turn_number). The in-memory history's high-water mark is the
            // allocation authority, not the turnNumber counter: a caller's ++turnNumber
            // side-effect leaves the counter equal to the passed number whether the caller
            // incremented or forgot, so the counter cannot distinguish a correct advance from a
            // reuse. If the passed number does not advance past the last recorded turn, allocate
            // the next one so a duplicate is never persisted (the DB would reject it and leave the
            // durable log inconsistent with the in-memory history). history only grows, so its
            // last element carries the max turn_number.
            int highWater = history.isEmpty() ? 0 : history.get(history.size() - 1).turnNumber();
            numbered = turn.turnNumber() <= highWater ? turn.withTurnNumber(highWater + 1) : turn;
            turnNumber = numbered.turnNumber();
            history.add(numbered);
        }
        // Persist the turn to the raw-turn audit/replay log (session resume, Leader
        // reconstruction). Best-effort — done outside the history lock so a DB write doesn't
        // block history readers, and the service swallows failures so the loop is never affected.
        if (turnLogService != null) {
            try {
                turnLogService.log(numbered, sessionId, userId, agentId);
            } catch (RuntimeException e) {
                log.warn("Agent {} turn log failed", agentId, e);
            }
        }
        // Transparency emission seam: forward tool calls + observations to subscribed transports
        // so the terminal can render a Claude-Code-style indicator. Emitted AFTER the DB persist
        // so listeners never see a turn the durable log lost. Only the two types whose wire
        // representation carries call/result fields are routed; other types (USER_PROMPT,
        // ASSISTANT_THOUGHT, ASSISTANT_RESPONSE) are already handled by the message/thought seams.
        switch (numbered.type()) {
            case TOOL_CALL -> {
                Object name = numbered.payload().get("tool_name");
                Object args = numbered.payload().get("args");
                if (name instanceof String toolName) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> argMap =
                            args instanceof java.util.Map
                                    ? (java.util.Map<String, Object>) args
                                    : java.util.Map.of();
                    emitToolCall(new IpcFrame.ToolCall(toolName, argMap));
                }
            }
            case TOOL_RESPONSE -> {
                Object content = numbered.payload().get("content");
                Object success = numbered.payload().get("success");
                if (content instanceof String body) {
                    emitToolResult(new IpcFrame.ToolResult(body, Boolean.TRUE.equals(success)));
                }
            }
            default -> {
                // no-op: message/thought seams already cover the other types
            }
        }
    }

    /**
     * Emission seam for tool calls. Notifies each subscribed transport (the terminal renders a
     * compact indicator) by handing it a structured {@link IpcFrame.ToolCall}. Best-effort: a
     * listener that throws is logged and skipped so one bad subscriber can't break the loop.
     */
    private void emitToolCall(IpcFrame.@NonNull ToolCall call) {
        if (toolCallListeners.isEmpty()) {
            return;
        }
        for (Consumer<IpcFrame.ToolCall> listener : toolCallListeners) {
            try {
                listener.accept(call);
            } catch (RuntimeException e) {
                log.warn("Agent {} tool-call listener threw", agentId, e);
            }
        }
    }

    /**
     * Emission seam for tool results. Notifies each subscribed transport with the framed
     * observation text (the exact string the model sees). The {@code body} is self-describing
     * thanks to {@code IngressDefense.maskAndFrame} which prefixes the call's args, so the terminal
     * can render a single result and the user can verify the call it belongs to without tracking
     * pairs.
     */
    private void emitToolResult(IpcFrame.@NonNull ToolResult result) {
        if (toolResultListeners.isEmpty()) {
            return;
        }
        for (Consumer<IpcFrame.ToolResult> listener : toolResultListeners) {
            try {
                listener.accept(result);
            } catch (RuntimeException e) {
                log.warn("Agent {} tool-result listener threw", agentId, e);
            }
        }
    }

    /**
     * Seeds the runner with replayed history (loaded from the durable turn log on session activate)
     * so a re-activated session resumes its conversation. Must run before the loop processes its
     * first action: {@link VetoAgent}'s ctor starts the virtual thread, but it parks on {@code
     * actionQueue.take()} while IDLE and only touches {@code history} when compiling a submitted
     * prompt - so seeding right after creation, before the first {@code submit}, is safe.
     *
     * <p>Idempotent: a no-op if {@code history} is already non-empty (or the replay is empty/null),
     * so a second get-or-create on an already-live agent does not duplicate turns.
     */
    public synchronized void seedHistory(java.util.@NonNull List<TurnRecord> replayed) {
        if (!history.isEmpty() || replayed == null || replayed.isEmpty()) {
            return;
        }
        history.addAll(replayed);
        // Advance the turn counter past the replayed turns so the next live turn does not reuse a
        // replayed turn number. Turn number is the durable key in turn_records, so a collision
        // would violate the unique constraint or shadow the replayed turn.
        int max = 0;
        for (TurnRecord t : replayed) {
            if (t.turnNumber() > max) {
                max = t.turnNumber();
            }
        }
        turnNumber = max;
    }

    // ── completion ──────────────────────────────────────────────────────────

    private volatile @NonNull String lastMessage = "";

    private void completeSuccess() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", turnNumber);
        complete(AgentResult.success(lastMessage, meta));
    }

    private void completeBreaker() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("breakerTrip", true);
        meta.put("turns", turnNumber);
        complete(AgentResult.failure(lastMessage, meta));
    }

    private void completeFailure(@Nullable String message) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("turns", turnNumber);
        complete(AgentResult.failure(message, meta));
    }

    private void complete(AgentResult result) {
        // Complete the in-place handoff future installed by startTask. Completing the field
        // (rather than reassigning it to a fresh completed future) means an await that already
        // snapshotted resultFuture blocks on the right future and wakes here — a reassignment
        // would leave await holding a stale (already-completed-null) snapshot that returned null.
        resultFuture.complete(result);
        Consumer<AgentResult> cb = callback;
        if (cb != null) {
            cb.accept(result);
        }
    }

    // ── state + API ops (called by VetoAgent / transport) ────────────────────

    private void transitionTo(AgentState next) {
        this.state = next;
    }

    /**
     * Starts a reasoning task: installs a fresh incomplete result future (the authoritative handoff
     * {@link #await} blocks on) and enqueues the action. Installing the future on the caller thread
     * before enqueueing removes the submit→await race — await always snapshots the future this task
     * will complete, not the previous episode's already-completed one.
     */
    public void startTask(@Nullable Consumer<AgentResult> callback, @NonNull AgentAction action) {
        this.callback = callback;
        this.resultFuture = new CompletableFuture<>();
        if (this.state == AgentState.INTERCEPTED) {
            hitlRegistry.declineAll(agentId);
        }
        actionQueue.add(action);
    }

    public void enqueue(@NonNull AgentAction action) {
        actionQueue.add(action);
    }

    public void bind(@NonNull LlmBinding binding) {
        this.binding = binding;
    }

    /** The current model binding (the transform stashes this before rebinding to the Leader). */
    public @NonNull LlmBinding binding() {
        return binding;
    }

    /**
     * Subscribes a user-facing-message listener (the emission seam; forwarded in {@link
     * #emitMessage}).
     */
    public void addMessageListener(@NonNull Consumer<String> listener) {
        if (listener != null) {
            messageListeners.add(listener);
        }
    }

    /** Unsubscribes a user-facing-message listener. */
    public void removeMessageListener(@NonNull Consumer<String> listener) {
        if (listener != null) {
            messageListeners.remove(listener);
        }
    }

    /**
     * Subscribes an interim-thought listener (the thought emission seam; forwarded in {@link
     * #emitThought}). Fires before the matching message listener because {@link #appendThought}
     * runs before {@link #emitMessage} in the loop.
     */
    public void addThoughtListener(@NonNull Consumer<String> listener) {
        if (listener != null) {
            thoughtListeners.add(listener);
        }
    }

    /** Unsubscribes an interim-thought listener. */
    public void removeThoughtListener(@NonNull Consumer<String> listener) {
        if (listener != null) {
            thoughtListeners.remove(listener);
        }
    }

    /**
     * Subscribes a HITL-veto listener (the veto emission seam; forwarded in {@link
     * #emitVetoRequired}). Fires on the agent's virtual thread when a tool call parks for approval.
     */
    public void addVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        if (listener != null) {
            vetoListeners.add(listener);
        }
    }

    /** Unsubscribes a HITL-veto listener. */
    public void removeVetoListener(@NonNull Consumer<VetoPrompt> listener) {
        if (listener != null) {
            vetoListeners.remove(listener);
        }
    }

    /**
     * Subscribes a tool-call listener (the transparency emission seam; forwarded in {@link
     * #emitToolCall}). Fires on the agent's virtual thread when a TOOL_CALL turn is appended — i.e.
     * immediately before the model receives the tool result for that call.
     */
    public void addToolCallListener(@NonNull Consumer<IpcFrame.ToolCall> listener) {
        if (listener != null) {
            toolCallListeners.add(listener);
        }
    }

    /** Unsubscribes a tool-call listener. */
    public void removeToolCallListener(@NonNull Consumer<IpcFrame.ToolCall> listener) {
        if (listener != null) {
            toolCallListeners.remove(listener);
        }
    }

    /**
     * Subscribes a tool-result listener (the transparency emission seam; forwarded in {@link
     * #emitToolResult}). Fires on the agent's virtual thread when a TOOL_RESPONSE turn is appended
     * — i.e. immediately after the model receives the observation.
     */
    public void addToolResultListener(@NonNull Consumer<IpcFrame.ToolResult> listener) {
        if (listener != null) {
            toolResultListeners.add(listener);
        }
    }

    /** Unsubscribes a tool-result listener. */
    public void removeToolResultListener(@NonNull Consumer<IpcFrame.ToolResult> listener) {
        if (listener != null) {
            toolResultListeners.remove(listener);
        }
    }

    public @NonNull AgentResult await(@NonNull Duration timeout)
            throws TimeoutException, InterruptedException {
        CompletableFuture<AgentResult> f = resultFuture;
        try {
            return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            // The runner completes the future normally via complete (never exceptionally); an
            // exceptional completion here is unexpected — surface its cause.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Agent task completed exceptionally", cause);
        }
    }

    public @NonNull CompletableFuture<AgentResult> result() {
        return resultFuture;
    }

    public @NonNull AgentState state() {
        return state;
    }

    public synchronized @NonNull List<TurnRecord> history() {
        return List.copyOf(history);
    }

    public @NonNull ReadHistory readHistory() {
        return readHistory;
    }

    /** The whitelisted tool-name view (immutable). */
    public @NonNull Set<String> whitelistedToolsView() {
        return whitelistedTools;
    }

    public @NonNull String agentId() {
        return agentId;
    }

    /** The group this agent belongs to, or null for a single-agent (STANDALONE) loop. */
    public @Nullable UUID groupId() {
        return groupId;
    }

    /** Stamps the group this agent belongs to (called by group-spawning code / the transform). */
    public void setGroupId(@Nullable UUID groupId) {
        this.groupId = groupId;
    }

    /**
     * Stamps the session owner (username) whose model-tier profile resolves this agent's tier.
     * Called by the DB-backed create path ({@link AgentService#createMate} / {@code createAgent})
     * before the loop starts, so group-spawned Mates / Leaders resolve their tier against the
     * user's active profile via the {@link ToolCallContext}.
     */
    public void setOwner(@Nullable String owner) {
        this.owner = owner;
    }

    // Overwrites the default (agent-id-derived) session id with the real session id. Called by the
    // DB-backed create path (AgentService.createAgent) before the loop starts, so persisted turns
    // land under turn_records.session_id = session.getId() and group with their sibling agent
    // streams.
    public void setSessionId(@NonNull UUID sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Replaces the persona (and its tool-name view) in place. Used by the delegation transform /
     * disband to flip the operational role + tool set without becoming a different agent (the id,
     * session, and user stay; only the role-scoped identity changes). The next {@link #callModel}
     * compiles against the new persona.
     */
    public void applyPersona(@NonNull AgentPersona persona) {
        this.persona = persona;
        this.whitelistedTools =
                persona.whitelistedTools().stream()
                        .map(ToolDefinition::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * The delegation transform: the calling STANDALONE agent becomes the Leader of a new group. Run
     * on the loop thread inside the tool-call drain pass (after the {@code create_group} tool
     * response is appended), so the transform's REWIND discards this call's response along with the
     * prior standalone turns.
     *
     * <p>Append sequence (each its own turn, monotonic counter): REWIND to 0 (drop the compiled
     * view), AGENT_INIT (Leader role-segment marker - maps to no message), COMPACTION_SUMMARY (the
     * essence of the prior standalone session, carried forward), USER_PROMPT (the brief). Then
     * mutate the persona to LEADER + the Leader tool set, bind the Leader (top-tier) model, stamp
     * the group, and reset the episode so the Leader reasons fresh from the brief.
     */
    private void transformToLeader(ToolCallContextHolder.@NonNull TransformDirective directive) {
        // Compaction summary of the prior standalone turns (defensive: a compactor failure yields
        // an
        // empty summary rather than aborting the transform).
        List<TurnRecord> priorTurns;
        synchronized (history) {
            priorTurns = new ArrayList<>(history);
        }
        String summary;
        try {
            summary = computeCompactionSummary(priorTurns);
        } catch (RuntimeException e) {
            log.warn(
                    "Agent {} transform compaction failed; continuing with empty summary",
                    agentId,
                    e);
            summary = "{}";
        }

        appendTurn(TurnRecord.rewind(++turnNumber, 0));
        appendTurn(TurnRecord.agentInit(++turnNumber, "leader"));
        if (summary != null && !summary.isBlank() && !"{}".equals(summary)) {
            appendTurn(TurnRecord.compactionSummary(++turnNumber, summary));
        }
        appendTurn(TurnRecord.userPrompt(++turnNumber, directive.brief()));

        // Stash the pre-transform STANDALONE persona + binding so disband_group can restore them,
        // then adopt the Leader persona + tool set + top-tier binding + group stamp.
        this.preTransformPersona = this.persona;
        this.preTransformBinding = this.binding;
        applyPersona(persona.withRoleAndTools(Role.LEADER, directive.leaderTools()));
        bind(directive.leaderBinding());
        setGroupId(directive.groupId());

        // Fresh reasoning episode from the brief: clear guided state + program, reset the breaker
        // and scope so prior standalone state does not leak into the Leader's planning.
        this.guided = false;
        this.activeProgram = null;
        this.programCounter = 0;
        this.breaker.newEpisode();
        this.scope = new Scope(objectMapper);
        log.info(
                "Agent {} transformed into Leader of group {} (Leader model={})",
                agentId,
                directive.groupId(),
                directive.leaderBinding().model());
    }

    /**
     * The reverse delegation transform: the Leader becomes STANDALONE again (the group was
     * disbanded). Run on the loop thread inside the tool-call drain pass (after the {@code
     * disband_group} tool response is appended). Append sequence: REWIND to 0, AGENT_INIT
     * (STANDALONE role-segment marker), COMPACTION_SUMMARY (the essence of the Leader session),
     * USER_PROMPT (the outcome brief). Then restore the stashed STANDALONE persona + binding, clear
     * the group stamp, and reset the episode.
     */
    private void transformToStandalone(@NonNull String brief) {
        List<TurnRecord> priorTurns;
        synchronized (history) {
            priorTurns = new ArrayList<>(history);
        }
        String summary;
        try {
            summary = computeCompactionSummary(priorTurns);
        } catch (RuntimeException e) {
            log.warn(
                    "Agent {} reverse-transform compaction failed; continuing with empty summary",
                    agentId,
                    e);
            summary = "{}";
        }

        appendTurn(TurnRecord.rewind(++turnNumber, 0));
        appendTurn(TurnRecord.agentInit(++turnNumber, "standalone"));
        if (summary != null && !summary.isBlank() && !"{}".equals(summary)) {
            appendTurn(TurnRecord.compactionSummary(++turnNumber, summary));
        }
        appendTurn(TurnRecord.userPrompt(++turnNumber, brief));

        // Restore the stashed STANDALONE persona + binding. Null-safe: if no transform was stashed
        // (the agent never led a group), flip the role back to STANDALONE on the current persona.
        AgentPersona restored =
                preTransformPersona != null
                        ? preTransformPersona
                        : persona.withRole(Role.STANDALONE);
        LlmBinding restoredBinding =
                preTransformBinding != null ? preTransformBinding : this.binding;
        applyPersona(restored);
        bind(restoredBinding);
        setGroupId(null);
        this.preTransformPersona = null;
        this.preTransformBinding = null;

        this.guided = false;
        this.activeProgram = null;
        this.programCounter = 0;
        this.breaker.newEpisode();
        this.scope = new Scope(objectMapper);
        log.info("Agent {} reversed transform back to STANDALONE (group disbanded)", agentId);
    }

    public void terminate() {
        sessionAlive = false;
        transitionTo(AgentState.TERMINATED);
        hitlRegistry.clear(agentId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<ToolCall> assignCallIds(List<ToolCall> calls) {
        List<ToolCall> withIds = new ArrayList<>();
        for (ToolCall c : calls) {
            if (c.callId() == null) {
                withIds.add(new ToolCall(c.toolName(), c.args(), nextCallId()));
            } else {
                withIds.add(c);
            }
        }
        return withIds;
    }

    private String nextCallId() {
        return "call_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * A model binding: provider/model/credential/options + the Layer-1 system-prompt base. The
     * {@code baseUrl} overrides the provider's default base URL when non-null (per-user, per-tier);
     * null falls back to the provider strategy's default.
     */
    public record LlmBinding(
            @NonNull ProviderType provider,
            @NonNull String model,
            @NonNull String credentialKey,
            @NonNull LlmOptions options,
            @Nullable String systemPromptBase,
            @Nullable String baseUrl) {

        /**
         * Convenience constructor for callers that do not override the base URL (null -> default).
         */
        public LlmBinding(
                @NonNull ProviderType provider,
                @NonNull String model,
                @NonNull String credentialKey,
                @NonNull LlmOptions options,
                @Nullable String systemPromptBase) {
            this(provider, model, credentialKey, options, systemPromptBase, null);
        }
    }

    /** Signals a breaker trip (caught at the action boundary → IDLE + notice). */
    private static final class BreakerTripException extends RuntimeException {}

    // ── Loop discipline helpers (duplicate-call filter) ─────────────────────

    /**
     * Filters a batch of model-issued tool calls against the recent-call ring. Any read-only call
     * whose (toolName, args) matches a recent entry is short-circuited: a synthetic observation
     * quoting the prior framed result is appended as a TOOL_RESPONSE turn, and the call is removed
     * from the batch. Non-read calls (writes, agent tools) pass through unchanged.
     *
     * <p>Loop-thread-only. Returns a new list; the input is not mutated.
     */
    private @NonNull List<ToolCall> applyDuplicateCallFilter(@NonNull List<ToolCall> calls) {
        List<ToolCall> kept = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            RecentCall prior = findRecentCall(call);
            if (prior != null) {
                String body =
                        "[loop-enforcer] You already executed this exact call earlier in this"
                                + " session. The prior framed result was:\n"
                                + prior.body()
                                + "\n\nRead that prior result; do NOT re-execute. Either make a"
                                + " NEW tool call with different arguments (descend into a"
                                + " subdirectory, search for different content, etc.) or compose"
                                + " your answer in `message`.";
                appendObservation(call.toolName(), body);
                // The dedup count is logged at the call site (where we know both totals).
            } else {
                kept.add(call);
            }
        }
        return kept;
    }

    /**
     * Records a successful tool call into the recent-call ring. Only read-only native tools are
     * recorded: writes have side effects (a subsequent read is a fresh call, not a duplicate), and
     * agent tools are state-dependent (a re-call with the same args may not be a mistake).
     */
    private void recordRecentCall(
            @NonNull ToolCall call, @NonNull String observation, @NonNull ToolDefinition def) {
        if (def.risk() != RiskCategory.READ_ONLY) {
            return;
        }
        if (recentToolCalls.size() >= RECENT_CALL_WINDOW) {
            recentToolCalls.pollFirst();
        }
        Map<String, Object> args = call.args() == null ? Map.of() : call.args();
        recentToolCalls.offerLast(new RecentCall(call.toolName(), args, observation));
    }

    /**
     * Returns the most recent matching entry from the recent-call ring, or {@code null} if this
     * call's (toolName, args) has not been seen before in this session. Match is exact: same tool
     * name and a structurally-equal args map (order-independent key/value comparison).
     */
    private @Nullable RecentCall findRecentCall(@NonNull ToolCall call) {
        String name = call.toolName();
        Map<String, Object> args = call.args() == null ? Map.of() : call.args();
        for (RecentCall rc : recentToolCalls) {
            if (rc.toolName().equals(name) && argsEqual(rc.args(), args)) {
                return rc;
            }
        }
        return null;
    }

    /**
     * Structural equality on args maps. Order-independent; values compared with {@link
     * Object#equals}. Used for duplicate-call detection — a model that re-issues the same call
     * typically re-uses the same args verbatim, so exact equality is the right granularity (no
     * fuzzy matching, no canonicalization of equivalent paths).
     */
    private static boolean argsEqual(
            @NonNull Map<String, Object> a, @NonNull Map<String, Object> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (!b.containsKey(e.getKey())) {
                return false;
            }
            Object av = e.getValue();
            Object bv = b.get(e.getKey());
            if (av == null ? bv != null : !av.equals(bv)) {
                return false;
            }
        }
        return true;
    }
}
