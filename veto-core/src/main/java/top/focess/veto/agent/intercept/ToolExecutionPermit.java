package top.focess.veto.agent.intercept;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.AgentToolDefinition;
import top.focess.veto.agent.mcp.NativeToolDefinition;
import top.focess.veto.agent.mcp.ParamCategory;
import top.focess.veto.agent.mcp.RemoteToolDefinition;
import top.focess.veto.agent.mcp.ToolDefinition;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.workspace.Resolution;
import top.focess.veto.agent.workspace.Workspace;
import top.focess.veto.llm.core.ToolCall;

/**
 * Immutable binding between screened filesystem arguments and their canonical execution targets. It
 * is captured during Gateway screening and revalidated after HITL, immediately before the tool
 * executes.
 */
public record ToolExecutionPermit(
        @NonNull String toolName,
        @NonNull Map<@NonNull String, Object> screenedArguments,
        @NonNull Map<@NonNull String, @NonNull AuthorizedPath> filesystemPaths,
        @NonNull List<@NonNull Path> workspaceRoots,
        @NonNull DeployerPolicy deployerPolicy,
        @NonNull Set<@NonNull Path> protectedPaths) {

    private static final @NonNull ToolExecutionPermit EMPTY =
            new ToolExecutionPermit(
                    "", Map.of(), Map.of(), List.of(), DeployerPolicy.FULL_ACCESS, Set.of());

    public ToolExecutionPermit {
        screenedArguments = Map.copyOf(screenedArguments);
        filesystemPaths = Map.copyOf(filesystemPaths);
        workspaceRoots =
                workspaceRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        protectedPaths =
                protectedPaths.stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static @NonNull ToolExecutionPermit empty() {
        return EMPTY;
    }

    /** Captures all FILESYSTEM_PATH arguments using the workspace resolver. */
    public static @NonNull ToolExecutionPermit capture(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull Workspace workspace) {
        return capture(
                call, definition, workspace, DeployerPolicy.FULL_ACCESS, ProtectedSet.empty());
    }

    /** Captures filesystem targets together with the workspace policy the Sandbox must enforce. */
    public static @NonNull ToolExecutionPermit capture(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull Workspace workspace,
            @NonNull DeployerPolicy deployerPolicy,
            @NonNull ProtectedSet protectedSet) {
        Map<@NonNull String, @NonNull ParamCategory> hints = parameterHints(definition);
        List<Path> roots = workspace.hostRoots();
        Set<Path> denied =
                deployerPolicy == DeployerPolicy.FULL_ACCESS ? Set.of() : protectedSet.paths();
        if (hints.isEmpty()) {
            return new ToolExecutionPermit(
                    call.toolName(), call.args(), Map.of(), roots, deployerPolicy, denied);
        }
        Map<@NonNull String, @NonNull AuthorizedPath> paths = new LinkedHashMap<>();
        for (var entry : hints.entrySet()) {
            if (entry.getValue() != ParamCategory.FILESYSTEM_PATH) {
                continue;
            }
            Object raw = call.args().get(entry.getKey());
            if (!(raw instanceof String requestedPath) || requestedPath.isBlank()) {
                continue;
            }
            Resolution resolution;
            try {
                resolution = workspace.pathResolver().resolveToHost(requestedPath);
            } catch (RuntimeException e) {
                resolution = Resolution.outOfScope(null);
            }
            paths.put(
                    entry.getKey(),
                    new AuthorizedPath(
                            entry.getKey(),
                            requestedPath,
                            resolution.hostPath(),
                            resolution.rootIndex(),
                            resolution.inScope()));
        }
        return new ToolExecutionPermit(
                call.toolName(), call.args(), paths, roots, deployerPolicy, denied);
    }

    /** Whether this permit still binds the exact immutable tool call. */
    public boolean matchesCall(@NonNull ToolCall call) {
        return toolName.equals(call.toolName()) && screenedArguments.equals(call.args());
    }

    /** Whether this permit and a fresh capture still bind the same call and resources. */
    public boolean sameTargets(@NonNull ToolExecutionPermit current) {
        if (!toolName.equals(current.toolName)
                || !screenedArguments.equals(current.screenedArguments)) {
            return false;
        }
        if (!filesystemPaths.keySet().equals(current.filesystemPaths.keySet())) {
            return false;
        }
        if (!workspaceRoots.equals(current.workspaceRoots)
                || deployerPolicy != current.deployerPolicy
                || !protectedPaths.equals(current.protectedPaths)) {
            return false;
        }
        for (var entry : filesystemPaths.entrySet()) {
            AuthorizedPath now = current.filesystemPaths.get(entry.getKey());
            if (now == null || !entry.getValue().sameTarget(now)) {
                return false;
            }
        }
        return true;
    }

    public @NonNull List<@NonNull String> requestedPaths() {
        return filesystemPaths.values().stream().map(AuthorizedPath::requestedPath).toList();
    }

    public AuthorizedPath path(@NonNull String argumentName) {
        return filesystemPaths.get(argumentName);
    }

    /** Returns the declared workspace root that contains an authorized path. */
    public @NonNull Path sandboxRoot(@NonNull String argumentName) {
        AuthorizedPath authorized = filesystemPaths.get(argumentName);
        if (authorized == null || authorized.hostPath() == null) {
            throw new SecurityException(
                    "Missing authorized filesystem target for parameter '" + argumentName + "'");
        }
        if (authorized.rootIndex() >= 0 && authorized.rootIndex() < workspaceRoots.size()) {
            return workspaceRoots.get(authorized.rootIndex());
        }
        Path host = authorized.hostPath().toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(host)) {
            return host;
        }
        Path parent = host.getParent();
        if (parent == null) {
            throw new SecurityException("Authorized path has no sandbox root: " + host);
        }
        return parent;
    }

    private static @NonNull Map<@NonNull String, @NonNull ParamCategory> parameterHints(
            @NonNull ToolDefinition definition) {
        return switch (definition) {
            case NativeToolDefinition nativeDefinition -> nativeDefinition.paramHints();
            case AgentToolDefinition agentDefinition -> agentDefinition.paramHints();
            case RemoteToolDefinition remoteDefinition -> Map.of();
        };
    }

    /** One security-relevant path argument and the canonical target screened for it. */
    public record AuthorizedPath(
            @NonNull String argumentName,
            @NonNull String requestedPath,
            Path hostPath,
            int rootIndex,
            boolean inScope) {

        boolean sameTarget(@NonNull AuthorizedPath current) {
            return argumentName.equals(current.argumentName)
                    && requestedPath.equals(current.requestedPath)
                    && Objects.equals(hostPath, current.hostPath)
                    && rootIndex == current.rootIndex
                    && inScope == current.inScope;
        }
    }
}
