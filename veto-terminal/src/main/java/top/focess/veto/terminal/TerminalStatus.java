package top.focess.veto.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;
import top.focess.veto.contract.IpcMeta;

/**
 * Manages the CLI session status display on JLine's bottom status line. Stores status info directly
 * and updates the Status bar when changes occur.
 *
 * <p><strong>Note:</strong> This class is not thread-safe. Callers must synchronize all accesses on
 * the external state lock.
 */
public class TerminalStatus {

    private final MordantRenderer renderer;
    private final Status status;
    private final List<String> requestQueue = new ArrayList<>();
    private String displayUser;
    private int turnCount;

    /**
     * Constructs a new TerminalStatus instance associated with the specified JLine Terminal.
     *
     * @param terminal the JLine terminal instance
     * @param renderer the MordantRenderer used to format/dim the status line text
     * @throws IllegalStateException if the terminal does not support a JLine Status bar
     */
    public TerminalStatus(Terminal terminal, MordantRenderer renderer) {
        this.renderer = renderer;
        // JLine status bar utility retrieves or creates the status bar instance for the given
        // terminal.
        this.status = Status.getStatus(terminal);
        if (this.status == null) {
            throw new IllegalStateException("Terminal does not support JLine Status bar");
        }
    }

    /**
     * Applies new session metadata received from the server. Extracts fields like USERNAME,
     * TURN_NUMBER, or CLEAR_SESSION from the map.
     *
     * @param meta a map containing session metadata fields
     */
    public void apply(Map<String, Object> meta) {
        if (meta.containsKey(IpcMeta.USERNAME)) {
            String newUser = (String) meta.get(IpcMeta.USERNAME);
            // Only update the display username if it has actually changed to avoid redraw flicker.
            if (newUser == null || !newUser.equals(displayUser)) {
                displayUser = newUser;
            }
        }
        if (meta.containsKey(IpcMeta.TURN_NUMBER)) {
            turnCount = ((Number) meta.get(IpcMeta.TURN_NUMBER)).intValue();
        }
        if (Boolean.TRUE.equals(meta.get(IpcMeta.CLEAR_SESSION))) {
            // When a session is cleared (e.g., logout), we reset the username and turn count.
            displayUser = null;
            turnCount = 0;
        }
    }

    /**
     * Redraws the status line with the current session state. Formats the username and turn count
     * and writes the result to JLine's status bar.
     */
    public void refresh() {
        String text;
        if (displayUser == null) {
            text = "  /login to start | /help";
        } else {
            text = "  " + displayUser + " | turns: " + turnCount;
        }
        String styled = renderer.dim(text);

        // Visualize the request queue if there are pending requests.
        if (!requestQueue.isEmpty()) {
            StringBuilder queueText = new StringBuilder();
            queueText.append(" | ⏳ next: ");
            for (int i = 0; i < requestQueue.size(); i++) {
                if (i > 0) {
                    queueText.append(" ➔ ");
                }
                String req = requestQueue.get(i);
                // Truncate long commands to keep the status bar layout clean.
                if (req.length() > 15) {
                    req = req.substring(0, 12) + "...";
                }
                queueText.append("\"").append(req).append("\"");
            }
            styled += " " + renderer.yellow(queueText.toString());
        }

        // We use AttributedString.fromAnsi to parse Mordant dim/color styles properly for JLine
        // status bar.
        status.update(List.of(AttributedString.fromAnsi(styled)));
    }

    /** Clears the status bar by removing any currently displayed text. */
    public void clear() {
        status.update(List.of());
    }

    /**
     * Returns the currently logged-in display username.
     *
     * @return the display username, or null if not logged in
     */
    public String getDisplayUser() {
        return displayUser;
    }

    /**
     * Returns the current session turn count.
     *
     * @return the turn count
     */
    public int getTurnCount() {
        return turnCount;
    }

    /**
     * Returns the shared queue of pending requests.
     *
     * @return the request queue
     */
    public List<String> getRequestQueue() {
        return requestQueue;
    }
}
