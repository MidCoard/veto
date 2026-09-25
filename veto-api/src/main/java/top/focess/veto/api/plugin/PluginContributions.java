package top.focess.veto.api.plugin;

import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contribution.Contribution;

/**
 * Immutable startup batch of implementations offered to the host.
 *
 * <p>The host validates the whole batch before publication. Supplying a contribution neither
 * selects it for a session nor grants its implementation a host service or invocation permit.
 */
public record PluginContributions(@NonNull List<@NonNull Contribution<?>> entries) {
    public PluginContributions {
        entries = List.copyOf(entries);
        if (entries.size() > 256) throw new IllegalArgumentException("Contribution limit exceeded");
    }
}
