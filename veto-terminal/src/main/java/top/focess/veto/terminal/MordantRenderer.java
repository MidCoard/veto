package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.util.logging.Logger;
import org.jline.reader.LineReader;
import top.focess.veto.contract.IpcFrame;

/**
 * Renders structured backend responses via Mordant rich-text output using {@link IpcFrame} types.
 */
public class MordantRenderer {

    private static final Logger log = Logger.getLogger(MordantRenderer.class.getName());

    private final Terminal terminal;
    private LineReader reader;

    public MordantRenderer(Terminal terminal) {
        this.terminal = terminal;
    }

    public void setReader(LineReader reader) {
        this.reader = reader;
    }

    // ── IpcFrame rendering ──────────────────────────────────────────────

    /** Render a single IPC frame from the backend. */
    public void renderFrame(IpcFrame frame) {
        switch (frame) {
            case IpcFrame.Delta d -> print(d.content());
            case IpcFrame.Done d -> {
                if (d.content() != null) println(d.content());
            }
            case IpcFrame.Error e -> error(e.content());
            case IpcFrame.Progress p ->
                    println(MordantTerminal.dim(terminal, "  ⏳ " + p.content()));
            case IpcFrame.Prompt p -> print(MordantTerminal.bold(terminal, p.content()) + " ");
            default -> {
                if (frame instanceof IpcFrame.Unknown u) {
                    log.warning("Unknown frame type: " + u.type());
                }
            }
        }
    }

    // ── low-level output ────────────────────────────────────────────────

    public void println(String text) {
        if (reader != null) {
            reader.printAbove(text);
        } else {
            MordantTerminal.println(terminal, text);
        }
    }

    public void print(String text) {
        MordantTerminal.print(terminal, text);
    }

    public void error(String text) {
        String err = MordantTerminal.red(terminal, "✗ " + text);
        if (reader != null) {
            reader.printAbove(err);
        } else {
            MordantTerminal.println(terminal, err);
        }
    }

    public void separator() {
        String sep = MordantTerminal.dim(terminal, "─".repeat(50));
        if (reader != null) {
            reader.printAbove(sep);
        } else {
            MordantTerminal.println(terminal, sep);
        }
    }
}
