package top.focess.veto.agent.capability;

import org.jspecify.annotations.NonNull;
import top.focess.veto.memory.MemoryTools.ForgetMemory;
import top.focess.veto.memory.MemoryTools.WriteMemory;

public sealed interface MemoryWriteCapability extends Capability permits MemoryWriteCapabilityImpl {
    @NonNull String write(WriteMemory.@NonNull Args args);

    @NonNull String forget(ForgetMemory.@NonNull Args args);
}
