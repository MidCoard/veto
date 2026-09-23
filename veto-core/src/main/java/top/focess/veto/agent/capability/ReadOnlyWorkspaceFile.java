package top.focess.veto.agent.capability;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.agent.capability.WorkspaceFile;

final class ReadOnlyWorkspaceFile extends RestrictedWorkspaceFile {
    ReadOnlyWorkspaceFile(@NonNull WorkspaceFileAccess access) {
        super(access);
    }

    @Override
    public @NonNull List<@NonNull WorkspaceFile> children() throws IOException {
        List<@NonNull WorkspaceFile> result = new ArrayList<>();
        for (var child : access.children()) {
            result.add(new ReadOnlyWorkspaceFile(child));
        }
        return List.copyOf(result);
    }
}
