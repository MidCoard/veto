package top.focess.veto.agent.tool;

import org.jspecify.annotations.NonNull;

/**
 * Where a tool definition came from, when it was contributed by an installed plugin rather than
 * shipped in the host. Provenance is data on an ordinary definition, orthogonal to how the tool
 * executes: a plugin-contributed tool is still a {@link NativeToolDefinition} (in-process JAR) or a
 * {@link RemoteToolDefinition} (out-of-process script). A non-null provenance marks the tool as
 * session-scoped and revision-pinned — a session only sees the plugins bound at its creation, and
 * an execution permit binds the exact plugin revision.
 */
public record Provenance(
        @NonNull String pluginId, @NonNull String bindingId, @NonNull String pluginVersion) {}
