package top.focess.veto.builtin.group;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.builtin.group.BlackboardMessage.MessageType;

/**
 * Host-side operations behind the Leader's group tools: membership, plan, messaging, and lifecycle
 * of the single group the calling agent leads.
 */
public interface GroupControlCapability {
    /** Renders the named group prompt template with the given data. */
    @NonNull String prompt(@NonNull String source, @NonNull Map<String, Object> data);

    /** Current group snapshot, or {@code null} when the caller leads no active group. */
    GroupSnapshot snapshot();

    /** Creates an idle collaborator and returns its new mate id. */
    @NonNull String createMate(@NonNull String name, @NonNull String responsibility);

    /** Registers a task node assigned to an existing mate, honoring its dependencies. */
    @NonNull NodeEdit createTask(
            @NonNull String id,
            @NonNull String description,
            @NonNull String mateId,
            @NonNull String responsibility,
            @NonNull Set<String> dependencies);

    /** Group snapshot plus blackboard messages posted since the given sequence number. */
    Inspection inspect(long since);

    /** Blackboard messages posted since the given sequence number. */
    @NonNull List<@NonNull BlackboardMessage> messages(long since);

    /** Blocks until the group changes or leaves the given state, bounded by {@code waitSeconds}. */
    void awaitChange(long since, @NonNull GroupState state, int waitSeconds)
            throws InterruptedException;

    /** Disbands the group, retaining its history for audit. */
    void disband(@NonNull String brief);

    /** Posts a blackboard message to the given receiver. */
    void post(@NonNull String receiver, @NonNull MessageType type, @NonNull String payload);

    /**
     * Adds a plan node; {@code mateId} pins an existing mate and {@code newMate} forces a new one.
     */
    @NonNull NodeEdit addNode(
            @NonNull String id,
            @NonNull String description,
            @NonNull String skillset,
            @NonNull Set<String> dependencies,
            String mateId,
            boolean newMate);

    /** Retires the plan node with the given id. */
    @NonNull NodeEdit removeNode(@NonNull String id);

    /** Cancels the task with the given id and confirms execution exit. */
    @NonNull NodeEdit cancelTask(@NonNull String id);

    /** Removes the idle mate with the given id after confirming execution exit. */
    @NonNull NodeEdit removeMate(@NonNull String id);
}
