package top.focess.veto.agent.screening;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.screening.Danger;

/** Advisory semantic judgment produced by the local screening model. */
public record SlmScreening(
        @NonNull Relevance relevance, @NonNull Danger danger, @NonNull String reason) {}
