package top.focess.veto.agent.capability;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import org.jspecify.annotations.NonNull;

/** Stages bounded content in memory and publishes only while its authority remains valid. */
final class WorkspaceOutputStream extends OutputStream {
    private final @NonNull WorkspaceFileAccess access;
    private final boolean replace;
    private final @NonNull ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean closed;
    private boolean failed;

    WorkspaceOutputStream(@NonNull WorkspaceFileAccess access, boolean replace) {
        this.access = access;
        this.replace = replace;
    }

    @Override
    public void write(int value) throws IOException {
        check(1);
        buffer.write(value);
    }

    @Override
    public void write(byte @NonNull [] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            failed = true;
            throw new IndexOutOfBoundsException();
        }
        check(length);
        buffer.write(bytes, offset, length);
    }

    @Override
    public void flush() throws IOException {
        check(0);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!failed) {
                access.publish(buffer.toByteArray(), replace);
            }
        } finally {
            buffer.reset();
        }
    }

    private void check(int length) throws IOException {
        if (closed || failed) {
            throw new IOException("Stream is closed or failed");
        }
        try {
            access.verify();
            if ((long) buffer.size() + length > WorkspaceFileAccess.MAX_BYTES) {
                throw new IOException("Content exceeds 16 MiB (16,777,216 bytes)");
            }
        } catch (IOException | RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }
}
