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
import top.focess.veto.agent.mcp.McpEngine;
import top.focess.veto.agent.mcp.McpToolResult;
import top.focess.veto.agent.mcp.ToolCallContextHolder;
import top.focess.veto.agent.mcp.ToolDefinition;
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
    private final @NonNull AgentPersona persona;
    private final @NonNull Set<String> whitelistedTools;
    private final @NonNull McpEngine mcpEngine;
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
    private final @NonNull UUID sessionId;
    // The per-turn memory-capture service (Part 4 §3.1). Nullable — when null (tests / no capture
    // configured), appendTurn only updates the in-memory history; when present, each turn is also
    // captured into Session LTM + the raw-turn audit log.
    private final top.focess.veto.memory.@Nullable MemoryCaptureService captureService;
    private final @NonNull UUID userId;

    // --- model binding (provider/model/credential), set per prompt ---
    private volatile @NonNull LlmBinding binding;

    // --- loop state (mutated only by the runner's virtual thread) ---
    private final BlockingQueue<AgentAction> actionQueue = new LinkedBlockingQueue<>();
    private volatile AgentState state = AgentState.IDLE;
    private final List<TurnRecord> history = new ArrayList<>();
    private int turnNumber = 0;
    private boolean guided = false;
    private boolean thought = true;
    private boolean freshEpisode = true; // effective thought ON at a user-prompt turn
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
            @NonNull McpEngine mcpEngine,
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
            top.focess.veto.memory.@Nullable MemoryCaptureService captureService) {
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
        // Capture is nullable: non-Spring callers (tests) pass null so turns are not captured.
        this.captureService = captureService;
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
        // Fresh UserPromptAction: reset guided/features, force effective thought ON.
        appendTurn(TurnRecord.userPrompt(++turnNumber, prompt));
        this.guided = false;
        this.activeProgram = null;
        this.programCounter = 0;
        this.freshEpisode = true;
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

        String finalSummary = "";
        if (summaries.size() == 1) {
            finalSummary = summaries.get(0);
        } else {
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
            finalSummary = callCompactor(systemPrompt, combined.toString());
        }

        appendTurn(TurnRecord.rewind(++turnNumber, 1));
        appendTurn(TurnRecord.compactionSummary(++turnNumber, finalSummary));
        emitMessage("Compaction complete. Summarized " + workTurns.size() + " turns.");
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
            boolean effectiveThought = freshEpisode || this.thought;
            boolean guidedSwitch = false;
            VetoResponse response = callModel(effectiveThought, guidedSwitch);
            breaker.recordModelCall();
            freshEpisode = false;

            // Read NEXT-status features (the mode the NEXT iteration enters).
            if (response.features() != null) {
                this.guided = response.features().guided();
                this.thought = response.features().thought();
            }

            // Agent requested guided mode for the next iteration → load + validate program.
            if (this.guided && response.actionsProgram() != null) {
                if (loadProgram(response.actionsProgram())) {
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
                        "actionsProgram failed validation; staying autonomous.");
                continue;
            }

            appendThought(response);
            if (response.message() != null && !response.message().isBlank()) {
                emitMessage(response.message());
            }
            if (response.isFinished()) {
                return;
            }
            if (response.hasCalls()) {
                executeToolCalls(assignCallIds(response.calls()));
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
                    McpToolResult result = executeOneCall(call);
                    scope.bindTool(tool.outputs(), result);
                    programCounter++;
                }
                case GenerateAction gen -> {
                    if (breaker.shouldTrip()) {
                        emitMessage(LoopBreaker.tripNotice());
                        throw new BreakerTripException();
                    }
                    boolean useThought = gen.thought() != null ? gen.thought() : this.thought;
                    VetoResponse response = callGenerate(gen, useThought);
                    breaker.recordModelCall();
                    scope.bindGenerate(gen.outputs(), response);
                    if (useThought) {
                        appendThought(response);
                    }
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

    private VetoResponse callGenerate(GenerateAction gen, boolean useThought) {
        // A generate action invokes the model within the shared conversation with its scoped
        // prompt.
        // The generate prompt is fed as a user turn; the model responds in veto_pulse.
        appendTurn(TurnRecord.userPrompt(++turnNumber, gen.resolvePrompt(scope)));
        boolean effectiveThought = useThought;
        VetoResponse response = callModel(effectiveThought, false);
        appendThought(response);
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

    private VetoResponse callModel(boolean effectiveThought, boolean guidedSwitch) {
        CompiledPrompt compiled =
                promptCompiler.compile(
                        persona,
                        binding.systemPromptBase(),
                        List.copyOf(history),
                        effectiveThought,
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
                return ResponseEnforcer.enforce(response, effectiveThought, guidedSwitch);
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
        if (msg.contains("thought ON")) {
            return "thought field must be present and non-empty when the effective thought flag is ON";
        }
        // NOTE: "message required" must be matched before "thought OFF" — the message-required
        // exception string is "message required (thought OFF or is_finished)", which contains the
        // substring "thought OFF"; checking "thought OFF" first would mis-map it.
        if (msg.contains("message required")) {
            return "message field is required when thought is OFF or is_finished is true";
        }
        if (msg.contains("thought OFF")) {
            return "thought field must not be present when the effective thought flag is OFF";
        }
        if (msg.contains("mutually exclusive")) {
            return "either calls or actionsProgram, not both";
        }
        if (msg.contains("empty turn")) {
            return "at least one of thought, message, calls, or actionsProgram";
        }
        if (msg.contains("features")) {
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

    private McpToolResult executeOneConfirmedCall(ToolCall call) {
        ToolDefinition def = mcpEngine.resolveDefinition(call.toolName());
        if (def == null) {
            String obs = "Tool not found: " + call.toolName();
            appendTurn(TurnRecord.toolCall(turnNumber, call));
            appendObservation(call.toolName(), obs);
            return new McpToolResult(call.toolName(), call.callId(), false, obs);
        }

        appendTurn(TurnRecord.toolCall(turnNumber, call));

        // (c) plugin preAction chain
        for (LoopInterceptor plugin : interceptors) {
            if (!plugin.preAction(agentId, call)) {
                appendObservation(call.toolName(), "Blocked by plugin.");
                return new McpToolResult(
                        call.toolName(), call.callId(), false, "blocked by plugin");
            }
        }

        // (d) execute with tool call context (agentId + userId) threaded through.
        ToolCallContextHolder.set(agentId, userId);
        try {
            McpToolResult result = mcpEngine.execute(call, def);

            // (e) plugin postAction chain
            McpToolResult transformed = result;
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
            return transformed;
        } finally {
            ToolCallContextHolder.clear();
        }
    }

    private McpToolResult executeOneCall(ToolCall call) {
        ToolDefinition def = mcpEngine.resolveDefinition(call.toolName());
        if (def == null) {
            String obs = "Tool not found: " + call.toolName();
            appendTurn(TurnRecord.toolCall(++turnNumber, call));
            appendObservation(call.toolName(), obs);
            return new McpToolResult(call.toolName(), call.callId(), false, obs);
        }

        // (a) early-route agent tools past the Gateway + HITL.
        ApprovalDecision decision = ApprovalDecision.AUTO_APPROVE;
        if (!(def instanceof AgentToolDefinition)) {
            var result = gateway.screen(call, def);
            decision = hitlRegistry.decide(agentId, call, def, result);
            if (decision instanceof ApprovalDecision.AutoBlock ab) {
                appendTurn(TurnRecord.toolCall(++turnNumber, call));
                appendObservation(call.toolName(), "Blocked: " + ab.reason());
                return new McpToolResult(
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
                return new McpToolResult(call.toolName(), call.callId(), false, "REFUSED");
            }
            if (decision instanceof ApprovalDecision.Prompt p) {
                call = awaitVeto(call, def, p);
                if (call == null) {
                    this.state = AgentState.IDLE;
                    return new McpToolResult("", "", false, "declined");
                }
            }
        }

        appendTurn(TurnRecord.toolCall(turnNumber, call));

        // (c) plugin preAction chain (transform/observe/block — plugins only).
        for (LoopInterceptor plugin : interceptors) {
            if (!plugin.preAction(agentId, call)) {
                appendObservation(call.toolName(), "Blocked by plugin.");
                return new McpToolResult(
                        call.toolName(), call.callId(), false, "blocked by plugin");
            }
        }

        // (d) execute with tool call context (agentId + userId) threaded through.
        ToolCallContextHolder.set(agentId, userId);
        try {
            McpToolResult result = mcpEngine.execute(call, def);

            // (e) plugin postAction chain.
            McpToolResult transformed = result;
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
        // Capture the turn into Session LTM + the raw-turn audit log (Part 4 §3.1). Best-effort —
        // done outside the history lock so a DB write doesn't block history readers, and the
        // service swallows failures so the loop is never affected.
        if (captureService != null) {
            try {
                captureService.capture(turn, sessionId, userId);
            } catch (RuntimeException e) {
                log.warn("Agent {} turn capture failed", agentId, e);
            }
        }
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
            @NonNull String systemPromptBase) {}

    /** Signals a breaker trip (caught at the action boundary → IDLE + notice). */
    private static final class BreakerTripException extends RuntimeException {}
}
