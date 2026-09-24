package top.focess.veto.agent;

import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.AgentAction;

/** The completion owner travels with the work, never in a global handoff slot. */
record QueuedRequest(@NonNull AgentAction action, @NonNull RequestHandle handle) {}
