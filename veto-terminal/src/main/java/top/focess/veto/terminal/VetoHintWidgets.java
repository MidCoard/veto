package top.focess.veto.terminal;

import java.util.concurrent.TimeUnit;
import org.jline.reader.LineReader;
import org.jline.reader.LineReader.SuggestionType;
import org.jline.widget.Widgets;
import top.focess.veto.contract.IpcFrame;

/**
 * JLine {@link org.jline.widget.Widgets} subclass that hooks buffer-change widgets to fetch and
 * display inline tail-tip hints via {@link LineReader#setTailTip}.
 *
 * <h3>When hints trigger</h3>
 *
 * A hint is requested from the backend when the buffer starts with {@code /} and ends with a space.
 * This means the user has finished typing a command name and the backend can suggest the next
 * argument (e.g. {@code <user> <pass>} after {@code /login }).
 *
 * <h3>When hints persist</h3>
 *
 * After a hint is fetched, it stays visible while the user types arguments. Only self-insert is
 * allowed to keep the hint — the user is typing what the hint suggested. Backspace, delete, and
 * kill-whole-line clear the hint because the user is editing the command, not accepting the
 * suggestion.
 */
public final class VetoHintWidgets extends Widgets {

    private static final long HINT_TIMEOUT_MS = 500;

    private final ZmqClient client;
    private boolean enabled;

    public VetoHintWidgets(LineReader reader, ZmqClient client) {
        super(reader);
        this.client = client;

        addWidget("_veto-self-insert", this::vetoInsert);
        addWidget("_veto-backward-delete-char", this::vetoBackwardDelete);
        addWidget("_veto-delete-char", this::vetoDelete);
        addWidget("_veto-accept-line", this::vetoAcceptLine);
        addWidget("_veto-kill-whole-line", this::vetoKillWholeLine);
    }

    // ── enable / disable ──────────────────────────────────────────────────

    public void enable() {
        if (enabled) return;
        aliasWidget("_veto-self-insert", LineReader.SELF_INSERT);
        aliasWidget("_veto-backward-delete-char", LineReader.BACKWARD_DELETE_CHAR);
        aliasWidget("_veto-delete-char", LineReader.DELETE_CHAR);
        aliasWidget("_veto-accept-line", LineReader.ACCEPT_LINE);
        aliasWidget("_veto-kill-whole-line", LineReader.KILL_WHOLE_LINE);
        reader.setAutosuggestion(SuggestionType.TAIL_TIP);
        enabled = true;
    }

    public void disable() {
        if (!enabled) return;
        aliasWidget("." + LineReader.SELF_INSERT, LineReader.SELF_INSERT);
        aliasWidget("." + LineReader.BACKWARD_DELETE_CHAR, LineReader.BACKWARD_DELETE_CHAR);
        aliasWidget("." + LineReader.DELETE_CHAR, LineReader.DELETE_CHAR);
        aliasWidget("." + LineReader.ACCEPT_LINE, LineReader.ACCEPT_LINE);
        aliasWidget("." + LineReader.KILL_WHOLE_LINE, LineReader.KILL_WHOLE_LINE);
        reader.setAutosuggestion(SuggestionType.NONE);
        enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── widget callbacks ──────────────────────────────────────────────────

    /** User typed a character — may extend the command or trigger a new hint. */
    public boolean vetoInsert() {
        doHint(LineReader.SELF_INSERT);
        return true;
    }

    /** User pressed backspace — always re-evaluate, clear if trigger is gone. */
    public boolean vetoBackwardDelete() {
        doHint(LineReader.BACKWARD_DELETE_CHAR);
        return true;
    }

    /** User pressed delete — same as backspace, re-evaluate. */
    public boolean vetoDelete() {
        doHint(LineReader.DELETE_CHAR);
        return true;
    }

    /** User killed the whole line — clear everything. */
    public boolean vetoKillWholeLine() {
        clearTailTip();
        callWidget(LineReader.KILL_WHOLE_LINE);
        return true;
    }

    /** User pressed enter — clear the hint before submitting. */
    public boolean vetoAcceptLine() {
        clearTailTip();
        callWidget(LineReader.ACCEPT_LINE);
        return true;
    }

    private void doHint(String widget) {
        callWidget(widget);

        String line = buffer().toString();

        // Left command context — drop the hint.
        if (!line.startsWith("/")) {
            clearTailTip();
            return;
        }

        // Starts with "/" and ends with a space — trigger a new hint fetch.
        if (line.endsWith(" ")) {
            IpcFrame.HintResult hintResult =
                    client.hint(line, HINT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (hintResult != null && hintResult.hint() != null) {
                String display = hintResult.hint().displayText();
                if (display != null) {
                    setTailTip(display);
                    return;
                }
            }
        }

        clearTailTip();
    }
}
