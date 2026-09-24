package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.tool.ParamCategory;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolDocumentation;
import top.focess.veto.api.agent.tool.ToolPresentation;
import top.focess.veto.api.agent.tool.ToolResultFormat;

/** Tool-class documentation and independent argument schemas for local tools. */
public sealed interface LocalToolDefinition extends ToolDefinition
        permits NativeToolDefinition, AgentToolDefinition {
    default ToolPresentation presentation() {
        return null;
    }

    @NonNull Class<?> argsClass();

    @NonNull Class<?> toolClass();

    @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints();

    @Override
    default @NonNull JsonNode inputSchema() {
        return argsClass().isRecord()
                ? ToolSchemaCompiler.compileFromRecord(argsClass())
                : ToolSchemaCompiler.emptyObjectSchema();
    }

    @Override
    default @NonNull List<@NonNull String> examples() {
        return ToolDocs.examplesOf(toolClass());
    }

    @Override
    default @NonNull List<@NonNull String> returnExamples() {
        return ToolDocs.returnExamplesOf(toolClass());
    }

    @Override
    default @NonNull List<@NonNull ToolResultFormat> resultFormats() {
        return ToolDocs.resultFormatsOf(toolClass());
    }

    @Override
    default @NonNull ToolDocumentation documentation() {
        return ToolDocs.documentationOf(toolClass());
    }
}
