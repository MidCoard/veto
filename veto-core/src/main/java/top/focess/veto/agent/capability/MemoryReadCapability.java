package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.memory.MemoryTools.RecallMemory;

public sealed interface MemoryReadCapability extends Capability permits MemoryReadCapabilityImpl {
    @NonNull String recall(RecallMemory.@NonNull Args args);
}
