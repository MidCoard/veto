package top.focess.veto.agent.screening;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.intercept.ToolExecutionPermit;
import top.focess.veto.agent.mcp.RiskCategory;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.workspace.Resolution;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * The deterministic danger computation, max-wins across: RiskCategory base, args-aware path
 * classification (via the Workspace PathResolver), shell args. SLM danger is omitted (degraded) —
 * finalDanger = detDanger.
 */
public class DangerComputation {

    private static final @NonNull Set<String> EXEC_ALLOWLIST =
            Set.of(
                    "mvn", "gradle", "gradlew", "npm", "npx", "node", "git", "python", "python3",
                    "gcc", "g++", "make", "java");

    /** Windows launcher extensions stripped before allowlist/blacklist matching. */
    private static final @NonNull List<String> WINDOWS_EXEC_EXTENSIONS =
            List.of(".exe", ".cmd", ".bat", ".com");

    // curl is deliberately NOT blacklisted: it stays DANGEROUS via the network-scan rule below, so
    // it is approval-gated (the user can grant a localhost API test) rather than auto-blocked.
    private static final @NonNull Set<String> EXEC_BLACKLIST =
            Set.of("nc", "ncat", "nmap", "wget", "ssh", "scp", "bash", "sh", "zsh", "fish");

    /**
     * Process-termination executables. Terminating a process is always a user decision — classified
     * DANGEROUS (HITL-gated, grantable) rather than left to the generic non-allowlist fallthrough,
     * so the intent is explicit and survives future allowlist edits. The agent's OWN background
     * tasks are stopped with the {@code stop_task} tool, not an OS kill.
     */
    private static final @NonNull Set<String> PROCESS_KILLERS =
            Set.of("taskkill", "tskill", "kill", "killall", "pkill");

    private static final @NonNull Set<String> SECRET_PATTERNS =
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

    public @NonNull Danger compute(
            @NonNull ToolDefinition def,
            @NonNull ToolCall call,
            @NonNull Workspace workspace,
            @NonNull DeployerPolicy policy,
            @NonNull ProtectedSet protectedSet) {
        ToolExecutionPermit permit =
                ToolExecutionPermit.capture(call, def, workspace, policy, protectedSet);
        return compute(def, call, workspace, policy, protectedSet, permit);
    }

    /** Computes danger from the exact canonical targets already captured for this tool call. */
    public @NonNull Danger compute(
            @NonNull ToolDefinition def,
            @NonNull ToolCall call,
            @NonNull Workspace workspace,
            @NonNull DeployerPolicy policy,
            @NonNull ProtectedSet protectedSet,
            @NonNull ToolExecutionPermit permit) {
        Danger base = baseFromRisk(def.risk());
        Danger pathDanger = pathDanger(def, permit, workspace, policy, protectedSet);
        Danger executionRootDanger =
                executionRootDanger(def, permit, workspace, policy, protectedSet);
        Danger shellDanger = shellDanger(def, call);
        return max(base, pathDanger, executionRootDanger, shellDanger);
    }

    private @NonNull Danger baseFromRisk(@NonNull RiskCategory risk) {
        return switch (risk) {
            case READ_ONLY -> Danger.SAFE;
            case FILE_WRITE, SHELL_EXEC, NETWORK -> Danger.ELEVATED;
            case AGENT -> Danger.SAFE; // not screened (early-routed)
        };
    }

    private @NonNull Danger pathDanger(
            @NonNull ToolDefinition def,
            @NonNull ToolExecutionPermit permit,
            @NonNull Workspace workspace,
            @NonNull DeployerPolicy policy,
            @NonNull ProtectedSet protectedSet) {
        Danger worst = Danger.SAFE;
        for (ToolExecutionPermit.AuthorizedPath path : permit.filesystemPaths().values()) {
            Resolution res = new Resolution(path.hostPath(), path.rootIndex(), path.inScope());
            Danger d = classifyPath(res, def.risk(), policy, protectedSet, workspace);
            worst = max(worst, d);
        }
        return worst;
    }

    /**
     * Process tools can mutate their working directory even though it is not a model-supplied
     * argument. Classify that implicit target with write semantics before any process starts.
     */
    private @NonNull Danger executionRootDanger(
            @NonNull ToolDefinition def,
            @NonNull ToolExecutionPermit permit,
            @NonNull Workspace workspace,
            @NonNull DeployerPolicy policy,
            @NonNull ProtectedSet protectedSet) {
        if (def.risk() != RiskCategory.SHELL_EXEC) {
            return Danger.SAFE;
        }
        Path executionPath = permit.executionRoot();
        if (executionPath == null) {
            return Danger.CRITICAL;
        }
        int rootIndex = workspace.currentRootIndex();
        Resolution executionRoot = new Resolution(executionPath, rootIndex, true);
        return classifyPath(
                executionRoot, RiskCategory.FILE_WRITE, policy, protectedSet, workspace);
    }

    private @NonNull Danger classifyPath(
            @NonNull Resolution res,
            @NonNull RiskCategory risk,
            @NonNull DeployerPolicy policy,
            @NonNull ProtectedSet protectedSet,
            @NonNull Workspace workspace) {
        Path host = res.hostPath();
        if (host == null) {
            return Danger.CRITICAL;
        }
        String str = host.toString();

        // 1. Check policies that make certain paths CRITICAL
        if (policy != DeployerPolicy.FULL_ACCESS) {
            if (protectedSet.covers(host)) {
                return Danger.CRITICAL;
            }
        }
        if (policy == DeployerPolicy.SANDBOXED) {
            if (!res.inScope()) {
                return Danger.CRITICAL;
            }
        } else if (policy == DeployerPolicy.TENANT) {
            if (!res.inScope()) {
                return Danger.CRITICAL;
            }
            if (res.rootIndex() >= 0 && res.rootIndex() < workspace.roots().size()) {
                top.focess.veto.agent.workspace.WorkspaceRoot root =
                        workspace.roots().get(res.rootIndex());
                if (root.trust() == top.focess.veto.agent.workspace.TrustMarker.SHARED_GRANT) {
                    // Shared root — reads are allowed, writes are CRITICAL (grant mode would
                    // distinguish read vs read+write, but for the MVP read is grantable
                    // and write is grant-gated).
                    if (risk == RiskCategory.FILE_WRITE) {
                        return Danger.CRITICAL;
                    }
                }
            }
        }

        // 2. Under FULL_ACCESS or non-critical paths in other policies:
        if (isDeviceOrKernelPath(str)) {
            return Danger.DANGEROUS;
        }

        // secret-location pattern
        for (String pat : SECRET_PATTERNS) {
            if (str.contains(pat)) {
                return Danger.DANGEROUS;
            }
        }

        // arbitrary host path (out of scope)
        if (!res.inScope()) {
            return risk == RiskCategory.FILE_WRITE ? Danger.DANGEROUS : Danger.ELEVATED;
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

    private boolean isDeviceOrKernelPath(@NonNull String path) {
        String p = path.toLowerCase();
        return p.startsWith("/dev/")
                || p.startsWith("/proc/")
                || p.startsWith("/sys/")
                || p.startsWith("\\\\.\\");
    }

    @SuppressWarnings("unchecked")
    private @NonNull Danger shellDanger(@NonNull ToolDefinition def, @NonNull ToolCall call) {
        if (def.risk() != RiskCategory.SHELL_EXEC) {
            return Danger.SAFE;
        }
        Map<String, Object> args = call.args();
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
            // Normalize the executable to its extensionless, lowercase base name so a
            // path-qualified or Windows-style tool (/bin/bash, C:\nodejs\node.exe) still matches
            // EXEC_BLACKLIST / EXEC_ALLOWLIST instead of falling through to the grantable
            // DANGEROUS bucket. (Restores the Gateway.baseName() behavior deleted with the dead
            // shell-cluster, extended for Windows launcher extensions.)
            String exec = normalizeExec(baseName(execObj == null ? "" : execObj.toString()));
            Danger d;
            if (EXEC_BLACKLIST.contains(exec)) {
                d = Danger.CRITICAL;
            } else if (isNetworkScan(exec)) {
                d = Danger.DANGEROUS;
            } else if (PROCESS_KILLERS.contains(exec)) {
                d = Danger.DANGEROUS; // process termination is always a user decision
            } else if (!EXEC_ALLOWLIST.contains(exec)) {
                d = Danger.DANGEROUS; // non-allowlisted executable bumps to DANGEROUS
            } else {
                d = Danger.SAFE;
            }
            worst = max(worst, d);
        }
        if (Boolean.TRUE.equals(args.get("network"))) {
            worst = max(worst, Danger.DANGEROUS);
        }
        return worst;
    }

    private boolean isNetworkScan(@NonNull String exec) {
        return Set.of("curl", "wget", "nc", "ncat", "nmap", "ftp", "telnet").contains(exec);
    }

    /**
     * Strips the path prefix from an executable invocation, returning the bare command name ({@code
     * /usr/bin/nc} → {@code nc}, {@code C:\tools\curl.exe} → {@code curl.exe}). Cross-platform:
     * handles both {@code /} and {@code \} separators without parsing via {@link Path} (which is
     * filesystem-sensitive and can misparse foreign-style paths).
     */
    private static @NonNull String baseName(@NonNull String exe) {
        int slash = Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\'));
        return slash >= 0 ? exe.substring(slash + 1) : exe;
    }

    /**
     * Lowercases and strips a Windows launcher extension ({@code node.EXE} → {@code node}) so the
     * allowlist/blacklist sets — plain lowercase names — match executables on both platforms.
     */
    private static @NonNull String normalizeExec(@NonNull String exe) {
        String name = exe.toLowerCase(java.util.Locale.ROOT);
        for (String ext : WINDOWS_EXEC_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    private static @NonNull Danger max(Danger @NonNull ... ds) {
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
