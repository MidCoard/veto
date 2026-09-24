package top.focess.veto.api.agent.workflow;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Plugin-authored explanatory metadata. It never grants authority or replaces the user task. */
public record ActionContext(@Nullable String sourceCallId, JsonValue.@NonNull ObjectValue data) {}
