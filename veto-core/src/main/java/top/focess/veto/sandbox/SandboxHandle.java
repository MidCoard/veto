package top.focess.veto.sandbox;

import java.nio.file.Path;
import org.jspecify.annotations.NonNull;

/**
 * An in-memory handle to a provisioned sandbox session. Held by {@link SandboxManager} keyed by
 * {@code agentId} — never persisted on {@code AgentEntity} (runtime resources are volatile)..
 *
 * @param sessionId the agent/session this sandbox belongs to
 * @param runtimeRef an opaque reference to the runtime resource (process/container/vm), substrate
 * @param workspaceRoot the canonical workspace root all relative paths resolve under
 */
public record SandboxHandle(
        @NonNull String sessionId, @NonNull Object runtimeRef, @NonNull Path workspaceRoot) {}
