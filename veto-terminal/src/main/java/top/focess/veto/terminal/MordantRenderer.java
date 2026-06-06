package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.util.List;
import java.util.Map;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.TerminalResponse;

/**
 * Renders structured backend responses via Mordant rich-text output. Handles both the legacy {@link
 * TerminalResponse} and the new {@link IpcFrame} protocol types.
 */
public class MordantRenderer {

    private final Terminal terminal;

    public MordantRenderer(Terminal terminal) {
        this.terminal = terminal;
    }

    // ── Legacy TerminalResponse rendering ───────────────────────────────

    public void render(TerminalResponse resp) {
        switch (resp.type()) {
            case MESSAGE -> println(resp.content());
            case CODE -> codeBlock(resp.content(), resp.meta());
            case TABLE -> table(resp.meta());
            case ERROR -> error(resp.content());
            case LIST -> bulletList(resp.content());
            case SUGGESTION -> suggestion(resp.content());
            case PROMPT -> prompt(resp.content());
            case PROGRESS -> {}
        }
    }

    // ── New IpcFrame rendering ──────────────────────────────────────────

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
            default -> {}
        }
    }

    // ── low-level output ────────────────────────────────────────────────

    public void println(String text) {
        MordantTerminal.println(terminal, text);
    }

    public void print(String text) {
        MordantTerminal.print(terminal, text);
    }

    public void error(String text) {
        MordantTerminal.println(terminal, MordantTerminal.red(terminal, "✗ " + text));
    }

    public void separator() {
        MordantTerminal.println(terminal, MordantTerminal.dim(terminal, "─".repeat(50)));
    }

    @SuppressWarnings("unchecked")
    private void codeBlock(String code, Map<String, Object> meta) {
        String lang = meta.getOrDefault("language", "code").toString();
        MordantTerminal.println(
                terminal, MordantTerminal.cyan(terminal, "┌── " + lang + " " + "─".repeat(36)));
        MordantTerminal.println(terminal, code);
        MordantTerminal.println(terminal, MordantTerminal.cyan(terminal, "└" + "─".repeat(42)));
    }

    private void bulletList(String text) {
        for (String line : text.split("\n")) {
            MordantTerminal.println(terminal, "  • " + line);
        }
    }

    private void suggestion(String text) {
        MordantTerminal.println(terminal, MordantTerminal.dim(terminal, text));
    }

    private void prompt(String text) {
        MordantTerminal.print(terminal, MordantTerminal.bold(terminal, text) + " ");
    }

    @SuppressWarnings("unchecked")
    private void table(Map<String, Object> meta) {
        List<String> headers = (List<String>) meta.get("headers");
        List<List<String>> rows = (List<List<String>>) meta.get("rows");
        if (headers == null || rows == null) return;
        MordantTerminal.println(terminal, MordantTerminal.table(terminal, headers, rows));
    }
}
