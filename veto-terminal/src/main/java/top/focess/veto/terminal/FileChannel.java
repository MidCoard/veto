package top.focess.veto.terminal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jline.reader.Candidate;
import top.focess.veto.contract.IpcChannel;
import top.focess.veto.contract.IpcFile;
import top.focess.veto.contract.IpcFrame;

/**
 * Terminal-side IPC endpoint with two unidirectional files:
 *
 * <ul>
 *   <li>{@code <id>-req.ipc} — terminal writes, backend reads
 *   <li>{@code <id>-resp.ipc} — backend writes, terminal reads
 * </ul>
 *
 * <p>Because the channels are independent, the terminal can always send {@code bye}, {@code
 * heartbeat}, or {@code cancel} on the req channel even while a streaming response is arriving on
 * the resp channel.
 */
public class FileChannel implements AutoCloseable {

    private static final Path BASE = Path.of(System.getProperty("user.home"), ".veto", "terminal");
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);
    private static final int IPC_FILE_SIZE_KB = 256;
    private static final int IPC_FILE_SIZE = IPC_FILE_SIZE_KB * 1024;

    private final IpcChannel reqChannel;
    private final IpcChannel respChannel;
    private final String terminalId;
    volatile boolean busy;

    public FileChannel() {
        this.terminalId = UUID.randomUUID().toString();
        Path reqPath = BASE.resolve(terminalId + "-req.ipc");
        Path respPath = BASE.resolve(terminalId + "-resp.ipc");
        try {
            this.reqChannel = new IpcChannel(new IpcFile(reqPath, IPC_FILE_SIZE), POLL_INTERVAL);
            this.respChannel = new IpcChannel(new IpcFile(respPath, IPC_FILE_SIZE), POLL_INTERVAL);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create IPC channels", e);
        }
    }

    // ── send / receive ──────────────────────────────────────────────────

    /** Send a frame to the backend on the req channel. Always available. */
    public void send(IpcFrame frame) throws IOException {
        reqChannel.send(frame);
    }

    /** Wait for a frame from the backend on the resp channel. */
    public IpcFrame receive(Duration timeout) throws IOException {
        return respChannel.receive(timeout);
    }

    // ── high-level API ──────────────────────────────────────────────────

    public List<Candidate> complete(String partial, long timeoutMs) {
        if (busy) return List.of();
        try {
            send(new IpcFrame.Complete(partial));
            IpcFrame resp = receive(Duration.ofMillis(timeoutMs));
            if (resp instanceof IpcFrame.Done done && done.content() != null) {
                String content = done.content();
                if (content.isBlank()) return List.of();
                List<Candidate> out = new ArrayList<>();
                for (String line : content.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    String[] parts = trimmed.split("\t", 3);
                    String name = parts[0];
                    String desc = parts.length > 1 && !parts[1].isBlank() ? parts[1] : null;
                    String group = parts.length > 2 && !parts[2].isBlank() ? parts[2] : null;
                    out.add(new Candidate(name, name, group, desc, null, null, true));
                }
                return out;
            }
        } catch (IOException ignored) {
        }
        return List.of();
    }

    public IpcFrame sendAndReceive(IpcFrame request, long timeoutMs, FrameHandler handler)
            throws IOException {
        busy = true;
        try {
            send(request);
            long deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();

            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return new IpcFrame.Error("Request timed out");
                }
                IpcFrame frame = receive(Duration.ofNanos(Math.max(remaining, 1_000_000)));
                if (frame == null) {
                    return new IpcFrame.Error("No response — backend unreachable?");
                }

                handler.onFrame(frame);

                if (frame instanceof IpcFrame.Prompt prompt) {
                    IpcFrame.Input input = handler.onPrompt(prompt);
                    if (input != null) {
                        send(input);
                        deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos();
                    }
                    continue;
                }
                if (frame instanceof IpcFrame.Done || frame instanceof IpcFrame.Error) {
                    return frame;
                }
            }
        } finally {
            busy = false;
        }
    }

    public void cancel() throws IOException {
        send(new IpcFrame.Cancel());
    }

    /**
     * Request a placeholder hint for the next expected argument. Returns a pair of {@code
     * (placeholder, description)} or {@code (null, null)}.
     */
    public String[] hint(String input, long timeoutMs) {
        // Don't interfere with an active request/response exchange
        if (busy) return new String[] {null, null};
        try {
            send(new IpcFrame.Hint(input));
            IpcFrame resp = receive(Duration.ofMillis(timeoutMs));
            if (resp instanceof IpcFrame.Done done) {
                String text = done.content();
                String desc = (String) done.meta().get("description");
                return new String[] {text, desc};
            }
        } catch (IOException ignored) {
        }
        return new String[] {null, null};
    }

    public void bye() {
        try {
            send(new IpcFrame.Bye());
        } catch (IOException ignored) {
        }
    }

    public Thread startHeartbeat(long intervalMs) {
        Thread t =
                new Thread(
                        () -> {
                            while (!Thread.currentThread().isInterrupted()) {
                                try {
                                    Thread.sleep(intervalMs);
                                    send(new IpcFrame.Heartbeat());
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (IOException e) {
                                    break;
                                }
                            }
                        },
                        "veto-hb-" + terminalId.substring(0, 8));
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public void close() throws IOException {
        try {
            reqChannel.delete();
        } catch (IOException ignored) {
        }
        try {
            respChannel.delete();
        } catch (IOException ignored) {
        }
    }

    public String terminalId() {
        return terminalId;
    }

    // ── FrameHandler interface ──────────────────────────────────────────

    public interface FrameHandler {
        void onFrame(IpcFrame frame);

        IpcFrame.Input onPrompt(IpcFrame.Prompt prompt);
    }
}
