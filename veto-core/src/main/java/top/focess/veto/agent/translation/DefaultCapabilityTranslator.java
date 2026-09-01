package top.focess.veto.agent.translation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.mcp.ToolDefinition;

/**
 * Fallback {@link CapabilityTranslator} used when the Spring context has no richer translator bean.
 * It delegates to the same canonical implementation as production so tests and reduced application
 * contexts cannot silently advertise a weaker tool-call schema.
 */
public class DefaultCapabilityTranslator implements CapabilityTranslator {

    private final @NonNull VetoCapabilityTranslator delegate = new VetoCapabilityTranslator();

    public DefaultCapabilityTranslator(@NonNull ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public @NonNull JsonNode vetoResponseSchema(boolean guidedSwitch) {
        return delegate.vetoResponseSchema(guidedSwitch);
    }

    @Override
    public @NonNull JsonNode vetoResponseSchema(
            boolean guidedSwitch, @NonNull List<top.focess.veto.llm.core.ToolDefinition> tools) {
        return delegate.vetoResponseSchema(guidedSwitch, tools);
    }

    @Override
    public @NonNull List<top.focess.veto.llm.core.ToolDefinition> translateTools(
            List<ToolDefinition> manifest) {
        return delegate.translateTools(manifest);
    }
}
