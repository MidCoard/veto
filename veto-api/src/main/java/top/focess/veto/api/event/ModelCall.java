package top.focess.veto.api.event;

import org.jspecify.annotations.NonNull;

/**
 * Selected model endpoint observed by the model-phase events.
 *
 * @param provider selected provider identity
 * @param model selected model identity
 */
public record ModelCall(@NonNull String provider, @NonNull String model) {}
