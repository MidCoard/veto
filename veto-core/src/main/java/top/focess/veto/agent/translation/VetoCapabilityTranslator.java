package top.focess.veto.agent.translation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import top.focess.veto.agent.tool.ControlSubmissions;
import top.focess.veto.agent.tool.LocalToolDefinition;
import top.focess.veto.agent.tool.ToolDefinition;
import top.focess.veto.api.agent.tool.ContextualInputSchemaSource;
import top.focess.veto.api.agent.tool.ControlSubmission;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.api.agent.tool.ToolInputSchema;

/** Produces native tool definitions from the runtime capability manifest. */
@Service
public class VetoCapabilityTranslator implements CapabilityTranslator {

    private static final @NonNull ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public @NonNull List<top.focess.veto.api.llm.ToolDefinition> translateTools(
            List<ToolDefinition> manifest) {
        List<top.focess.veto.api.llm.ToolDefinition> flat = new ArrayList<>();
        var submissions = new HashMap<String, ControlSubmission.Kind>();
        var javaRecordTools = new HashSet<String>();
        if (manifest == null) return flat;
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
            var kind = ControlSubmissions.kindOf(def);
            if (kind != null) submissions.put(def.name(), kind);
            if (def instanceof LocalToolDefinition) javaRecordTools.add(def.name());
        }
        var context = new ContextualInputSchemaSource.Context(flat, javaRecordTools, submissions);
        for (int i = 0; i < manifest.size(); i++) {
            if (!(manifest.get(i) instanceof LocalToolDefinition local)) continue;
            var annotation =
                    local.argsClass().getAnnotation(ToolDocs.nonNullClass(ToolInputSchema.class));
            if (annotation == null) continue;
            ContextualInputSchemaSource schemaSource;
            try {
                var source = annotation.value().getDeclaredConstructor().newInstance();
                if (!(source instanceof ContextualInputSchemaSource contextual)) continue;
                schemaSource = contextual;
            } catch (ReflectiveOperationException error) {
                throw new IllegalArgumentException(
                        "Cannot construct contextual tool schema", error);
            }
            var plan = flat.get(i);
            flat.set(
                    i,
                    new top.focess.veto.api.llm.ToolDefinition(
                            plan.name(),
                            plan.description(),
                            MAPPER.convertValue(
                                    schemaSource.schema(context),
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
