package top.focess.veto.agent.intercept;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.DeployerPolicy;
import top.focess.veto.agent.screening.ProtectedSet;
import top.focess.veto.agent.tool.AgentToolDefinition;
import top.focess.veto.agent.tool.NativeToolDefinition;
import top.focess.veto.agent.tool.ParamCategory;
import top.focess.veto.agent.tool.RemoteToolDefinition;
import top.focess.veto.agent.tool.ToolCallContext;
import top.focess.veto.agent.tool.ToolCapability;
import top.focess.veto.agent.tool.ToolDefinition;
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
        @NonNull String callId,
        @NonNull ToolCapability capability,
        CallerBinding caller,
        @NonNull Map<@NonNull String, Object> screenedArguments,
        @NonNull Map<@NonNull String, @NonNull AuthorizedPath> filesystemPaths,
        @NonNull List<@NonNull Path> workspaceRoots,
        Path executionRoot,
        @NonNull DeployerPolicy deployerPolicy,
        @NonNull Set<@NonNull Path> protectedPaths,
        TaskBinding taskBinding) {

    private static final @NonNull ToolExecutionPermit EMPTY =
            new ToolExecutionPermit(
                    "",
                    "",
                    ToolCapability.AGENT_CONTROL,
                    null,
                    Map.of(),
                    Map.of(),
                    List.of(),
                    null,
                    DeployerPolicy.FULL_ACCESS,
                    Set.of(),
                    null);

    public ToolExecutionPermit {
        screenedArguments = Map.copyOf(screenedArguments);
        filesystemPaths = Map.copyOf(filesystemPaths);
        workspaceRoots =
                workspaceRoots.stream().map(path -> path.toAbsolutePath().normalize()).toList();
        executionRoot = executionRoot == null ? null : executionRoot.toAbsolutePath().normalize();
        protectedPaths =
                protectedPaths.stream()
                        .map(path -> path.toAbsolutePath().normalize())
                        .collect(Collectors.toUnmodifiableSet());
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
        Path executionRoot = workspace.currentHostRoot();
        Set<Path> denied =
                deployerPolicy == DeployerPolicy.FULL_ACCESS ? Set.of() : protectedSet.paths();
        if (hints.isEmpty()) {
            return new ToolExecutionPermit(
                    call.toolName(),
                    call.callId(),
                    definition.capability(),
                    null,
                    call.args(),
                    Map.of(),
                    roots,
                    executionRoot,
                    deployerPolicy,
                    denied,
                    null);
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
            Path hostPath = resolution.hostPath();
            Path parentPath = hostPath == null ? null : hostPath.getParent();
            paths.put(
                    entry.getKey(),
                    new AuthorizedPath(
                            entry.getKey(),
                            requestedPath,
                            hostPath,
                            resolution.rootIndex(),
                            resolution.inScope(),
                            FileIdentity.capture(hostPath),
                            parentPath == null
                                    ? FileIdentity.missing()
                                    : FileIdentity.capture(parentPath)));
        }
        return new ToolExecutionPermit(
                call.toolName(),
                call.callId(),
                definition.capability(),
                null,
                call.args(),
                paths,
                roots,
                executionRoot,
                deployerPolicy,
                denied,
                null);
    }

    /** Binds a screened process-input call to the exact background-task instance it targeted. */
    public @NonNull ToolExecutionPermit withTaskBinding(@NonNull TaskBinding binding) {
        return new ToolExecutionPermit(
                toolName,
                callId,
                capability,
                caller,
                screenedArguments,
                filesystemPaths,
                workspaceRoots,
                executionRoot,
                deployerPolicy,
                protectedPaths,
                binding);
    }

    /** Binds authorization to the runtime caller immediately before dispatch. */
    public @NonNull ToolExecutionPermit withCaller(
            @NonNull String agentId,
            @NonNull UUID userId,
            UUID groupId,
            String owner,
            UUID sessionId) {
        return new ToolExecutionPermit(
                toolName,
                callId,
                capability,
                new CallerBinding(agentId, userId, groupId, owner, sessionId),
                screenedArguments,
                filesystemPaths,
                workspaceRoots,
                executionRoot,
                deployerPolicy,
                protectedPaths,
                taskBinding);
    }

    public boolean authorizes(
            @NonNull ToolCall call,
            @NonNull ToolDefinition definition,
            @NonNull ToolCallContext context) {
        return matchesCall(call)
                && capability == definition.capability()
                && caller != null
                && caller.agentId().equals(context.agentId())
                && caller.userId().equals(context.userId())
                && Objects.equals(caller.groupId(), context.groupId())
                && Objects.equals(caller.owner(), context.owner())
                && Objects.equals(caller.sessionId(), context.sessionId());
    }

    public record CallerBinding(
            @NonNull String agentId,
            @NonNull UUID userId,
            UUID groupId,
            String owner,
            UUID sessionId) {}

    /** Whether this permit still binds the exact immutable tool call. */
    public boolean matchesCall(@NonNull ToolCall call) {
        return toolName.equals(call.toolName())
                && callId.equals(call.callId())
                && screenedArguments.equals(call.args());
    }

    /** Whether this permit and a fresh capture still bind the same call and resources. */
    public boolean sameTargets(@NonNull ToolExecutionPermit current) {
        if (!callId.equals(current.callId)
                || capability != current.capability
                || !toolName.equals(current.toolName)
                || !screenedArguments.equals(current.screenedArguments)) {
            return false;
        }
        if (!filesystemPaths.keySet().equals(current.filesystemPaths.keySet())) {
            return false;
        }
        if (!workspaceRoots.equals(current.workspaceRoots)
                || !Objects.equals(executionRoot, current.executionRoot)
                || deployerPolicy != current.deployerPolicy
                || !protectedPaths.equals(current.protectedPaths)
                || !Objects.equals(taskBinding, current.taskBinding)) {
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

    /** Returns the session-selected execution root captured during Gateway screening. */
    public @NonNull Path requireExecutionRoot() {
        if (executionRoot == null) {
            throw new SecurityException("Missing screened workspace execution root");
        }
        return executionRoot;
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
        if (Files.isDirectory(host)) {
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
            boolean inScope,
            @NonNull FileIdentity identity,
            @NonNull FileIdentity parentIdentity) {

        boolean sameTarget(@NonNull AuthorizedPath current) {
            return argumentName.equals(current.argumentName)
                    && requestedPath.equals(current.requestedPath)
                    && Objects.equals(hostPath, current.hostPath)
                    && rootIndex == current.rootIndex
                    && inScope == current.inScope
                    && identity.sameObject(current.identity)
                    && parentIdentity.sameObject(current.parentIdentity);
        }
    }

    /** Best-effort no-follow identity used to detect path replacement after screening. */
    public record FileIdentity(
            @NonNull State state,
            @NonNull String fileKey,
            long size,
            long lastModifiedMillis,
            boolean directory,
            boolean symbolicLink) {

        /** Compares object identity, using provider file keys when available. */
        public boolean sameObject(@NonNull FileIdentity current) {
            if (state != current.state
                    || directory != current.directory
                    || symbolicLink != current.symbolicLink) {
                return false;
            }
            if (state != State.PRESENT) {
                return state == State.MISSING;
            }
            if (!fileKey.isEmpty() || !current.fileKey.isEmpty()) {
                return fileKey.equals(current.fileKey);
            }
            return directory
                    || (size == current.size && lastModifiedMillis == current.lastModifiedMillis);
        }

        public static @NonNull FileIdentity capture(Path path) {
            if (path == null) {
                return unavailable();
            }
            try {
                BasicFileAttributes attributes =
                        Files.readAttributes(
                                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return new FileIdentity(
                        State.PRESENT,
                        Objects.toString(attributes.fileKey(), ""),
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis(),
                        attributes.isDirectory(),
                        attributes.isSymbolicLink());
            } catch (NoSuchFileException e) {
                return missing();
            } catch (IOException | SecurityException e) {
                return unavailable();
            }
        }

        public static @NonNull FileIdentity missing() {
            return new FileIdentity(State.MISSING, "", 0, 0, false, false);
        }

        public static @NonNull FileIdentity unavailable() {
            return new FileIdentity(State.UNAVAILABLE, "", 0, 0, false, false);
        }

        public enum State {
            PRESENT,
            MISSING,
            UNAVAILABLE
        }
    }

    /** Exact process instance approved for one process-input call. */
    public record TaskBinding(
            @NonNull String taskId,
            @NonNull String agentId,
            @NonNull UUID sessionId,
            @NonNull UUID taskInstanceId) {}
}
