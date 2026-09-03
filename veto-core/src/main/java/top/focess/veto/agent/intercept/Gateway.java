package top.focess.veto.agent.intercept;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.Relevance;
import top.focess.veto.agent.screening.Screening;
import top.focess.veto.agent.screening.SlmScreening;
import top.focess.veto.agent.screening.SlmScreeningProvider;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * The tool-call security screen. Screens every native/remote tool call into a typed {@link
 * GatewayResult}: a {@link GatewayResult.Screened} ({@link Relevance}, {@link Danger}) result
 * computed via {@link DangerComputation} plus an optional {@link SlmScreeningProvider}, a {@link
 * GatewayResult.DriftResult} when a write target changed since the agent last read it (Scenario W;
 * a correctness check, not a danger class), or {@link GatewayResult.NotScreened} when an agent tool
 * early-routes past the Gateway. The {@link HitlRegistry} decides {@link ApprovalDecision} from it.
 * Agent tools early-route past the Gateway entirely and never reach here.
 *
 * <p>When no usable SLM judgment exists, the result explicitly records {@code slmEvaluated=false};
 * the deterministic danger layer remains authoritative and relevance conservatively defaults to
 * HIGH without pretending that the SLM produced that value.
 */
public class Gateway {

    private static final @NonNull Logger log =
            LoggerFactory.getLogger("top.focess.veto.agent.intercept.Gateway");

    private final @NonNull Workspace workspace;
    private final @NonNull DangerComputation dangerComputation;
    private final @NonNull SlmScreeningProvider slmScreeningProvider;
    private final @NonNull DeployerPolicy policy;
    private final @NonNull ProtectedSet protectedSet;
    private final @NonNull ReadHistory readHistory;

    /**
     * Constructs a per-agent Gateway wired to its screening dependencies. The caller ({@link
     * top.focess.veto.agent.AgentService}) assembles the deterministic {@link DangerComputation},
     * the optional {@link SlmScreeningProvider}, the deployer {@link DeployerPolicy} + {@link
     * ProtectedSet}, and this agent's {@link ReadHistory}.
     *
     * @param workspace the agent's workspace; incoming paths resolve against its resolver.
     * @param dangerComputation the deterministic danger computation (path/shell classification).
     * @param slmScreeningProvider the optional SLM relevance-and-danger seam.
     * @param policy the install-time deployer policy (FULL_ACCESS / PROTECTED / SANDBOXED /
     *     TENANT).
     * @param protectedSet the protected paths (empty under FULL_ACCESS).
     * @param readHistory this agent's read-history (for write drift checks).
     */
    public Gateway(
            @NonNull Workspace workspace,
            @NonNull DangerComputation dangerComputation,
            @NonNull SlmScreeningProvider slmScreeningProvider,
            @NonNull DeployerPolicy policy,
            @NonNull ProtectedSet protectedSet,
            @NonNull ReadHistory readHistory) {
        this.workspace = workspace;
        this.dangerComputation = dangerComputation;
        this.slmScreeningProvider = slmScreeningProvider;
        this.policy = policy;
        this.protectedSet = protectedSet;
        this.readHistory = readHistory;
    }

    /**
     * Screens one native/remote tool call. Agent tools early-route to {@link
     * GatewayResult.NotScreened}; write-tool drift (the target changed since the agent last read
     * it) routes to {@link GatewayResult.DriftResult} as a correctness check before danger;
     * otherwise the call is screened to a {@link GatewayResult.Screened} ({@link Relevance}, {@link
     * Danger}) via {@link DangerComputation} plus optional {@link SlmScreeningProvider}. The {@link
     * HitlRegistry} decides {@link ApprovalDecision} from the result.
     */
    public @NonNull GatewayResult screen(@NonNull ToolCall call, @NonNull ToolDefinition def) {
        return screen(call, def, null);
    }

    public @NonNull GatewayResult screen(
            @NonNull ToolCall call, @NonNull ToolDefinition def, String thought) {
        return screen(call, def, null, thought);
    }

    public @NonNull GatewayResult screen(
            @NonNull ToolCall call,
            @NonNull ToolDefinition def,
            String activeTask,
            String thought) {
        if (def instanceof AgentToolDefinition) {
            return new GatewayResult.NotScreened();
        }
        ToolExecutionPermit executionPermit =
                ToolExecutionPermit.capture(call, def, workspace, policy, protectedSet);
        List<@NonNull String> paths = executionPermit.requestedPaths();
        // drift is a correctness check on writes — checked before danger.
        if (def.risk() == RiskCategory.FILE_WRITE) {
            GatewayResult.DriftResult drift = checkWriteDrift(paths, executionPermit);
            if (drift != null) {
                return drift;
            }
        }
        Danger deterministicDanger =
                dangerComputation.compute(
                        def, call, workspace, policy, protectedSet, executionPermit);
        Optional<SlmScreening> advisory =
                slmScreeningProvider.screen(call, def, activeTask, thought);
        Danger danger =
                advisory.map(screening -> maxDanger(deterministicDanger, screening.danger()))
                        .orElse(deterministicDanger);
        if (def instanceof top.focess.veto.agent.mcp.NativeToolDefinition nativeDefinition
                && nativeDefinition.requiresSemanticScreening()
                && advisory.isEmpty()) {
            danger = maxDanger(danger, Danger.DANGEROUS);
        }
        Relevance relevance = advisory.map(SlmScreening::relevance).orElse(Relevance.HIGH);
        VetoScenario scenario = scenarioFor(danger, def);
        String reason = reasonFor(deterministicDanger, advisory, danger, def);
        return new GatewayResult.Screened(
                new Screening(relevance, danger, advisory.isPresent(), scenario, reason),
                executionPermit);
    }

    /**
     * Re-resolves security-relevant arguments after approval. A target change invalidates the old
     * decision; the caller returns a denied tool observation and the model must issue a fresh call.
     */
    public @NonNull ToolExecutionPermit revalidateExecution(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull ToolExecutionPermit screenedPermit) {
        if (definition instanceof AgentToolDefinition) {
            return ToolExecutionPermit.empty();
        }
        ToolExecutionPermit current =
                ToolExecutionPermit.capture(call, definition, workspace, policy, protectedSet);
        if (!screenedPermit.sameTargets(current)) {
            throw new SecurityException(
                    "Filesystem target changed after screening; submit a fresh tool call");
        }
        return current;
    }

    private static @NonNull Danger maxDanger(@NonNull Danger left, @NonNull Danger right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private @NonNull VetoScenario scenarioFor(@NonNull Danger danger, @NonNull ToolDefinition def) {
        if (danger == Danger.CRITICAL)
            return VetoScenario
                    .EXEC_DETERMINISTIC; // E1-style; the registry offers options per scenario
        return switch (def.risk()) {
            case READ_ONLY -> VetoScenario.READ;
            case FILE_WRITE ->
                    VetoScenario
                            .GENERIC; // non-drift write — generic (no WRITE_DRIFT scenario here)
            case SHELL_EXEC, NETWORK -> VetoScenario.EXEC_FIRST_TIME; // E3 first-time pattern
            case AGENT -> VetoScenario.GENERIC;
        };
    }

    private @NonNull String reasonFor(
            @NonNull Danger deterministicDanger,
            @NonNull Optional<SlmScreening> advisory,
            @NonNull Danger finalDanger,
            @NonNull ToolDefinition def) {
        String slm =
                advisory.map(value -> value.danger() + " (" + value.reason() + ")")
                        .orElse("unavailable");
        return def.risk()
                + " -> deterministic="
                + deterministicDanger
                + ", slm="
                + slm
                + ", final="
                + finalDanger;
    }

    // ── Write drift ─────────────

    /** Returns a drift result if a write target changed since the agent last read it, else null. */
    private GatewayResult.DriftResult checkWriteDrift(
            @NonNull List<@NonNull String> paths, @NonNull ToolExecutionPermit executionPermit) {
        for (String path : paths) {
            ReadHistory.Snapshot prior = readHistory.lookup(path).orElse(null);
            if (prior == null) {
                continue; // write without prior read — nothing to compare, proceed
            }
            Path hostPath = workspace.pathResolver().resolveToHost(path).hostPath();
            String currentHash = hostPath == null ? "<unresolved>" : computeHash(hostPath);
            if (!prior.sha256Hash().equals(currentHash)) {
                return new GatewayResult.DriftResult(
                        path, buildDiff(path, prior, currentHash), executionPermit);
            }
        }
        return null;
    }

    private @NonNull String buildDiff(
            @NonNull String path,
            ReadHistory.@NonNull Snapshot prior,
            @NonNull String currentHash) {
        return "--- "
                + path
                + " (read at size="
                + prior.fileSize()
                + ", hash="
                + prior.sha256Hash()
                + ")\n+++ "
                + path
                + " (current hash="
                + currentHash
                + ")\n"
                + "File was modified externally since the agent last read it.";
    }

    // ── Hashing ──────────────────────────────────────────────────────────────

    private static @NonNull String computeHash(@NonNull Path path) {
        try {
            if (!Files.exists(path)) {
                return "<missing>";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            log.warn("Failed to hash {}", path, e);
            return "<error>";
        }
    }

    /** This Gateway's read-history (the agent's drift state; passed to the runner). */
    public @NonNull ReadHistory readHistory() {
        return readHistory;
    }

    /**
     * The per-session {@link Workspace} this Gateway screens against. Threaded to the {@link
     * top.focess.veto.agent.loop.PromptCompiler} so the system prompt mounts the session's actual
     * roots (not the default bean workspace).
     */
    public @NonNull Workspace workspace() {
        return workspace;
    }
}
