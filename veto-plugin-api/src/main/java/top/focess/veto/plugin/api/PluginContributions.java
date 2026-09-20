package top.focess.veto.plugin.api;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.extension.ExtensionContribution;

/** Plugin packaging around the same typed contributions accepted from built-in modules. */
public record PluginContributions(@NonNull List<@NonNull ExtensionContribution<?>> entries) {
    public PluginContributions {
        entries = List.copyOf(entries);
        if (entries.size() > 256) throw new IllegalArgumentException("Contribution limit exceeded");
    }
}
