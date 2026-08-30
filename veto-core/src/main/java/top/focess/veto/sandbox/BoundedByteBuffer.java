package top.focess.veto.sandbox;

import java.io.ByteArrayOutputStream;
import org.jspecify.annotations.NonNull;

/** A single-writer byte buffer that retains a bounded prefix and discards the remaining bytes. */
final class BoundedByteBuffer {

    private final @NonNull ByteArrayOutputStream delegate = new ByteArrayOutputStream();
    private final int limit;
    private boolean truncated;

    BoundedByteBuffer(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    void write(byte @NonNull [] bytes, int offset, int length) {
        int remaining = limit - delegate.size();
        if (remaining > 0) {
            delegate.write(bytes, offset, Math.min(remaining, length));
        }
        if (length > remaining) {
            truncated = true;
        }
    }

    byte @NonNull [] toByteArray() {
        return delegate.toByteArray();
    }

    boolean truncated() {
        return truncated;
    }
}
