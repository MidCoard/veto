package top.focess.veto.terminal;

import java.util.List;
import java.util.Map;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.Status;
import top.focess.veto.contract.IpcMeta;

/**
 * Manages the CLI session status display on JLine's bottom status line. Stores status info directly
 * and updates the Status bar when changes occur.
 */
public class TerminalStatus {

    private final com.github.ajalt.mordant.terminal.Terminal mordant;
    private final Status status;
    private String displayUser;
    private int turnCount;

    public TerminalStatus(Terminal terminal, com.github.ajalt.mordant.terminal.Terminal mordant) {
        this.mordant = mordant;
        this.status = Status.getStatus(terminal);
        if (this.status == null) {
            throw new IllegalStateException("Terminal does not support JLine Status bar");
        }
    }

    /** Applies new session metadata and updates the JLine status bar if the state changed. */
    public void apply(Map<String, Object> meta) {
        if (meta.containsKey(IpcMeta.USERNAME)) {
            String newUser = (String) meta.get(IpcMeta.USERNAME);
            if (newUser == null || !newUser.equals(displayUser)) {
                displayUser = newUser;
            }
        }
        if (meta.containsKey(IpcMeta.TURN_NUMBER)) {
            turnCount = ((Number) meta.get(IpcMeta.TURN_NUMBER)).intValue();
        }
        if (Boolean.TRUE.equals(meta.get(IpcMeta.CLEAR_SESSION))) {
            displayUser = null;
            turnCount = 0;
        }
    }

    /** Redraws the status line. */
    public void refresh() {
        String text;
        if (displayUser == null) {
            text = "  /login to start | /help";
        } else {
            text = "  " + displayUser + " | turns: " + turnCount;
        }
        String styled = MordantTerminal.dim(mordant, text);
        status.update(List.of(AttributedString.fromAnsi(styled)));
    }

    /** Clears the status line. */
    public void clear() {
        status.update(null);
    }

    public String getDisplayUser() {
        return displayUser;
    }

    public int getTurnCount() {
        return turnCount;
    }
}
