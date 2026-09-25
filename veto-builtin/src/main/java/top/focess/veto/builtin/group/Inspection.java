package top.focess.veto.builtin.group;

import java.util.*;
import org.jspecify.annotations.NonNull;

/** Read-only view of a group: its snapshot together with the recent blackboard messages. */
public record Inspection(
        @NonNull GroupSnapshot group, @NonNull List<@NonNull BlackboardMessage> messages) {}
