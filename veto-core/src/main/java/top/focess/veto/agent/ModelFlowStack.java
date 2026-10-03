package top.focess.veto.agent;

import java.util.ArrayDeque;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.workflow.ModelFlow;
import top.focess.veto.plugin.runtime.ManagedPluginFlow;

/** Runner-thread-owned flow selection; an empty stack selects the core default flow. */
final class ModelFlowStack {
    private static final int MAX_DEPTH = 8;
    private final @NonNull ArrayDeque<@NonNull ModelFlow> frames = new ArrayDeque<>();

    @NonNull ModelFlow top() {
        ModelFlow top = frames.peek();
        if (top == null) throw new IllegalStateException("Core default flow is selected");
        return top;
    }

    boolean defaultSelected() {
        return frames.isEmpty();
    }

    void push(@NonNull ModelFlow flow) {
        if (frames.size() >= MAX_DEPTH)
            throw new IllegalStateException("Model flow stack depth exceeded");
        frames.push(flow);
    }

    void pushNested(@NonNull ModelFlow parent, @NonNull ModelFlow child) {
        requireTop(parent);
        push(parent instanceof ManagedPluginFlow owned ? owned.child(child) : child);
    }

    void pop(@NonNull ModelFlow flow) {
        requireTop(flow);
        frames.pop();
    }

    void discardFailed(@NonNull ModelFlow flow) {
        if (frames.stream().noneMatch(frame -> frame == flow)) return;
        ModelFlow removed;
        do {
            removed = frames.pop();
        } while (removed != flow);
    }

    private void requireTop(@NonNull ModelFlow flow) {
        if (frames.peek() != flow)
            throw new IllegalStateException("Only the selected top flow may change the stack");
    }
}
