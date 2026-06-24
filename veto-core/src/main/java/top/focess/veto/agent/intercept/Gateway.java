package top.focess.veto.agent.intercept;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.agent.drift.ReadHistory;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RemoteToolDefinition;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.llm.core.ToolCall;

/**
 * The deterministic tool-call security screen. Screens every <b>native/remote</b> tool call into a
 * typed {@link Verdict} — path validation (filesystem-path args under the workspace root), an
 * executable allowlist + blacklist for {@code run_command}, and a {@link ReadHistory} drift check
 * on writes. Agent tools early-route past the Gateway entirely (step 4a) and never reach here.
 *
 * <p><b>Note:</b> the advisory local-SLM semantic screening (relevance &amp; judgmental danger) is
 * not enabled and is skipped here. Under that degradation the deterministic layer is authoritative:
 * only deterministic trips (out-of-bounds path, blacklisted/non-allowlisted executable, write
 * drift) produce a non-{@link Verdict.Safe} verdict. SLM-driven prompting of otherwise-clean
 * writes/network/exec is deferred. Constructed per-agent so {@link #screen} matches the signature
 * (the agent's {@link ReadHistory} is instance state).
 */
public class Gateway {

    private static final Logger log = LoggerFactory.getLogger(Gateway.class);

    /** Default executable allowlist (deployer-configurable via Runtime Profile). */
    private static final Set<String> DEFAULT_EXEC_ALLOWLIST =
            Set.of(
                    "mvn", "gradle", "gradlew", "npm", "git", "python", "python3", "gcc", "g++",
                    "make");

    /** Default network/scanner/spawn blacklist → CRITICAL → BLOCKED. */
    private static final Set<String> DEFAULT_EXEC_BLACKLIST =
            Set.of("nc", "ncat", "nmap", "curl", "wget", "ssh", "scp", "bash", "sh", "zsh", "fish");

    private final Path workspaceRoot;
    private final Set<String> execAllowlist;
    private final Set<String> execBlacklist;
    private final ReadHistory readHistory;

    /**
     * Constructs a per-agent Gateway.
     *
     * @param workspaceRoot the agent's virtual root; incoming paths resolve against it.
     * @param readHistory this agent's read-history (for write drift checks).
     */
    public Gateway(Path workspaceRoot, ReadHistory readHistory) {
        this(workspaceRoot, DEFAULT_EXEC_ALLOWLIST, DEFAULT_EXEC_BLACKLIST, readHistory);
    }

    /** Full constructor (for tests / Runtime-Profile-injected allow/blacklists). */
    public Gateway(
            Path workspaceRoot,
            Set<String> execAllowlist,
            Set<String> execBlacklist,
            ReadHistory readHistory) {
        this.workspaceRoot = workspaceRoot;
        this.execAllowlist = execAllowlist;
        this.execBlacklist = execBlacklist;
        this.readHistory = readHistory;
    }

    /**
     * Screens one native/remote tool call. Runs deterministic checks only; the SLM semantic screen
     * is not enabled. Returns a typed {@link Verdict}; the {@link HitlRegistry} decides {@link
     * ApprovalDecision} from it.
     */
    public Verdict screen(ToolCall call, ToolDefinition def) {
        if (def instanceof AgentToolDefinition) {
            // Agent tools early-route past the Gateway — never screened here.
            return Verdict.SAFE;
        }

        List<String> paths = extractPathArgs(call, def);
        Verdict pathVerdict = validatePaths(paths);
        if (pathVerdict != null) {
            return pathVerdict;
        }

        return switch (def.risk()) {
            case READ_ONLY -> Verdict.SAFE;
            case FILE_WRITE -> checkWriteDrift(paths);
            case SHELL_EXEC -> checkShellCommand(call);
            case NETWORK -> Verdict.SAFE; // clean egress: deterministic floor clean (SLM deferred)
            case AGENT -> Verdict.SAFE;
        };
    }

    // ── Path validation ───────────────────────────────────────────────────

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

    /** Returns a BLOCKED verdict if any path escapes the workspace root, else null. */
    private Verdict validatePaths(List<String> paths) {
        for (String raw : paths) {
            Path resolved = workspaceRoot.resolve(raw).normalize();
            Path normalizedRoot = workspaceRoot.normalize();
            if (!resolved.startsWith(normalizedRoot)) {
                return new Verdict.Blocked(
                        "path escapes workspace root: " + raw + " -> " + resolved);
            }
        }
        return null;
    }

    // ── Write drift ─────────────

    private Verdict checkWriteDrift(List<String> paths) {
        for (String path : paths) {
            ReadHistory.Snapshot prior = readHistory.lookup(path).orElse(null);
            if (prior == null) {
                continue; // write without prior read — nothing to compare, proceed
            }
            String currentHash = computeHash(workspaceRoot.resolve(path));
            if (!prior.sha256Hash().equals(currentHash)) {
                return new Verdict.Drift(path, buildDiff(path, prior, currentHash));
            }
        }
        return Verdict.SAFE;
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

    // ── Shell command integrity ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Verdict checkShellCommand(ToolCall call) {
        Map<String, Object> args = call.args();
        if (args == null) {
            return Verdict.SAFE;
        }
        // run_command takes a structured command array (no shell); inspect each executable.
        Object commands = args.get("commands");
        if (commands instanceof List<?> list) {
            for (Object item : list) {
                String exe = extractExecutable(item);
                Verdict v = classifyExecutable(exe);
                if (v != Verdict.SAFE) {
                    return v;
                }
            }
            return Verdict.SAFE;
        }
        // Fallback: a single executable field.
        Object exe = args.get("executable");
        if (exe instanceof String s) {
            return classifyExecutable(s);
        }
        return Verdict.SAFE;
    }

    @SuppressWarnings("unchecked")
    private String extractExecutable(Object commandItem) {
        if (commandItem instanceof String s) {
            return baseName(s);
        }
        if (commandItem instanceof Map<?, ?> m) {
            Object exe = m.get("executable");
            if (exe instanceof String s) {
                return baseName(s);
            }
        }
        return "";
    }

    private Verdict classifyExecutable(String exe) {
        if (exe == null || exe.isBlank()) {
            return Verdict.SAFE;
        }
        if (execBlacklist.contains(exe)) {
            return new Verdict.Blocked("blacklisted executable: " + exe);
        }
        if (!execAllowlist.contains(exe)) {
            // Non-allowlisted but not blacklisted → first-time pattern (Scenario E3).
            return new Verdict.Risky(
                    VetoScenario.EXEC_FIRST_TIME, "non-allowlisted executable: " + exe);
        }
        return Verdict.SAFE;
    }

    private static String baseName(String exe) {
        int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        return slash >= 0 ? exe.substring(slash + 1) : exe;
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

    /** The workspace root this Gateway resolves against (for tests). */
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    /** Computes a SHA-256 hex hash of the given path (for read-time recording by the sandbox). */
    public static String hashOf(Path path) {
        return computeHash(path);
    }
}
