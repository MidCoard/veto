package example;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginContributions;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;
import top.focess.veto.api.plugin.service.PluginServices;
import top.focess.veto.api.plugin.service.ServiceException;

/** Compilation fixture for the consumer documented in veto-api/README.md. */
public final class TextConsumerPlugin extends VetoPlugin {
    private final @NonNull PluginContext context;
    private Optional<PluginServices.Handle> textService = Optional.empty();

    public TextConsumerPlugin(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration) {
        this.context = context;
    }

    public @NonNull PluginIdentity identity() {
        return new PluginIdentity("example.consumer", "1.0.0");
    }

    public @NonNull PluginContributions contributions() {
        return new PluginContributions(List.of());
    }

    public void start() throws PluginFailure {
        textService = context.services().find("example:text", 1);
    }

    public JsonValue normalize(@NonNull String input) throws ServiceException {
        if (textService.isEmpty()) return new JsonValue.StringValue(input);
        return textService.orElseThrow().invoke(new JsonValue.StringValue(input));
    }

    public void close() throws PluginFailure {
        textService = Optional.empty();
    }
}
