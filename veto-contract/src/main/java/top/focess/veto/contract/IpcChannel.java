package top.focess.veto.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.time.Duration;

/**
 * A unidirectional IPC channel over a single {@link IpcFile}. The channel is either a sender or a
 * receiver — the file flows in only one direction. Two files (req + resp) form a full-duplex
 * connection.
 *
 * <p>Each file uses a simple state machine: {@code IDLE → READY → IDLE → …}. The writer sets {@code
 * READY} after writing; the reader resets to {@code IDLE} after reading.
 */
public final class IpcChannel implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IpcFile file;
    private final long pollIntervalNs;
    private volatile boolean running = true;

    public IpcChannel(IpcFile file, Duration pollInterval) {
        this.file = file;
        this.pollIntervalNs = pollInterval.toNanos();
    }

    // ── I/O ─────────────────────────────────────────────────────────────

    /**
     * Wait for a frame, with a deadline. Returns {@code null} on timeout. Call this on the
     * receive-side channel.
     */
    public IpcFrame receive(Duration timeout) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (running && System.nanoTime() < deadline) {
            IpcFrame frame = tryReceive();
            if (frame != null) return frame;
            sleepNs(pollIntervalNs);
        }
        return null;
    }

    /**
     * Send a frame, blocking until the file is IDLE and the write completes. Call this on the
     * send-side channel. Never blocks on the receive-side channel because they are independent
     * files.
     */
    public void send(IpcFrame frame) throws IOException {
        byte[] payload = JSON.writeValueAsBytes(frame);
        if (payload.length > file.maxPayloadSize()) {
            throw new IOException(
                    "Frame too large: " + payload.length + " > " + file.maxPayloadSize());
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (running && System.nanoTime() < deadline) {
            try (FileLock lock = file.tryLock()) {
                if (lock == null) {
                    sleepNs(pollIntervalNs);
                    continue;
                }
                IpcState state = file.readState();
                if (state == IpcState.IDLE) {
                    file.writePayload(payload);
                    file.writeState(IpcState.READY);
                    file.flush();
                    return;
                }
                // Drain stale frame from an abandoned previous exchange
                if (state == IpcState.READY) {
                    file.writeState(IpcState.IDLE);
                    file.flush();
                    continue;
                }
            }
            sleepNs(pollIntervalNs);
        }
        throw new IOException("Timed out waiting to send frame");
    }

    // ── internal ────────────────────────────────────────────────────────

    private IpcFrame tryReceive() throws IOException {
        try (FileLock lock = file.tryLock()) {
            if (lock == null) return null;
            IpcState state = file.readState();
            if (state == IpcState.READY) {
                int len = file.readLength();
                if (len <= 0 || len > file.maxPayloadSize()) {
                    file.writeState(IpcState.IDLE);
                    file.flush();
                    return new IpcFrame.Error("Bad payload length: " + len);
                }
                byte[] data = file.readPayload(len);
                file.writeState(IpcState.IDLE);
                file.flush();
                return JSON.readValue(data, IpcFrame.class);
            }
        }
        return null;
    }

    private static void sleepNs(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        file.close();
    }

    public void delete() throws IOException {
        running = false;
        file.delete();
    }

    public String filename() {
        return file.filename();
    }

    public IpcFile file() {
        return file;
    }
}
