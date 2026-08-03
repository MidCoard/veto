package top.focess.veto.terminal;

import com.github.ajalt.mordant.terminal.Terminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jline.reader.*;
import org.jline.terminal.TerminalBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.client.core.ClientSession;
import top.focess.veto.client.core.ClientView;
import top.focess.veto.client.core.Logging;
import top.focess.veto.client.core.StyleToken;
import top.focess.veto.client.core.StyledText;
import top.focess.veto.contract.ClientOptions;
import top.focess.veto.contract.IpcClient;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.Version;

/**
 * Main interactive REPL terminal controller for Veto Core.
 *
 * <p>Reads user input via JLine {@link LineReader} and coordinates with the backend via {@link
 * IpcClient}. The interaction protocol (session state, request pipeline, frame dispatch) lives in a
 * shared {@link ClientSession}; this class owns only the REPL presentation — the prompt, the
 * inline-above-prompt rendering, and the one retained interrupt for the mid-line prompt swap.
 *
 * <h3>Threading</h3>
 *
 * <ul>
 *   <li><b>Main thread</b> — blocks in {@link LineReader#readLine}; on each return it asks the
 *       session for the current state to render the prompt, then submits the line.
 *   <li><b>Consumer thread ({@code veto-incoming})</b> — drains {@link IpcClient#receive} into
 *       {@link ClientSession#onFrame}, which drives rendering back through {@link TerminalView} and
 *       returns the next frame to dispatch (sent here).
 * </ul>
 *
 * <h3>The one retained interrupt</h3>
 *
 * <p>When a {@link IpcFrame.Prompt} arrives, the consumer thread (via {@link
 * TerminalView#onPrompt}) sets {@link #promptSwapPending} and <b>then</b> interrupts the main
 * thread to break its blocking {@code readLine}. The main thread's {@code UserInterruptException}
 * catch distinguishes the two wake causes by reading the flag <b>after</b> {@code readLine} throws:
 * flag set ⇒ a Prompt-swap re-render; flag unset ⇒ a genuine Ctrl+C (the {@code 0x03} byte, never a
 * {@code Thread.interrupt}) ⇒ cancel. Reading the flag after the throw — not clearing a "stale"
 * interrupt at the loop top — is what keeps the distinction sound: the consumer's flag-set and
 * interrupt are not atomic, so a loop-top clear could drain a not-yet-delivered interrupt and then
 * misread the swap's late interrupt as Ctrl+C (exiting the terminal on a stray Prompt), or fall
 * through to {@code readLine} with a stale prompt and a null mask (a password echoed in plaintext).
 * See the catch in {@link #repl} for the full rationale.
 */
public class VetoTerminal {

    private static final Logger log = LoggerFactory.getLogger(VetoTerminal.class);

    /** ASCII Banner displayed on startup. */
    private static final String HEADER =
            """
                    ██╗   ██╗███████╗████████╗ ██████╗
                    ██║   ██║██╔════╝╚══██╔══╝██╔═══██╗
                    ██║   ██║█████╗     ██║   ██║   ██║
                    ╚██╗ ██╔╝██╔══╝     ██║   ██║   ██║
                     ╚████╔╝ ███████╗   ██║   ╚██████╔╝
                      ╚═══╝  ╚══════╝   ╚═╝    ╚═════╝
                    """;

    /** The Mordant terminal used for styled (ANSI) console output. */
    private final Terminal t;

    /** IPC connection to the backend (transport-agnostic; ZMQ locally). */
    private final IpcClient client;

    /** JLine reader driving the interactive REPL; set in {@link #start(LineReader)}. */
    private LineReader reader;

    /** Output seam — prints above the active prompt via {@link LineReader#printAbove}. */
    private MordantRenderer renderer;

    /** Maps {@link StyleToken}s to Mordant ANSI strings. */
    private MordantTheme theme;

    /** Bottom status bar view over the session. */
    private TerminalStatus status;

    /** The shared interaction protocol (session state, request pipeline, frame dispatch). */
    private ClientSession session;

    /** Reference to the main REPL thread so the consumer can interrupt its blocking readLine. */
    private Thread mainThread;

    /** Set to {@code false} to signal all background threads to stop. */
    private volatile boolean running;

    /**
     * Verbose tool-trace flag (driven by the {@code VETO_DEBUG} env var). When {@code true}, the
     * tool-result hook renders the full observation body; when {@code false} (default), it
     * truncates to a short preview so the user's terminal is not flooded with raw tool output.
     * Always true is also safe - the user can flip it on whenever they need to verify what the
     * model is being fed.
     */
    private final boolean verboseToolTrace = "true".equalsIgnoreCase(System.getenv("VETO_DEBUG"));

    /**
     * The sole swap-wake signal: set by the consumer thread (via {@link TerminalView#onPrompt}) to
     * request that the main thread's next {@code readLine} wake be treated as a Prompt-swap
     * re-render rather than a genuine Ctrl+C. The consumer sets this <b>before</b> calling {@link
     * Thread#interrupt} (which breaks the blocking {@code readLine}), so "an interrupt fired"
     * implies "the flag was set."
     *
     * <p>It is read and cleared in exactly one place: the {@code UserInterruptException} catch in
     * {@link #repl}, <b>after</b> {@code readLine} throws. Reading/clearing it after the throw (not
     * at the loop top) is what makes the swap/Ctrl+C distinction sound — see the catch for the full
     * rationale (a loop-top clear races the consumer's non-atomic flag-set + interrupt, causing a
     * phantom-Ctrl+C terminal exit and a stale-prompt/mask plaintext leak).
     */
    private volatile boolean promptSwapPending;

    /**
     * Constructs a new VetoTerminal instance.
     *
     * @param t the Mordant Terminal instance used for styled outputs
     * @param client the IpcClient used for IPC communications with the backend
     */
    public VetoTerminal(@NonNull Terminal t, @NonNull IpcClient client) {
        this.t = t;
        this.client = client;
    }

    /**
     * Initializes the terminal components, starts the consumer thread, and enters the interactive
     * REPL loop. Ensures clean resource teardown on shutdown.
     *
     * @param reader the JLine LineReader instance to read inputs from
     */
    public void start(@NonNull LineReader reader) {
        this.reader = reader;
        this.mainThread = Thread.currentThread();
        this.theme = new MordantTheme(t);
        this.renderer = new MordantRenderer(reader);
        this.session = new ClientSession(new TerminalView());
        this.status = new TerminalStatus(reader.getTerminal(), session, theme);
        status.refresh();

        printBanner();
        running = true;

        // Heartbeats are sent by the IpcClient itself (its ipc-hb thread).

        // --- hint widgets ---
        // Binds custom parameter autocomplete / tail-tip widgets to JLine reader.
        VetoHintWidgets hintWidgets = new VetoHintWidgets(reader, client);
        hintWidgets.enable();

        // --- incoming consumer thread ---
        // Drains frames from the connection through the session, which drives rendering back via
        // TerminalView and returns the next frame to dispatch (if any).
        Thread consumerThread =
                new Thread(
                        () -> {
                            while (running) {
                                try {
                                    IpcFrame.ServerFrame frame = client.receive();
                                    if (frame == null) {
                                        continue;
                                    }
                                    IpcFrame.ClientFrame reply = session.onFrame(frame);
                                    if (reply != null) {
                                        client.send(reply);
                                    }
                                } catch (Exception e) {
                                    if (running) {
                                        log.warn("Error in incoming loop, terminating thread", e);
                                    }
                                    break;
                                }
                            }
                        },
                        "veto-incoming");
        consumerThread.setDaemon(true);
        consumerThread.start();

        try {
            // Enter the main interactive loop.
            repl();
        } finally {
            // Teardown: stop loops, disable widgets, interrupt the consumer, restore the terminal
            // scroll region + clear the status bar, and close the connection (close flushes the Bye
            // frame before teardown).
            running = false;
            hintWidgets.disable();
            consumerThread.interrupt();
            status.close();
            client.close();
        }
    }

    // ── repl ──────────────────────────────────────────────────────────────

    /**
     * The main interactive REPL read-eval-print loop. Renders the prompt from the current session
     * state, reads a line, and submits it (a command, a prompt reply, or a cancel).
     */
    private void repl() {
        while (running) {
            ClientSession.PromptView view = session.promptView();
            ClientSession.State state = view.state();
            IpcFrame.Prompt activePrompt = view.activePrompt();
            IpcFrame.VetoPayload activeVeto =
                    (state == ClientSession.State.PROMPTED && activePrompt != null)
                            ? activePrompt.veto()
                            : null;

            String promptText;
            Character mask;
            if (state == ClientSession.State.PROMPTED && activePrompt != null) {
                if (activeVeto != null) {
                    // HITL veto: render a numbered option picker above the prompt. The reply is
                    // resolved to an option name (by index or name) after readLine and sent as an
                    // Input frame; the server's veto-first routing resolves the parked veto.
                    renderVetoPicker(activeVeto);
                    promptText =
                            theme.style(StyleToken.PROMPT, "▸")
                                    + " "
                                    + theme.style(StyleToken.ACCENT, "veto " + activeVeto.tool())
                                    + " pick> ";
                    mask = null;
                    reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.TRUE);
                } else {
                    // Server-prompted input request.
                    promptText =
                            theme.style(StyleToken.PROMPT, "▸")
                                    + " "
                                    + theme.style(StyleToken.ACCENT, activePrompt.content())
                                    + " ";
                    mask = activePrompt.mask() ? '*' : null;
                    reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.TRUE);
                }
            } else {
                // Standard prompt: green when logged in, red when logged out.
                boolean loggedIn = view.username() != null;
                promptText =
                        loggedIn
                                ? theme.style(StyleToken.SUCCESS, "▸ ")
                                : theme.style(StyleToken.ERROR, "◇ ");
                mask = null;
                reader.setVariable(LineReader.DISABLE_HISTORY, Boolean.FALSE);
            }

            String line;
            try {
                line = reader.readLine(promptText, mask);
            } catch (UserInterruptException e) {
                // two ways to trigger this, first the user press the ctrl+C,
                // second the consumerThread actively call the mainThread to interrupt.
                // when consumerThread actively call the mainThread to interrupt, the
                // promptSwapPending flag is always first set
                // and then the mainThread interrupts, so when two ways come both, there are
                // multiple cases.
                // case 1: the ctrl+C is always first, so we move to cancel, it is expected
                // behavior.
                // case 2: the ctrl+C is first, but promptSwapPending is true when codes come to
                // here, and until calling the "continue", the mainThread is not interrupted.
                // so in this case, when come to reader.readLine again, another
                // UserInterruptException is thrown, and we move to cancel, this is also expected.
                // case 3: the ctrl+C is first, but promptSwapPending is true and the mainThread
                // interrupts when codes come to here, so thread's interrupt status is cleared.
                // when codes come to reader.readLine again, nothing break this, the user might need
                // ctrl+C again, but it is acceptable, and rarely happened.
                // case 4: the ctrl+C is last, so we first change the prompt and then move to
                // cancel, it is expected behavior
                if (promptSwapPending) {
                    promptSwapPending = false;
                    Thread.interrupted();
                    continue;
                }
                // Flag false ⇒ not a swap ⇒ genuine Ctrl+C → cancel the in-flight request/prompt,
                // or exit if idle (cancel returns null as the shutdown signal per IDLE ×
                // cancel).
                IpcFrame.Cancel cancel = session.cancel();
                if (cancel == null) {
                    break; // idle → exit the REPL
                }
                client.send(cancel);
                continue;
            } catch (EndOfFileException e) {
                break;
            }

            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            // A veto picker reply is resolved to an option name (by 1-based index or name) before
            // submit. An invalid choice re-renders the picker (the veto is still pending
            // server-side,
            // so the snapshot still shows PROMPTED + veto). Cancel-during-veto is handled by the
            // UserInterruptException catch above, which sends Cancel - the server declines the
            // veto.
            if (activeVeto != null) {
                String resolved = resolveVetoChoice(activeVeto, line);
                if (resolved == null) {
                    renderer.println(theme.style(StyleToken.ERROR, "  invalid choice: " + line));
                    continue;
                }
                line = resolved;
            }

            // Route the line authoritatively: submit checks the *current* state under its lock
            // and returns exactly the frame to send — Input for a prompt reply, Request for a
            // dispatched new command, or null if enqueued while awaiting (or discarded as a stale
            // reply). Routing on the `state` captured above would be a TOCTOU: the consumer thread
            // can transition the state machine during the blocking readLine (a Prompt arriving, or
            // a
            // Done/Error completing the in-flight request), so the render-time snapshot may no
            // longer hold when the user submits. The snapshot is used only to *render* the prompt,
            // never to route the reply. A dispatched command is echoed by onCommandDispatched
            // (fired
            // by submit on dispatch, and by onFrame when a queued command is auto-dispatched) — so
            // a
            // queued command echoes when it actually runs, not when it is merely typed — and the
            // call site need not distinguish Input from Request: both just get sent, and only the
            // null case (enqueued / discarded) sends nothing.
            IpcFrame.ClientFrame reply = session.submit(line);
            if (reply != null) {
                client.send(reply);
            }
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    /**
     * Echoes the user's input wrapped in a clean, framed box for visual clarity.
     *
     * @param line the input text
     */
    private void echoInput(@NonNull String line) {
        int termWidth = reader.getTerminal().getWidth();
        int maxWidth = termWidth > 0 ? termWidth - 4 : 76;
        int borderLen = Math.max(0, Math.min(maxWidth, line.length() + 4));
        renderer.println("");
        renderer.println(theme.style(StyleToken.BORDER, "  ╭─ you " + "─".repeat(borderLen)));
        renderer.println("  │ " + line);
        renderer.println(
                theme.style(
                        StyleToken.BORDER, "  ╰" + "─".repeat(Math.min(maxWidth, borderLen + 4))));
    }

    /**
     * Renders a HITL veto as a numbered option picker above the prompt. Each option is shown as
     * {@code [n] NAME}; the user replies with the index or the option name (resolved by {@link
     * #resolveVetoChoice}).
     *
     * @param veto the pending veto payload
     */
    private void renderVetoPicker(IpcFrame.@NonNull VetoPayload veto) {
        renderer.println("");
        renderer.println(
                theme.style(StyleToken.BORDER, "  ╭─ HITL ")
                        + theme.style(StyleToken.ACCENT, veto.tool())
                        + theme.style(StyleToken.MUTED, "  (" + veto.scenario() + ")"));
        if (!veto.args().isEmpty()) {
            renderer.println("  │ " + theme.style(StyleToken.MUTED, "args: " + veto.args()));
        }
        int i = 1;
        for (String opt : veto.options()) {
            renderer.println("  │ " + theme.style(StyleToken.ACCENT, "[" + i + "]") + "  " + opt);
            i++;
        }
        renderer.println(theme.style(StyleToken.BORDER, "  ╰──"));
    }

    /**
     * Resolves a veto picker reply to an option name. Accepts a 1-based index into the offered
     * options or the option name (case-insensitive). Returns {@code null} if the reply matches no
     * offered option, so the caller can re-render the picker.
     *
     * @param veto the pending veto payload
     * @param reply the user's reply (index or name)
     * @return the resolved option name, or {@code null} if invalid
     */
    private @Nullable String resolveVetoChoice(
            IpcFrame.@NonNull VetoPayload veto, @NonNull String reply) {
        List<String> options = veto.options();
        if (options.isEmpty()) {
            return null;
        }
        try {
            int idx = Integer.parseInt(reply.trim());
            if (idx >= 1 && idx <= options.size()) {
                return options.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {
            // fall through to a name match
        }
        for (String opt : options) {
            if (opt.equalsIgnoreCase(reply.trim())) {
                return opt;
            }
        }
        return null;
    }

    /** Prints the startup ASCII banner header. */
    private void printBanner() {
        for (String line : HEADER.split("\n")) {
            renderer.println(theme.styleBold(StyleToken.ACCENT, line));
        }
        renderer.println(theme.style(StyleToken.MUTED, "  terminal " + VetoVersion.VERSION));
        renderer.println("");
    }

    // ── ClientView ────────────────────────────────────────────────────────

    /**
     * Renders a labeled, bordered block - the explicit visual distinction between the dimmed
     * "thinking" stream (interim reasoning) and the normal "veto" message stream (user-facing
     * answer). Each block carries a label and a border so the two streams never blur into one
     * undifferentiated run of text, even when only one is present on a given turn.
     *
     * @param label the header label (e.g. "thinking" or "veto")
     * @param border the style for the box border (top, left rule, bottom)
     * @param body the style for the content lines
     * @param content the text to render (may contain newlines)
     */
    private void renderBoxed(
            @NonNull String label,
            @NonNull StyleToken border,
            @NonNull StyleToken body,
            @NonNull String content) {
        renderer.println("");
        renderer.println(theme.style(border, "  ╭─ " + label + " " + "─".repeat(20)));
        for (String line : content.split("\n", -1)) {
            renderer.println(theme.style(border, "  │ ") + theme.style(body, line));
        }
        renderer.println(theme.style(border, "  ╰" + "─".repeat(24)));
    }

    /**
     * Renders a one-line summary of a tool call for the {@code onToolCall} indicator. Prefers a
     * small set of common "primary" arg keys (path/command/url/...) so the user sees what the agent
     * is about to operate on, falling back to a generic {@code tool(k=v, k2=v2)} for unknown tools.
     * Empty args reduce to just the tool name.
     */
    private static @NonNull String summarizeToolCall(IpcFrame.@NonNull ToolCall call) {
        String tool = call.toolName();
        if (call.isEmpty()) {
            return tool;
        }
        String[] preferred = {
            "path", "directoryPath", "file", "filePath", "command", "url", "query", "input"
        };
        for (String key : preferred) {
            Object v = call.args().get(key);
            if (v != null) {
                return tool + "(" + key + "=" + abbreviate(v.toString(), 80) + ")";
            }
        }
        StringBuilder sb = new StringBuilder(tool).append("(");
        boolean first = true;
        for (var e : call.args().entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append('=').append(abbreviate(String.valueOf(e.getValue()), 40));
            first = false;
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Truncates a string with a trailing ellipsis if it exceeds the cap, otherwise returns as-is.
     */
    private static @NonNull String abbreviate(@NonNull String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /**
     * Truncates a multi-line body to the first {@code maxLines} lines and appends a "…(N more
     * lines)" hint so the user knows the preview was cut. Returns the input unchanged if it already
     * fits.
     */
    private static @NonNull String truncateForPreview(@NonNull String body, int maxLines) {
        String[] lines = body.split("\n", -1);
        if (lines.length <= maxLines) {
            return body;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        sb.append("\n…(").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }

    /**
     * {@link ClientView} adapter rendering session events to the REPL. Runs on the consumer thread;
     * all output goes through {@link LineReader#printAbove} (thread-safe) and {@link
     * TerminalStatus#refresh} (reads session snapshots under the session's own lock).
     */
    private final class TerminalView implements ClientView {

        @Override
        public void onDelta(@NonNull String content) {
            // User-facing answer: a labeled "veto" box with a cyan (ACCENT) border and normal body
            // text. Explicitly distinct from the "thinking" box (onThought), which is fully dimmed
            // - different label, different border color, different body color. The two streams
            // never blur together, even when only one is present on a turn.
            renderBoxed("veto", StyleToken.ACCENT, StyleToken.PLAIN, content);
        }

        @Override
        public void onThought(@NonNull String content) {
            // Interim reasoning: a labeled "thinking" box, fully dimmed (MUTED border and body) so
            // it recedes behind the cyan "veto" answer box. Same shape as onDelta but different
            // label and different color - the user can tell the two streams apart at a glance.
            renderBoxed("thinking", StyleToken.MUTED, StyleToken.MUTED, content);
        }

        @Override
        public void onToolCall(IpcFrame.@NonNull ToolCall call) {
            // Claude-Code-style indicator: a single muted line announcing the tool + its key arg
            // (the "what is the agent about to do"). Compact so a long sequence of tool calls does
            // not bury the reasoning. The matching result lands in onToolResult right after.
            String summary = summarizeToolCall(call);
            renderer.println(theme.style(StyleToken.MUTED, "  ◆ " + summary));
        }

        @Override
        public void onToolResult(IpcFrame.@NonNull ToolResult result) {
            // Framed observation the model saw. Default: truncate to the first 20 lines + show
            // success/failure marker so a long tool output does not flood the REPL. The user
            // can set VETO_DEBUG=1 to render the full body for debugging.
            String body = result.body() == null ? "" : result.body();
            String marker = result.success() ? "✓" : "✗";
            String header = "  ◇ result " + marker;
            if (!verboseToolTrace) {
                body = truncateForPreview(body, 20);
            }
            renderer.println("");
            renderer.println(theme.style(StyleToken.MUTED, header));
            for (String line : body.split("\n", -1)) {
                renderer.println(
                        theme.style(StyleToken.MUTED, "    ")
                                + theme.style(StyleToken.PLAIN, line));
            }
        }

        @Override
        public void onProgress(@NonNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
        }

        @Override
        public void onPrompt(IpcFrame.@NonNull Prompt prompt) {
            // The session already set state=PROMPTED. Signal the main thread to re-render with the
            // prompted prompt: set the flag FIRST, then interrupt readLine. The ordering matters —
            // "interrupt fired" must imply "flag was set" so the catch can distinguish this
            // swap-wake
            // from a genuine Ctrl+C (see the UserInterruptException catch in repl).
            promptSwapPending = true;
            mainThread.interrupt();
        }

        @Override
        public void onError(@NonNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
        }

        @Override
        public void onTerminate(@NonNull StyledText content) {
            renderer.println(theme.style(content.token(), content.text()));
            running = false;
            // The main thread is blocked in readLine; setting running=false alone won't wake it, so
            // the prompt would keep blinking (the session is dead but the REPL looks alive) until
            // the user presses Enter. Interrupt to break readLine — the UserInterruptException
            // catch
            // then sees promptSwapPending is false (a Terminate is not a swap) and routes to
            // cancel, which always ends the iteration (null at IDLE → break; or a Cancel at
            // RUNNING
            // → send + continue → while(running) is now false → exit). Either way the REPL exits
            // and
            // teardown runs, promptly telling the user the session is over.
            mainThread.interrupt();
        }

        @Override
        public void onIdle() {
            status.refresh();
        }

        @Override
        public void onRunning() {
            status.refresh();
        }

        @Override
        public void onCommandDispatched(@NonNull String line) {
            // Fires on whichever thread performed the dispatch: the main thread (a line typed at
            // IDLE) or the consumer thread (a queued command auto-dispatched from onFrame).
            // printAbove is thread-safe, so both paths render uniformly.
            if (!line.startsWith("/")) {
                echoInput(line);
                renderer.println(theme.style(StyleToken.MUTED, "  thinking…"));
            }
        }

        @Override
        public void onMetaChanged(ClientSession.@NonNull SessionMeta meta) {
            status.refresh();
        }
    }

    // ── tab completion ───────────────────────────────────────────────────

    /**
     * JLine {@link Completer} that fetches tab-completion candidates from the backend.
     *
     * <p>Completion is triggered only when the buffer starts with {@code /} (slash-commands).
     * Candidates are retrieved synchronously via {@link IpcClient#complete} with a 3-second
     * timeout; if the backend does not respond in time, no candidates are offered.
     */
    private class VetoCompleter implements Completer {

        @Override
        public void complete(
                @NonNull LineReader r, @NonNull ParsedLine line, @NonNull List<Candidate> out) {
            String fullLine = line.line();
            // Completion is only requested for slash commands.
            if (!fullLine.startsWith("/")) return;
            IpcFrame.CompleteResult compResult = client.complete(fullLine, 2, TimeUnit.SECONDS);
            if (compResult != null) {
                for (IpcFrame.Completion comp : compResult.candidates()) {
                    String name = comp.value();
                    String desc =
                            comp.description() != null && !comp.description().isEmpty()
                                    ? comp.description()
                                    : null;
                    String group =
                            comp.group() != null && !comp.group().isEmpty() ? comp.group() : null;
                    out.add(new Candidate(name, name, group, desc, null, null, true));
                }
            }
        }
    }

    // ── main ──────────────────────────────────────────────────────────────

    /**
     * Main entry point for starting the VetoTerminal client.
     *
     * @param args command line arguments, supports --debug or -d to enable log file output
     */
    public static void main(@NonNull String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        ClientOptions options = ClientOptions.parse(args);
        Logging.configure(options.debug());

        // The workspace root is sent to the backend in the IPC Hello handshake and becomes the
        // agent's operational root. --workspace makes it explicit; otherwise it falls back to the
        // JVM cwd (user.dir) - i.e. wherever the terminal is launched from.
        String workspaceCwd =
                options.workspace() != null ? options.workspace() : System.getProperty("user.dir");
        if (options.workspace() != null && !Files.isDirectory(Path.of(options.workspace()))) {
            System.err.println("Workspace not a directory: " + options.workspace());
            return;
        }

        org.jline.terminal.Terminal jt = null;
        try {
            // Build the system JLine Terminal and the Mordant Terminal.
            // Force the JNA NativeWinSysTerminal over the FFM one. On JDK 25 JLine 3.26.1 prefers
            // the FFM terminal, but it does not advertise the capabilities JLine's Status bar
            // requires (change_scroll_region / save_cursor / restore_cursor / cursor_address), so
            // Status.display stays null: the status bar never renders and Status.resize NPEs on
            // window-resize signals. The JNA terminal advertises those capabilities, so the status
            // bar works. JNA is already on the classpath (jna + mordant-jvm-jna).
            jt =
                    TerminalBuilder.builder()
                            .system(true)
                            .ffm(false)
                            .jna(true)
                            .encoding("UTF-8")
                            .build();
            Terminal mt = MordantTerminal.create();
            System.out.println("Connecting to backend at " + options.address() + " ...");
            IpcClient transport =
                    new IpcClient(options.address(), VetoVersion.VERSION, workspaceCwd);
            Version serverVersion = transport.serverProductVersion();
            System.out.println(
                    "Connected to veto-core "
                            + serverVersion
                            + " (veto-terminal "
                            + VetoVersion.VERSION
                            + ").");
            VetoTerminal vt = new VetoTerminal(mt, transport);
            Completer completer = vt.new VetoCompleter();
            LineReader r = LineReaderBuilder.builder().terminal(jt).completer(completer).build();
            r.setVariable(LineReader.HISTORY_FILE, Path.of(".veto_history"));
            vt.start(r);
        } catch (IOException e) {
            System.err.println("Terminal failed: " + e.getMessage());
        } finally {
            // Closing the JLine Terminal restores the original terminal mode (scroll region,
            // echo, etc.) and flushes pending output — ensuring the status bar is cleaned up
            // even on crash.  JLine's Status.close() can throw NPE on terminals that don't
            // support the status bar (display == null), so we catch that here.
            if (jt != null) {
                try {
                    jt.close();
                } catch (IOException | NullPointerException ignored) {
                    // Best-effort cleanup on the way out.
                }
            }
        }
    }
}
