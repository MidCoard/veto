package top.focess.veto.agent.capability;

/** A call-scoped authority issued from the Gateway-approved execution permit. */
public sealed interface Capability permits WorkspaceReadCapability, WorkspaceWriteCapability {}
