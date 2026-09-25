package top.focess.veto.api.agent;

import org.jspecify.annotations.NonNull;

/**
 * Portable event emitted when tool execution completes.
 *
 * @param body model-visible result body
 * @param success whether execution succeeded
 */
public record ToolResultEvent(@NonNull String body, boolean success) {}
