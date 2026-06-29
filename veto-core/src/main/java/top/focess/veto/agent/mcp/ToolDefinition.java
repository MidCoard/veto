package top.focess.veto.agent.mcp;

import org.jetbrains.annotations.NotNull;

/**
 * Unified tool definition — the capability manifest element. Every tool exposes its name,
 * description, risk category, and parameter schema through this single contract. The Gateway reads
 * {@link #risk} and {@link #parameters} to decide how to screen each call, without hard-coding
 * per-tool-name logic..
 *
 * <p>Three flavours:
 *
 * <ul>
 *   <li>{@link NativeToolDefinition} — a shipped tool backed by a Java record.
 *   <li>{@link RemoteToolDefinition} — an external MCP tool with raw JSON Schema.
 *   <li>{@link AgentToolDefinition} — an engine-provided control/meta tool used directly inside the
 *       agent loop or workflows ({@code create_group}, {@code load_skill}).
 * </ul>
 *
 * <p><b>Note:</b> It also lists static {@code of(...)} factory methods that delegate to {@code
 * ToolSchemaCompiler} (a utility). Those factories are intentionally omitted from this shared
 * interface — they are convenience builders, not part of the read surface both parts compile
 * against. {@code ToolSchemaCompiler} builds {@link NativeToolDefinition} instances directly;
 * callers construct {@link RemoteToolDefinition}/{@link AgentToolDefinition} via their canonical
 * constructors. This interface is shared/read-only: do not add fields or factories here without
 * coordinator approval.
 */
public sealed interface ToolDefinition
        permits NativeToolDefinition, RemoteToolDefinition, AgentToolDefinition {

    @NotNull
    String name();

    @NotNull
    String description();

    @NotNull
    RiskCategory risk();

    @NotNull
    ParameterSchema parameters();
}
