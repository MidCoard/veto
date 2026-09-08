package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.screening.Danger;

/**
 * Unified tool definition — the capability manifest element. Every tool exposes its name,
 * description, capability, default danger, and parameter schema through this single contract. The
 * Gateway reads these properties to select the security boundary and screen each call without
 * hard-coding per-tool-name logic.
 *
 * <p>Three flavours:
 *
 * <ul>
 *   <li>{@link NativeToolDefinition} — a shipped tool backed by a Java record.
 *   <li>{@link RemoteToolDefinition} — an external MCP tool with raw JSON Schema.
 *   <li>{@link AgentToolDefinition} — an engine-provided control/meta tool used directly inside the
 *       agent loop or workflows ({@code create_group}, {@code load_skill}).
 * </ul>
 */
public sealed interface ToolDefinition permits LocalToolDefinition, RemoteToolDefinition {

    @NonNull String name();

    @NonNull String description();

    /** The effect boundary this tool executes through. */
    @NonNull ToolCapability capability();

    /** The deterministic danger assigned before argument-, policy-, and model-aware escalation. */
    @NonNull Danger defaultDanger();

    @NonNull JsonNode inputSchema();

    /** Successful result content encodings. Failure status is carried separately. */
    default @NonNull List<@NonNull ToolResultFormat> resultFormats() {
        return List.of(ToolResultFormat.JSON, ToolResultFormat.PLAINTEXT);
    }

    /**
     * Concrete usage examples (args-object strings) reflected from a {@link ToolDoc} on the tool's
     * implementation class; rendered under the tool entry by the prompt compiler. Empty by default.
     */
    default @NonNull List<@NonNull String> examples() {
        return List.of();
    }

    /**
     * Successful return-value examples reflected from a {@link ToolDoc} on the tool class. They are
     * independent of {@link #examples()} and never represent the failure channel. Empty by default.
     */
    default @NonNull List<@NonNull String> returnExamples() {
        return List.of();
    }

    /** Typed LLM-facing documentation sections. */
    default @NonNull ToolDocumentation documentation() {
        return ToolDocumentation.empty();
    }
}
