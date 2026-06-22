package top.focess.veto.terminal;

import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.LineReader;
import org.jline.reader.LineReader.SuggestionType;
import org.jline.widget.Widgets;
import top.focess.veto.contract.IpcClient;
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

    private final IpcClient client;
    private boolean enabled;

    /**
     * Constructs a new VetoHintWidgets instance associated with the specified LineReader. Registers
     * key widget implementations for intercepting user editing actions.
     *
     * @param reader the JLine LineReader instance
     * @param client the IpcClient used to fetch autocomplete/tail-tip hints from the backend
     */
    public VetoHintWidgets(@NotNull LineReader reader, @NotNull IpcClient client) {
        super(reader);
        this.client = client;

        // Custom JLine widgets that wrap the standard keybindings to intercept buffer changes.
        addWidget("_veto-self-insert", this::vetoInsert);
        addWidget("_veto-backward-delete-char", this::vetoBackwardDelete);
        addWidget("_veto-delete-char", this::vetoDelete);
        addWidget("_veto-accept-line", this::vetoAcceptLine);
        addWidget("_veto-kill-whole-line", this::vetoKillWholeLine);
    }

    // ── enable / disable ──────────────────────────────────────────────────

    /**
     * Activates the tail-tip widgets by aliasing the standard widgets to our intercepted ones and
     * enabling TAIL_TIP suggestions in the reader.
     */
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

    /**
     * Deactivates the tail-tip widgets by restoring JLine's original key bindings and disabling
     * autosuggestions.
     */
    public void disable() {
        if (!enabled) return;
        // Prefixing with '.' retrieves the original JLine built-in widget implementations.
        aliasWidget("." + LineReader.SELF_INSERT, LineReader.SELF_INSERT);
        aliasWidget("." + LineReader.BACKWARD_DELETE_CHAR, LineReader.BACKWARD_DELETE_CHAR);
        aliasWidget("." + LineReader.DELETE_CHAR, LineReader.DELETE_CHAR);
        aliasWidget("." + LineReader.ACCEPT_LINE, LineReader.ACCEPT_LINE);
        aliasWidget("." + LineReader.KILL_WHOLE_LINE, LineReader.KILL_WHOLE_LINE);
        reader.setAutosuggestion(SuggestionType.NONE);
        enabled = false;
    }

    /**
     * Checks whether the tail-tip hints widget layer is currently enabled.
     *
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    // ── widget callbacks ──────────────────────────────────────────────────

    /**
     * Intercepts standard character insertion. Triggers or clears the suggestion depending on the
     * new buffer contents.
     *
     * @return true to indicate the action was handled
     */
    public boolean vetoInsert() {
        doHint(LineReader.SELF_INSERT);
        return true;
    }

    /**
     * Intercepts backspace (backward character deletion). Triggers or clears the suggestion
     * depending on the new buffer contents.
     *
     * @return true to indicate the action was handled
     */
    public boolean vetoBackwardDelete() {
        doHint(LineReader.BACKWARD_DELETE_CHAR);
        return true;
    }

    /**
     * Intercepts forward character deletion. Triggers or clears the suggestion depending on the new
     * buffer contents.
     *
     * @return true to indicate the action was handled
     */
    public boolean vetoDelete() {
        doHint(LineReader.DELETE_CHAR);
        return true;
    }

    /**
     * Intercepts standard line killing (e.g. Ctrl+U). Clears any active tail-tip suggestion before
     * delegating.
     *
     * @return true to indicate the action was handled
     */
    public boolean vetoKillWholeLine() {
        clearTailTip();
        callWidget(LineReader.KILL_WHOLE_LINE);
        return true;
    }

    /**
     * Intercepts line acceptance (pressing Enter). Clears any active tail-tip suggestion to prevent
     * visual artifacts on submit.
     *
     * @return true to indicate the action was handled
     */
    public boolean vetoAcceptLine() {
        clearTailTip();
        callWidget(LineReader.ACCEPT_LINE);
        return true;
    }

    /**
     * Executes the underlying JLine widget action and dynamically evaluates whether to display or
     * clear the tail-tip hints based on the resulting buffer state.
     *
     * @param widget the name of the delegate widget to execute first
     */
    private void doHint(@NotNull String widget) {
        // Execute JLine's original widget action first to update the buffer contents.
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

        // Default: clear the active tail tip if the new state does not warrant a suggestion.
        clearTailTip();
    }
}
