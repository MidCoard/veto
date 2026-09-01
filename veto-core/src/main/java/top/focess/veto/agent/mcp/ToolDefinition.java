package top.focess.veto.agent.mcp;

import java.util.List;
import org.jspecify.annotations.NonNull;

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

    @NonNull String name();

    @NonNull String description();

    @NonNull RiskCategory risk();

    /** The effect boundary this tool executes through. Independent from flavour and risk. */
    @NonNull ToolCapability capability();

    @NonNull ParameterSchema parameters();

    /** Successful result content encodings. Failure status is carried separately. */
    default @NonNull List<@NonNull ToolResultFormat> resultFormats() {
        return List.of(ToolResultFormat.JSON, ToolResultFormat.PLAINTEXT);
    }

    /**
     * Concrete usage examples (args-object strings) reflected from a {@link ToolDoc} on the tool's
     * args record; rendered under the tool entry by the prompt compiler. Empty by default.
     */
    default @NonNull List<@NonNull String> examples() {
        return List.of();
    }

    /**
     * Successful return-value examples reflected from a {@link ToolDoc} on the tool's args record.
     * They are independent of {@link #examples()} and never represent the failure channel. Empty by
     * default.
     */
    default @NonNull List<@NonNull String> returnExamples() {
        return List.of();
    }

    /**
     * Long-form, LLM-facing usage doc reflected from a {@link ToolDoc} on the tool's args record;
     * rendered verbatim as the body of the tool's catalog entry by the prompt compiler. Empty by
     * default (the short {@link #description()} is rendered alone).
     */
    default @NonNull String longDescription() {
        return "";
    }
}
