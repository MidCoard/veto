package top.focess.veto.agent.translation;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.tool.ToolDefinition;

/** Translates the tool manifest into provider-native tool definitions. */
public interface CapabilityTranslator {

    /**
     * Translates the whitelisted manifest tools into the flat, provider-facing {@link
     * top.focess.veto.api.llm.ToolDefinition} list carried by {@code VetoRequest.tools}.
     */
    @NonNull List<top.focess.veto.api.llm.ToolDefinition> translateTools(
            List<ToolDefinition> manifest);
}
