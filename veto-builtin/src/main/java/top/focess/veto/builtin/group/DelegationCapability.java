package top.focess.veto.builtin.group;

import org.jspecify.annotations.NonNull;

/** Host capability that lets an agent delegate work by spawning a new collaboration group. */
public interface DelegationCapability {
    /** Spawns a group to carry out the given task description. */
    void createGroup(@NonNull String task);
}
