package top.focess.veto.agent.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.tool.LocalToolDefinition;
import top.focess.veto.agent.tool.ResponseSubmission;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.agent.tool.builtin.PlanProgramSchema;

/** Produces native tool definitions from the runtime capability manifest. */
@Service
public class VetoCapabilityTranslator implements CapabilityTranslator {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public @NonNull List<top.focess.veto.api.llm.ToolDefinition> translateTools(
            List<ToolDefinition> manifest) {
        List<top.focess.veto.api.llm.ToolDefinition> flat = new ArrayList<>();
        List<top.focess.veto.api.llm.ToolDefinition> planTools = new ArrayList<>();
        var javaRecordTools = new HashSet<String>();
        if (manifest == null) return flat;
        boolean citationsAvailable =
                manifest.stream()
                        .anyMatch(
                                def ->
                                        ResponseSubmission.Metadata.kindOf(def)
                                                == ResponseSubmission.Kind.ANSWER);
        for (ToolDefinition def : manifest) {
            Map<String, Object> inputSchema = inputSchemaOf(def);
            var translated =
                    new top.focess.veto.api.llm.ToolDefinition(
                            def.name(),
                            def.description(),
                            inputSchema,
                            def.examples(),
                            def.documentation(),
                            def.returnExamples(),
                            def.resultFormats());
            flat.add(translated);
            if (ResponseSubmission.Metadata.kindOf(def) == null) planTools.add(translated);
            if (def instanceof LocalToolDefinition) javaRecordTools.add(def.name());
        }
        // Plan steps execute only capabilities in this request's manifest. Binding here keeps
        // the native schema and the prompt catalogue on the same session-specific contract.
        for (int i = 0; i < manifest.size(); i++) {
            if (ResponseSubmission.Metadata.kindOf(manifest.get(i)) != ResponseSubmission.Kind.PLAN)
                continue;
            var plan = flat.get(i);
            flat.set(
                    i,
                    new top.focess.veto.api.llm.ToolDefinition(
                            plan.name(),
                            plan.description(),
                            MAPPER.convertValue(
                                    PlanProgramSchema.create(
                                            planTools, javaRecordTools, citationsAvailable),
                                    new TypeReference<Map<String, Object>>() {}),
                            plan.examples(),
                            plan.documentation(),
                            plan.returnExamples(),
                            plan.resultFormats()));
        }
        flat.sort(Comparator.comparing(top.focess.veto.api.llm.ToolDefinition::name));
        return flat;
    }

    /** Resolves a manifest tool's inputSchema to a flat {@code Map} for the provider tool list. */
    private @NonNull Map<String, Object> inputSchemaOf(@NonNull ToolDefinition def) {
        JsonNode schema = def.inputSchema();
        return MAPPER.convertValue(schema, new TypeReference<Map<String, Object>>() {});
    }
}
