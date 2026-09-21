package top.focess.veto.plugin.api;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contribution.Contribution;

/** Plugin packaging around the same typed contributions accepted from built-in modules. */
public record PluginContributions(@NonNull List<@NonNull Contribution<?>> entries) {
    public PluginContributions {
        entries = List.copyOf(entries);
        if (entries.size() > 256) throw new IllegalArgumentException("Contribution limit exceeded");
    }
}
