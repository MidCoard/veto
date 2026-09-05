package top.focess.veto.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

/** Shared schema and documentation derived from local tool arguments. */
public sealed interface LocalToolDefinition extends ToolDefinition
        permits NativeToolDefinition, AgentToolDefinition {
    @NonNull Class<?> argsClass();

    @NonNull Map<@NonNull String, @NonNull ParamCategory> paramHints();

    @Override
    default @NonNull JsonNode inputSchema() {
        return argsClass().isRecord()
                ? ToolSchemaCompiler.compileFromRecord(argsClass())
                : ToolSchemaCompiler.emptyObjectSchema();
    }

    @Override
    default @NonNull List<@NonNull String> examples() {
        return ToolDocs.examplesOf(argsClass());
    }

    @Override
    default @NonNull List<@NonNull String> returnExamples() {
        return ToolDocs.returnExamplesOf(argsClass());
    }

    @Override
    default @NonNull List<@NonNull ToolResultFormat> resultFormats() {
        return ToolDocs.resultFormatsOf(argsClass());
    }

    @Override
    default @NonNull ToolDocumentation documentation() {
        return ToolDocs.documentationOf(argsClass());
    }
}
