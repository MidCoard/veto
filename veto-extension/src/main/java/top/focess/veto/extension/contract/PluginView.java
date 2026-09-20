package top.focess.veto.extension.contract;

import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Map;

/** Host-neutral, declarative UI primitives. No plugin-specific presentation types. */
public record PluginView(@NonNull List<Node> content, int resetAfterMillis, boolean resetOnHidden) {
    public PluginView {
        content = List.copyOf(content);
        if (resetAfterMillis < 0 || resetAfterMillis > 86400000)
            throw new IllegalArgumentException("Invalid reset interval");
        int count = 0;
        for (var node : content) count += validate(node, 0);
        if (count > 128) throw new IllegalArgumentException("View too large");
    }

    private static int validate(@NonNull Node node, int depth) {
        if (depth > 8) throw new IllegalArgumentException("View too deep");
        int count = 1;
        if (node instanceof Group group)
            for (var child : group.children()) count += validate(child, depth + 1);
        return count;
    }

    public sealed interface Node permits Text, Button, Group {}

    public record Label(@NonNull String fallback, @NonNull Map<String, String> translations) {
        public Label {
            translations = Map.copyOf(translations);
        }

        public static @NonNull Label literal(@NonNull String text) {
            return new Label(text, Map.of());
        }
    }

    public record Text(@NonNull String type, @NonNull Label text) implements Node {
        public Text(@NonNull Label text) {
            this("text", text);
        }

        public Text {
            if (!type.equals("text")) throw new IllegalArgumentException("Invalid node type");
        }
    }

    public enum ActionTarget {
        SERVER,
        RESET
    }

    public record Action(@NonNull ActionTarget target, @NonNull String id) {
        public Action {
            if (!id.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}"))
                throw new IllegalArgumentException("Invalid action id");
        }
    }

    public record Button(@NonNull String type, @NonNull Label label, @NonNull Action action)
            implements Node {
        public Button(@NonNull Label label, @NonNull Action action) {
            this("button", label, action);
        }

        public Button {
            if (!type.equals("button")) throw new IllegalArgumentException("Invalid node type");
        }
    }

    public enum Direction {
        ROW,
        COLUMN
    }

    public record Group(
            @NonNull String type, @NonNull Direction direction, @NonNull List<Node> children)
            implements Node {
        public Group(@NonNull Direction direction, @NonNull List<Node> children) {
            this("group", direction, children);
        }

        public Group {
            if (!type.equals("group")) throw new IllegalArgumentException("Invalid node type");
            children = List.copyOf(children);
        }
    }
}
