package top.focess.veto.agent.capability;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;

final class WritableWorkspaceFileImpl extends RestrictedWorkspaceFile
        implements WritableWorkspaceFile {
    WritableWorkspaceFileImpl(@NonNull WorkspaceFileAccess access) {
        super(access);
    }

    @Override
    public @NonNull OutputStream openForCreate() throws IOException {
        return access.openWrite(false);
    }

    @Override
    public @NonNull OutputStream openForReplace() throws IOException {
        return access.openWrite(true);
    }

    @Override
    public void moveTo(@NonNull WritableWorkspaceFile target) throws IOException {
        if (!(target instanceof WritableWorkspaceFileImpl destination)) {
            throw new SecurityException("Unrecognized file handle");
        }
        access.moveTo(destination.access);
    }

    @Override
    public void delete() throws IOException {
        access.delete();
    }

    @Override
    public @NonNull List<@NonNull WritableWorkspaceFile> children() throws IOException {
        List<@NonNull WritableWorkspaceFile> result = new ArrayList<>();
        for (var child : access.children()) {
            result.add(new WritableWorkspaceFileImpl(child));
        }
        return List.copyOf(result);
    }
}
