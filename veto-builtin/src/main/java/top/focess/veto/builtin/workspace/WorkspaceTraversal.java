package top.focess.veto.builtin.workspace;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.WorkspaceFile;

/** Bounded tool-side traversal over already restricted resource handles. */
final class WorkspaceTraversal {
    private static final long MAX_NANOS = 10_000_000_000L;
    private final @NonNull ArrayDeque<@NonNull DirectoryCursor> pending = new ArrayDeque<>();
    private final @NonNull WorkspaceFile root;
    private @NonNull String relativeName = "";
    private final long started = System.nanoTime();
    private int visited;
    private int skipped;
    private String reason;

    WorkspaceTraversal(@NonNull WorkspaceFile root) {
        this.root = root;
        pending.push(new DirectoryCursor(List.of(root).iterator(), ""));
    }

    WorkspaceFile next() throws IOException {
        while (!pending.isEmpty() && withinTime()) {
            var directory = pending.peek();
            if (directory == null) return null;
            if (!directory.files().hasNext()) {
                pending.pop();
                continue;
            }
            if (++visited > 50_000) {
                reason = "VISIT_LIMIT";
                return null;
            }
            WorkspaceFile file = directory.files().next();
            String relative = file == root ? "" : directory.prefix() + file.name();
            String kind;
            try {
                kind = file.kind();
                if (kind.equals("directory")) {
                    pending.push(
                            new DirectoryCursor(
                                    file.children().iterator(),
                                    relative.isEmpty() ? "" : relative + "/"));
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

    /** The traversal's thread-confined iterator and the prefix shared by its children. */
    private record DirectoryCursor(
            @NonNull Iterator<? extends @NonNull WorkspaceFile> files, @NonNull String prefix) {}
}
