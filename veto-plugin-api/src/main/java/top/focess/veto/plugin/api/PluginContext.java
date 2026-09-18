package top.focess.veto.plugin.api;

import org.jspecify.annotations.NonNull;

/** Plugin instance metadata only. Typed host services belong to separate invocation contracts. */
public record PluginContext(@NonNull PluginIdentity identity) {}
