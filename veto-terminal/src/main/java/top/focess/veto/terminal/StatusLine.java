package top.focess.veto.terminal;

import java.util.Collections;
import java.util.List;

import org.jline.utils.AttributedString;
import org.jline.utils.Display;

/**
 * A persistent status bar at the bottom of the terminal (like Claude Code's status line). Uses
 * JLine's {@link Display} to reserve a screen region and update it atomically.
 */
public class StatusLine {

    private static final String BG = "\033[48;5;236m";
    private static final String RESET = "\033[0m";

    private final Display display;

    public StatusLine(Display display) {
        this.display = display;
    }

    /**
     * Show a status message in the reserved bottom region.
     */
    public void show(String text) {
        display.update(List.of(AttributedString.fromAnsi(BG + " " + text + " " + RESET)), -1);
    }

    /**
     * Hide the status line.
     */
    public void hide() {
        display.update(Collections.emptyList(), 0);
    }
}
