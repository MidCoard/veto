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
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.mcp.ToolEngine;
import top.focess.veto.agent.mcp.ToolResult;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.bus.DeltaBroker;
import top.focess.veto.bus.DeltaFrame;
import top.focess.veto.llm.core.ChatMessage;
import top.focess.veto.llm.core.LlmOptions;
import top.focess.veto.llm.core.ProviderType;
import top.focess.veto.llm.core.ToolCall;
import top.focess.veto.llm.core.UniformLLMCaller;
import top.focess.veto.llm.core.VetoRequest;
import top.focess.veto.llm.core.VetoResponse;
import top.focess.veto.llm.exceptions.LlmException;
import top.focess.veto.llm.exceptions.ModelSchemaException;

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
    // The group this agent belongs to (the group it leads, or the group it is a Mate of); null for
    // a single-agent (STANDALONE) loop. Stamped by group-spawning code and threaded into each
    // tool's ToolCallContext so group-scoped tools (create_node, post_message, ...) resolve the
    // caller's group without a groupId argument.
    private volatile @Nullable UUID groupId;

    // --- model binding (provider/model/credential), set per prompt ---
    private volatile @NonNull LlmBinding binding;

    // The pre-transform STANDALONE persona + binding, stashed when the agent transforms into a
    // Leader so disband_group can reverse the transform and restore them. Null when not leading.
    private volatile @Nullable AgentPersona preTransformPersona;
    private volatile @Nullable LlmBinding preTransformBinding;

    // --- loop state (mutated only by the runner's virtual thread) ---
    private final BlockingQueue<AgentAction> actionQueue = new LinkedBlockingQueue<>();
    private volatile AgentState state = AgentState.IDLE;
    private final List<TurnRecord> history = new ArrayList<>();
    private int turnNumber = 0;
    private boolean guided = false;
    private ActionsProgram activeProgram = null;
    private int programCounter = 0;
    private int currentSteps = 0;
    private @NonNull Scope scope;
    private CompletableFuture<AgentResult> resultFuture = CompletableFuture.completedFuture(null);
    private @Nullable Consumer<AgentResult> callback;
    private volatile boolean sessionAlive = true;
    private double correctionFactor = 1.0;
    private long lastEstimatedTokens = 0;

    // User-facing message listeners (the emission seam). emitMessage notifies these so a
    // transport (the terminal PromptHandler) can forward each assistantResponse to its client as a
    // Delta while the loop runs. A JVM EventBus + ZmqServer Delta-frame broker will sit between
    // this
    // seam and the wire; until then the listener is the direct handoff.
    private final CopyOnWriteArrayList<Consumer<String>> messageListeners =
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
    }

    // ── Episode setup + autonomous loop ─────────────────────────────────────

    private void processUserPrompt(String prompt) {
        // Fresh UserPromptAction: reset guided state and program counter.
        appendTurn(TurnRecord.userPrompt(++turnNumber, prompt));
        this.guided = false;
        this.activeProgram = null;
        this.programCounter = 0;
        this.breaker.newEpisode();
        this.scope = new Scope(objectMapper);

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
    private String computeCompactionSummary(List<TurnRecord> workTurns) {
        if (workTurns == null || workTurns.isEmpty()) {
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

    private String callCompactor(String systemPrompt, String userPrompt) {
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
                        null);
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

    private VetoResponse callGenerate(GenerateAction gen) {
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

    private boolean loadProgram(JsonNode node) {
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

    private void escapeToAutonomous(String reason) {
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

    private VetoResponse callModel(boolean guidedSwitch) {
        CompiledPrompt compiled =
                promptCompiler.compile(
                        persona,
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

    private VetoRequest buildRequest(CompiledPrompt compiled) {
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
                compiled.responseSchema());
    }

    /**
     * Injects an ephemeral rejection message for a schema-violation retry. The rejection is added
     * only to the {@link VetoRequest} for the next attempt — it is never appended to {@link
     * #history}, so a crash after a successful retry loses it (acceptable). All binding fields
     * (provider/model/credential/options/schema) are preserved verbatim.
     */
    private VetoRequest injectSchemaRejection(VetoRequest request, ModelSchemaException e) {
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
                request.responseSchema());
    }

    /**
     * Maps a {@link ModelSchemaException} message to the human-readable behavior the model should
     * adopt on retry. Substring-matched against the canonical messages {@link ResponseEnforcer}
     * emits.
     */
    private String getExpectedDescription(ModelSchemaException e) {
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

    private void executeToolCalls(List<ToolCall> calls) {
        transitionTo(AgentState.WAITING);
        try {
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

                    if (decision instanceof ApprovalDecision.Refused r) {
                        emitMessage(r.reason());
                        transitionTo(AgentState.INTERCEPTED);
                        hitlRegistry.register(agentId, call.callId());
                        InterceptResolution res = hitlRegistry.await(agentId, call.callId());
                        batchApproved = false;
                        break;
                    } else if (decision instanceof ApprovalDecision.Prompt p) {
                        transitionTo(AgentState.INTERCEPTED);
                        emitVetoRequired(call, p);
                        hitlRegistry.register(agentId, call.callId());
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
                            ToolDefinition def = mcpEngine.resolveDefinition(edited.toolName());
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
                        appendTurn(TurnRecord.toolCall(turnNumber, call));
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
                    appendTurn(TurnRecord.toolCall(turnNumber, call));
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

    private ToolResult executeOneConfirmedCall(ToolCall call) {
        ToolDefinition def = mcpEngine.resolveDefinition(call.toolName());
        if (def == null) {
            String obs = "Tool not found: " + call.toolName();
            appendTurn(TurnRecord.toolCall(turnNumber, call));
            appendObservation(call.toolName(), obs);
            return new ToolResult(call.toolName(), call.callId(), false, obs);
        }

        appendTurn(TurnRecord.toolCall(turnNumber, call));

        // (c) plugin preAction chain
        for (LoopInterceptor plugin : interceptors) {
            if (!plugin.preAction(agentId, call)) {
                appendObservation(call.toolName(), "Blocked by plugin.");
                return new ToolResult(call.toolName(), call.callId(), false, "blocked by plugin");
            }
        }

        // (d) execute with tool call context (agentId + userId + groupId) threaded through.
        ToolCallContextHolder.set(agentId, userId, groupId);
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

    private ToolResult executeOneCall(ToolCall call) {
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

                appendTurn(TurnRecord.toolCall(turnNumber, call));
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

        appendTurn(TurnRecord.toolCall(turnNumber, call));

        // (c) plugin preAction chain (transform/observe/block — plugins only).
        for (LoopInterceptor plugin : interceptors) {
            if (!plugin.preAction(agentId, call)) {
                appendObservation(call.toolName(), "Blocked by plugin.");
                return new ToolResult(call.toolName(), call.callId(), false, "blocked by plugin");
            }
        }

        // (d) execute with tool call context (agentId + userId + groupId) threaded through.
        ToolCallContextHolder.set(agentId, userId, groupId);
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
    private ToolCall awaitVeto(ToolCall call, ToolDefinition def, ApprovalDecision.Prompt p) {
        transitionTo(AgentState.INTERCEPTED);
        emitVetoRequired(call, p);
        CompletableFuture<top.focess.veto.agent.intercept.InterceptResolution> future =
                hitlRegistry.register(agentId, call.callId());
        top.focess.veto.agent.intercept.InterceptResolution resolution =
                hitlRegistry.await(agentId, call.callId());
        transitionTo(AgentState.WAITING);
        if (resolution.isRefusal()) {
            appendTurn(TurnRecord.toolCall(turnNumber, call));
            appendTurn(TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
            return null;
        }
        if (resolution.option() == VetoOption.EDIT && resolution.editedArgs() != null) {
            ToolCall edited = new ToolCall(call.toolName(), resolution.editedArgs(), call.callId());
            // re-screen the edited call.
            var r2 = gateway.screen(edited, def);
            if (r2 instanceof GatewayResult.Screened sc
                    && sc.screening().danger() == Danger.CRITICAL) {
                appendTurn(TurnRecord.toolCall(turnNumber, call));
                appendTurn(TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
                return null;
            }
            if (r2 instanceof GatewayResult.DriftResult) {
                appendTurn(TurnRecord.toolCall(turnNumber, call));
                appendTurn(TurnRecord.toolResponse(++turnNumber, call.callId(), "REFUSED", false));
                return null;
            }
            return edited;
        }
        return call;
    }

    private void emitVetoRequired(ToolCall call, ApprovalDecision.Prompt p) {
        log.info(
                "VETO_REQUIRED agent={} callId={} tool={} scenario={} options={}",
                agentId,
                call.callId(),
                call.toolName(),
                p.scenario(),
                p.options());
        // The transport subscribes to this; it is logged.
        // A full VETO_REQUIRED frame is emitted via the event bus in a later part.
    }

    // ── Turn history + messaging ────────────────────────────────────────────

    private void appendThought(VetoResponse response) {
        if (response.thought() != null && !response.thought().isBlank()) {
            try {
                String raw = objectMapper.writeValueAsString(response);
                appendTurn(TurnRecord.assistantThought(turnNumber, raw));
            } catch (Exception e) {
                appendTurn(TurnRecord.assistantThought(turnNumber, response.thought()));
            }
        }
    }

    private void emitMessage(String message) {
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

    private void appendObservation(String toolName, String content) {
        appendTurn(TurnRecord.toolResponse(++turnNumber, null, content, false));
    }

    private void appendTurn(TurnRecord turn) {
        synchronized (this) {
            history.add(turn);
        }
        // Persist the turn to the raw-turn audit/replay log (session resume, Leader
        // reconstruction). Best-effort — done outside the history lock so a DB write doesn't
        // block history readers, and the service swallows failures so the loop is never affected.
        if (turnLogService != null) {
            try {
                turnLogService.log(turn, sessionId, userId, agentId);
            } catch (RuntimeException e) {
                log.warn("Agent {} turn log failed", agentId, e);
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

    private volatile String lastMessage = "";

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

    private void completeFailure(String message) {
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

    /** A model binding: provider/model/credential/options + the Layer-1 system-prompt base. */
    public record LlmBinding(
            @NonNull ProviderType provider,
            @NonNull String model,
            @NonNull String credentialKey,
            @NonNull LlmOptions options,
            @Nullable String systemPromptBase) {}

    /** Signals a breaker trip (caught at the action boundary → IDLE + notice). */
    private static final class BreakerTripException extends RuntimeException {}
}
