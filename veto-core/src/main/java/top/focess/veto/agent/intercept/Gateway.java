package top.focess.veto.agent.intercept;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RemoteToolDefinition;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.screening.Danger;
import top.focess.veto.agent.screening.DangerComputation;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.screening.Relevance;
import top.focess.veto.agent.screening.Screening;
import top.focess.veto.agent.screening.SlmRelevanceProvider;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * The tool-call security screen. Screens every native/remote tool call into a typed {@link
 * GatewayResult}: a {@link GatewayResult.Screened} ({@link Relevance}, {@link Danger}) result
 * computed via {@link DangerComputation} + {@link SlmRelevanceProvider}, a {@link
 * GatewayResult.DriftResult} when a write target changed since the agent last read it (Scenario W;
 * a correctness check, not a danger class), or {@link GatewayResult.NotScreened} when an agent tool
 * early-routes past the Gateway. The {@link HitlRegistry} decides {@link ApprovalDecision} from it.
 * Agent tools early-route past the Gateway entirely and never reach here.
 *
 * <p><b>Note:</b> the advisory local-SLM semantic screening (relevance &amp; judgmental danger) is
 * not enabled; the degraded SLM relevance provider returns {@link Relevance#HIGH}. Under that
 * degradation the deterministic layer ({@link DangerComputation}) is authoritative. Constructed
 * per-agent so {@link #screen} matches the signature (the agent's {@link ReadHistory} is instance
 * state).
 */
public class Gateway {

    private static final Logger log = LoggerFactory.getLogger(Gateway.class);

    private final Workspace workspace;
    private final DangerComputation dangerComputation;
    private final SlmRelevanceProvider slmRelevance;
    private final DeployerPolicy policy;
    private final ProtectedSet protectedSet;
    private final ReadHistory readHistory;

    /**
     * Constructs a per-agent Gateway wired to its screening dependencies. Callers ({@link
     * top.focess.veto.agent.AgentService}, {@link top.focess.veto.agent.WorkflowRunner}) assemble
     * the deterministic {@link DangerComputation}, the (degraded) {@link SlmRelevanceProvider}, the
     * deployer {@link DeployerPolicy} + {@link ProtectedSet}, and this agent's {@link ReadHistory}.
     *
     * @param workspace the agent's workspace; incoming paths resolve against its resolver.
     * @param dangerComputation the deterministic danger computation (path/shell classification).
     * @param slmRelevance the SLM relevance seam (degraded → HIGH).
     * @param policy the install-time deployer policy (FULL_ACCESS / PROTECTED / SANDBOXED /
     *     TENANT).
     * @param protectedSet the protected paths (empty under FULL_ACCESS).
     * @param readHistory this agent's read-history (for write drift checks).
     */
    public Gateway(
            Workspace workspace,
            DangerComputation dangerComputation,
            SlmRelevanceProvider slmRelevance,
            DeployerPolicy policy,
            ProtectedSet protectedSet,
            ReadHistory readHistory) {
        this.workspace = workspace;
        this.dangerComputation = dangerComputation;
        this.slmRelevance = slmRelevance;
        this.policy = policy;
        this.protectedSet = protectedSet;
        this.readHistory = readHistory;
    }

    /**
     * Screens one native/remote tool call. Agent tools early-route to {@link
     * GatewayResult.NotScreened}; write-tool drift (the target changed since the agent last read
     * it) routes to {@link GatewayResult.DriftResult} as a correctness check before danger;
     * otherwise the call is screened to a {@link GatewayResult.Screened} ({@link Relevance}, {@link
     * Danger}) via {@link DangerComputation} + {@link SlmRelevanceProvider}. The {@link
     * HitlRegistry} decides {@link ApprovalDecision} from the result.
     */
    public GatewayResult screen(ToolCall call, ToolDefinition def) {
        if (def instanceof AgentToolDefinition) {
            return new GatewayResult.NotScreened();
        }
        List<String> paths = extractPathArgs(call, def);
        // drift is a correctness check on writes — checked before danger.
        if (def.risk() == RiskCategory.FILE_WRITE) {
            GatewayResult.DriftResult drift = checkWriteDrift(paths);
            if (drift != null) {
                return drift;
            }
        }
        Danger danger = dangerComputation.compute(def, call, workspace, policy, protectedSet);
        Relevance relevance = slmRelevance.relevance(call, def, /* thought */ null);
        VetoScenario scenario = scenarioFor(danger, def);
        String reason = reasonFor(danger, def);
        return new GatewayResult.Screened(new Screening(relevance, danger, scenario, reason));
    }

    private VetoScenario scenarioFor(Danger danger, ToolDefinition def) {
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

    private String reasonFor(Danger danger, ToolDefinition def) {
        return def.risk() + " -> " + danger;
    }

    // ── Path extraction ───────────────────────────────────────────────────

    /** Extracts filesystem-path arguments using the definition's {@link ParamCategory} hints. */
    private List<String> extractPathArgs(ToolCall call, ToolDefinition def) {
        List<String> paths = new ArrayList<>();
        Map<String, ParamCategory> hints = parameterHints(def);
        if (hints.isEmpty()) {
            return paths; // remote tools carry no hints → no path extraction
        }
        Map<String, Object> args = call.args();
        if (args == null) {
            return paths;
        }
        for (var entry : hints.entrySet()) {
            if (entry.getValue() == ParamCategory.FILESYSTEM_PATH) {
                Object v = args.get(entry.getKey());
                if (v instanceof String s && !s.isBlank()) {
                    paths.add(s);
                }
            }
        }
        return paths;
    }

    private Map<String, ParamCategory> parameterHints(ToolDefinition def) {
        return switch (def) {
            case NativeToolDefinition n -> n.paramHints();
            case AgentToolDefinition a -> a.paramHints();
            case RemoteToolDefinition r -> Map.of();
        };
    }

    // ── Write drift ─────────────

    /** Returns a drift result if a write target changed since the agent last read it, else null. */
    private GatewayResult.DriftResult checkWriteDrift(List<String> paths) {
        for (String path : paths) {
            ReadHistory.Snapshot prior = readHistory.lookup(path).orElse(null);
            if (prior == null) {
                continue; // write without prior read — nothing to compare, proceed
            }
            String currentHash =
                    computeHash(workspace.pathResolver().resolveToHost(path).hostPath());
            if (!prior.sha256Hash().equals(currentHash)) {
                return new GatewayResult.DriftResult(path, buildDiff(path, prior, currentHash));
            }
        }
        return null;
    }

    private String buildDiff(String path, ReadHistory.Snapshot prior, String currentHash) {
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

    private static String computeHash(Path path) {
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
    public ReadHistory readHistory() {
        return readHistory;
    }

    /** The operational workspace root this Gateway resolves against (for tests). */
    public Path workspaceRoot() {
        return workspace.pathResolver().operationalRoot();
    }

    /** Computes a SHA-256 hex hash of the given path (for read-time recording by the sandbox). */
    public static String hashOf(Path path) {
        return computeHash(path);
    }
}
