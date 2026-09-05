package top.focess.veto.agent.capability;

import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NonNull;

/** A read stream that cannot outlive its invocation or silently follow a replaced path. */
final class WorkspaceInputStream extends InputStream {
    private final @NonNull WorkspaceFileAccess access;
    private final @NonNull InputStream delegate;
    private long consumed;
    private boolean closed;

    WorkspaceInputStream(@NonNull WorkspaceFileAccess access, @NonNull InputStream delegate) {
        this.access = access;
        this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
        check();
        int value = delegate.read();
        if (value >= 0) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte @NonNull [] bytes, int offset, int length) throws IOException {
        check();
        int count = delegate.read(bytes, offset, length);
        if (count > 0) {
            count(count);
        }
        return count;
    }

    @Override
    public int available() throws IOException {
        check();
        return delegate.available();
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            delegate.close();
        }
    }

    private void check() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        access.verify();
    }

    private void count(int count) throws IOException {
        consumed += count;
        if (consumed > WorkspaceFileAccess.MAX_BYTES) {
            throw new IOException("Read exceeds 16 MiB (16,777,216 bytes)");
        }
    }
}
