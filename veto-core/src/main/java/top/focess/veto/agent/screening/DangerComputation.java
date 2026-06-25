package top.focess.veto.agent.screening;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.workspace.PathResolver;
import top.focess.veto.agent.workspace.Resolution;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * The deterministic danger computation (screening_model.md §3.3), max-wins across: RiskCategory
 * base, args-aware path classification (via the Workspace PathResolver), shell args. SLM danger is
 * omitted (degraded) — finalDanger = detDanger.
 */
public class DangerComputation {

    private static final Set<String> EXEC_ALLOWLIST =
            Set.of(
                    "mvn", "gradle", "gradlew", "npm", "git", "python", "python3", "gcc", "g++",
                    "make", "java");
    private static final Set<String> EXEC_BLACKLIST =
            Set.of("nc", "ncat", "nmap", "curl", "wget", "ssh", "scp", "bash", "sh", "zsh", "fish");
    private static final Set<String> SECRET_PATTERNS =
            Set.of(
                    ".ssh",
                    ".aws",
                    ".gnupg",
                    ".env",
                    "id_rsa",
                    "id_ed25519",
                    "credentials",
                    ".npmrc",
                    ".pypirc");

    public Danger compute(
            ToolDefinition def,
            ToolCall call,
            Workspace workspace,
            DeployerPolicy policy,
            ProtectedSet protectedSet) {
        Danger base = baseFromRisk(def.risk());
        Danger pathDanger = pathDanger(def, call, workspace, policy, protectedSet);
        Danger shellDanger = shellDanger(def, call);
        return max(base, pathDanger, shellDanger);
    }

    private Danger baseFromRisk(RiskCategory risk) {
        return switch (risk) {
            case READ_ONLY -> Danger.SAFE;
            case FILE_WRITE -> Danger.ELEVATED;
            case SHELL_EXEC -> Danger.ELEVATED;
            case NETWORK -> Danger.ELEVATED;
            case AGENT -> Danger.SAFE; // not screened (early-routed)
        };
    }

    private Danger pathDanger(
            ToolDefinition def,
            ToolCall call,
            Workspace workspace,
            DeployerPolicy policy,
            ProtectedSet protectedSet) {
        Map<String, ParamCategory> hints =
                def instanceof NativeToolDefinition n ? n.paramHints() : Map.of();
        if (hints.isEmpty()) {
            return Danger.SAFE; // remote tools carry no path hints
        }
        Map<String, Object> args = call.args();
        if (args == null) {
            return Danger.SAFE;
        }
        PathResolver resolver = workspace.pathResolver();
        Danger worst = Danger.SAFE;
        for (var entry : hints.entrySet()) {
            if (entry.getValue() != ParamCategory.FILESYSTEM_PATH) {
                continue;
            }
            Object v = args.get(entry.getKey());
            if (!(v instanceof String s) || s.isBlank()) {
                continue;
            }
            Resolution res = resolver.resolveToHost(s);
            Danger d = classifyPath(res, def.risk(), policy, protectedSet);
            worst = max(worst, d);
        }
        return worst;
    }

    private Danger classifyPath(
            Resolution res, RiskCategory risk, DeployerPolicy policy, ProtectedSet protectedSet) {
        if (!res.inScope()) {
            return Danger.CRITICAL;
        }
        Path host = res.hostPath();
        String str = host.toString();
        // protected set (PROTECT_SENSITIVE only)
        if (policy == DeployerPolicy.PROTECT_SENSITIVE && protectedSet.covers(host)) {
            return Danger.CRITICAL;
        }
        // secret-location pattern
        for (String pat : SECRET_PATTERNS) {
            if (str.contains(pat)) {
                return Danger.DANGEROUS;
            }
        }
        // dependency/cache dir → ELEVATED (read only; writes stay at base ELEVATED anyway)
        if (str.contains("/target/")
                || str.contains("/build/")
                || str.contains("/node_modules/")
                || str.contains("/.git/")
                || str.contains("\\target\\")
                || str.contains("\\build\\")
                || str.contains("\\node_modules\\")
                || str.contains("\\.git\\")) {
            return Danger.ELEVATED;
        }
        // in-project → no bump
        return Danger.SAFE;
    }

    @SuppressWarnings("unchecked")
    private Danger shellDanger(ToolDefinition def, ToolCall call) {
        if (def.risk() != RiskCategory.SHELL_EXEC) {
            return Danger.SAFE;
        }
        Map<String, Object> args = call.args();
        if (args == null) {
            return Danger.CRITICAL; // malformed run_command
        }
        Object commandsObj = args.get("commands");
        if (!(commandsObj instanceof List<?> commands) || commands.isEmpty()) {
            return Danger.CRITICAL;
        }
        Danger worst = Danger.SAFE;
        for (Object cmdObj : commands) {
            if (!(cmdObj instanceof Map<?, ?> cmd)) {
                return Danger.CRITICAL;
            }
            Object execObj = cmd.get("executable");
            // Normalize the executable to its base name so a path-qualified blacklisted tool
            // (/bin/bash, /usr/bin/nc) still matches EXEC_BLACKLIST/isNetworkScan rather than
            // falling through to the grantable DANGEROUS bucket. (Restores the Gateway.baseName()
            // behavior deleted with the dead shell-cluster.)
            String exec = baseName(execObj == null ? "" : execObj.toString());
            Danger d;
            if (EXEC_BLACKLIST.contains(exec)) {
                d = Danger.CRITICAL;
            } else if (isNetworkScan(exec)) {
                d = Danger.DANGEROUS;
            } else if (!EXEC_ALLOWLIST.contains(exec)) {
                d = Danger.DANGEROUS; // non-allowlisted executable bumps to DANGEROUS
            } else {
                d = Danger.SAFE;
            }
            worst = max(worst, d);
        }
        return worst;
    }

    private boolean isNetworkScan(String exec) {
        return Set.of("curl", "wget", "nc", "ncat", "nmap", "ftp", "telnet").contains(exec);
    }

    /**
     * Strips the path prefix from an executable invocation, returning the bare command name ({@code
     * /usr/bin/nc} → {@code nc}, {@code C:\tools\curl.exe} → {@code curl.exe}). Cross-platform:
     * handles both {@code /} and {@code \} separators without parsing via {@link Path} (which is
     * filesystem-sensitive and can misparse foreign-style paths).
     */
    private static String baseName(String exe) {
        int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        return slash >= 0 ? exe.substring(slash + 1) : exe;
    }

    private static Danger max(Danger... ds) {
        Danger worst = Danger.SAFE;
        for (Danger d : ds) {
            if (d == null) continue;
            if (d.ordinal() > worst.ordinal()) {
                worst = d;
            }
        }
        return worst;
    }
}
