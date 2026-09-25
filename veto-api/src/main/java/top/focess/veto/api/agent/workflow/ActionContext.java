package top.focess.veto.api.agent.workflow;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.contract.JsonValue;

/**
 * Plugin-authored explanatory metadata passed to a workflow tool invocation. It never grants
 * authority or replaces the user task.
 *
 * @param sourceCallId originating model call identifier, or {@code null} when none exists
 * @param data immutable workflow-owned structured context
 */
public record ActionContext(String sourceCallId, JsonValue.@NonNull ObjectValue data) {}
