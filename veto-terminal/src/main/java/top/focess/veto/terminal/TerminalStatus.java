package top.focess.veto.terminal;

import java.util.List;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;
import org.jspecify.annotations.NonNull;
import top.focess.veto.client.core.ClientSession;
import top.focess.veto.client.core.StyleToken;
import top.focess.veto.client.core.Theme;

/**
 * Draws the session status bar on JLine's bottom status line.
 *
 * <p>JLine's {@link Status} is <b>not thread-safe</b> (no synchronization on its mutable fields:
 * {@code lines}, {@code suspended}, {@code scrollRegion}, {@code display.oldLines}). The consumer
 * thread and the main thread both reach {@link #refresh} and {@link #clear} through {@code
 * ClientSession.fire} (which runs outside the session lock), so concurrent calls are possible. All
 * {@link Status#update} calls are serialized on {@link #statusLock} to prevent data races on the
 * underlying JLine object.
 *
 * <p>Session state reads ({@link ClientSession#statusView}) are performed <b>outside</b> {@code
 * statusLock} — they acquire the session's own lock internally, and nesting it inside {@code
 * statusLock} would create a lock-ordering constraint with no benefit. The snapshot is immutable
 * once captured, so it remains consistent through the render.
 */
public final class TerminalStatus {

    private final Theme theme;
    private final Status status;
    private final ClientSession session;

    /**
     * Guards all calls to {@link Status#update} — JLine's {@code Status} is not thread-safe, and
     * both the consumer thread and the main thread can call {@link #refresh}/{@link #clear}
     * concurrently via {@code ClientSession.fire}.
     */
    private final Object statusLock = new Object();

    public TerminalStatus(
            @NonNull Terminal terminal, @NonNull ClientSession session, @NonNull Theme theme) {
        this.theme = theme;
        this.session = session;
        this.status = Status.getStatus(terminal);
        if (this.status == null) {
            throw new IllegalStateException("Terminal does not support JLine Status bar");
        }
    }

    /**
     * Redraws the status line from a single atomic session snapshot (username, turn count, queue).
     */
    public void refresh() {
        // One atomic snapshot — username, turn count and the queue describe the same moment.
        // Reading them via separate snapshot/pendingQueue calls would be a TOCTOU: the consumer
        // thread can mutate the session between the reads (a Done changing the username, or a
        // submit adding to the queue), so the bar could show a username and a queue that never
        // coexisted.
        ClientSession.StatusView view = session.statusView();
        String text =
                view.username() == null
                        ? "  /login to start | /help"
                        : "  " + view.username() + " | turns: " + view.turnCount();
        String styled = theme.style(StyleToken.MUTED, text);

        List<String> queue = view.pending();
        if (!queue.isEmpty()) {
            StringBuilder queueText = new StringBuilder(" | ⏳ next: ");
            for (int i = 0; i < queue.size(); i++) {
                if (i > 0) {
                    queueText.append(" ➔ ");
                }
                String req = queue.get(i);
                // Truncate long commands to keep the status bar layout clean.
                if (req.length() > 15) {
                    req = req.substring(0, 12) + "...";
                }
                queueText.append("\"").append(req).append("\"");
            }
            styled += " " + theme.style(StyleToken.WARNING, queueText.toString());
        }

        synchronized (statusLock) {
            status.update(List.of(AttributedString.fromAnsi(styled)));
        }
    }

    /**
     * Restores the terminal scroll region and clears the status bar.
     *
     * <p>JLine's {@link Status#update} with an empty list clears the internal line buffer but does
     * <b>not</b> restore the scroll region — the bottom row(s) remain pinned outside the scroll
     * area, so the status bar content persists visually even after the process exits. Calling
     * {@link Status#close} first restores the scroll region to the full terminal height; the
     * subsequent empty update then erases any remaining content and flushes it.
     */
    public void close() {
        synchronized (statusLock) {
            status.close();
            status.update(List.of());
        }
    }
}
