package top.focess.veto.agent.tool.builtin;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.agent.capability.WorkspaceFile;

/** Bounded tool-side traversal over already restricted resource handles. */
final class WorkspaceTraversal {
    private static final long MAX_NANOS = 10_000_000_000L;
    private final @NonNull ArrayDeque<@NonNull Iterator<? extends @NonNull WorkspaceFile>> pending =
            new ArrayDeque<>();
    private final @NonNull ArrayDeque<@NonNull String> prefixes = new ArrayDeque<>();
    private final @NonNull WorkspaceFile root;
    private @NonNull String relativeName = "";
    private final long started = System.nanoTime();
    private int visited;
    private int skipped;
    private String reason;

    WorkspaceTraversal(@NonNull WorkspaceFile root) {
        this.root = root;
        pending.push(List.of(root).iterator());
        prefixes.push("");
    }

    WorkspaceFile next() throws IOException {
        while (!pending.isEmpty() && withinTime()) {
            var iterator = pending.peek();
            if (iterator == null) return null;
            if (!iterator.hasNext()) {
                pending.pop();
                prefixes.pop();
                continue;
            }
            if (++visited > 50_000) {
                reason = "VISIT_LIMIT";
                return null;
            }
            WorkspaceFile file = iterator.next();
            String prefix = prefixes.peek();
            if (prefix == null) throw new IllegalStateException("Missing traversal prefix");
            String relative = file == root ? "" : prefix + file.name();
            String kind;
            try {
                kind = file.kind();
                if (kind.equals("directory")) {
                    pending.push(file.children().iterator());
                    prefixes.push(relative.isEmpty() ? "" : relative + "/");
                }
            } catch (IOException e) {
                if (visited == 1) throw e;
                skipped++;
                continue;
            }
            if (kind.equals("file")) {
                relativeName = relative.isEmpty() ? file.name() : relative;
                return file;
            }
            if (!kind.equals("directory")) skipped++;
        }
        return null;
    }

    boolean withinTime() {
        if (System.nanoTime() - started > MAX_NANOS) {
            reason = "TIME_LIMIT";
            return false;
        }
        return true;
    }

    String reason() {
        return reason;
    }

    int skipped() {
        return skipped;
    }

    static @NonNull String basename(@NonNull String name) {
        String portable = name.replace('\\', '/');
        return portable.substring(portable.lastIndexOf('/') + 1);
    }

    @NonNull String relativeName() {
        return relativeName;
    }
}
