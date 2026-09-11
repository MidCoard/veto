package top.focess.veto.agent.capability;

/** Restricted operations authorized for the current tool call. */
public sealed interface Capability
        permits WorkspaceReadCapability,
                WorkspaceWriteCapability,
                ProcessExecutionCapability,
                TaskControlCapability,
                NetworkEgressCapability,
                CredentialImportCapability,
                WebReadCapability,
                WebDocumentCapability,
                MemoryReadCapability,
                MemoryWriteCapability,
                DelegationCapability,
                GroupControlCapability,
                MonitorCapability,
                LoopControlCapability,
                SkillReadCapability,
                UserInteractionCapability,
                RemoteCallCapability {}
