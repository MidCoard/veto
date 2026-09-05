package top.focess.veto.agent.capability;

import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NonNull;

abstract sealed class RestrictedWorkspaceFile implements WorkspaceFile
        permits ReadOnlyWorkspaceFile, WritableWorkspaceFileImpl {
    protected final @NonNull WorkspaceFileAccess access;

    RestrictedWorkspaceFile(@NonNull WorkspaceFileAccess access) {
        this.access = access;
    }

    @Override
    public final @NonNull String name() {
        return access.name();
    }

    @Override
    public final @NonNull String kind() throws IOException {
        return access.kind();
    }

    @Override
    public final long size() throws IOException {
        return access.size();
    }

    @Override
    public final @NonNull InputStream openRead() throws IOException {
        return access.openRead();
    }
}
