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
 * <p>Stateless beyond the thread-safe JLine {@link Status} — it reads session metadata and the
 * pending-request queue from the {@link ClientSession} on each {@link #refresh}, so it needs no
 * external synchronization. (The prior "not thread-safe; synchronize on an external stateLock"
 * design is gone: the session owns its own lock, and this view just reads snapshots.)
 */
public final class TerminalStatus {

    private final Theme theme;
    private final Status status;
    private final ClientSession session;

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

        status.update(List.of(AttributedString.fromAnsi(styled)));
    }

    /** Clears the status bar by removing any currently displayed text. */
    public void clear() {
        status.update(List.of());
    }
}
