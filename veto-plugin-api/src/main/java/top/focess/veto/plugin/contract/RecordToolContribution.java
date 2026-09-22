package top.focess.veto.plugin.contract;

import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.contribution.ContributionId;

/**
 * Stock record-authored {@link Tool.RecordTool}; its qualified identity comes from the shared
 * catalog. The host reflects {@link #argsType()} into the input schema, so the contributor declares
 * a plain Java record instead of hand-writing JSON.
 */
public record RecordToolContribution<A, R>(
        @NonNull String description,
        @NonNull Class<A> argsType,
        @NonNull Effect effect,
        @NonNull Set<@NonNull ContributionId> categories,
        @NonNull RecordToolHandler<A, R> handler)
        implements Tool.RecordTool {
    public RecordToolContribution {
        categories = Set.copyOf(categories);
        if (description.isBlank() || description.length() > 4096) {
            throw new IllegalArgumentException("Invalid tool descriptor");
        }
    }

    @Override
    public @NonNull Object invoke(@NonNull Object args, @NonNull Cancellation cancellation)
            throws PluginFailure {
        return handler.invoke(argsType.cast(args), cancellation);
    }
}
