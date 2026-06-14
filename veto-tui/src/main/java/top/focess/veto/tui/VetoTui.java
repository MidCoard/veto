package top.focess.veto.tui;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.focess.veto.contract.ClientOptions;
import top.focess.veto.contract.IpcFrame;
import top.focess.veto.contract.ZmqClient;

/**
 * Main application entry point for Veto TUI. Implements a single-threaded event loop architecture
 * to avoid race conditions.
 */
public class VetoTui {

    private static final Logger log = LoggerFactory.getLogger(VetoTui.class);
    private static final String DEFAULT_ADDR = "tcp://127.0.0.1:5555";

    private final BlockingQueue<TuiEvent> eventQueue = new LinkedBlockingQueue<>();
    private final Terminal terminal;
    private final Display display;
    private final ZmqClient client;
    private final TuiState state;
    private final TuiRenderer renderer;
    private final Attributes originalAttributes;
    private volatile boolean running = true;

    public VetoTui(@NotNull String address) throws IOException {
        this.state = new TuiState();
        this.state.setServerAddress(address);

        // Synchronously initialize the client and handshake with backend
        this.client = new ZmqClient(address);
        this.state.setConnection(TuiState.ConnectionState.CONNECTED);
        this.state.appendAnsiText("\u001B[92mConnected to backend at " + address + "\u001B[0m\n");

        // Build JLine terminal
        this.terminal = TerminalBuilder.builder().system(true).jna(true).encoding("UTF-8").build();

        // Save attributes and enter raw mode
        this.originalAttributes = this.terminal.enterRawMode();

        // Create Display
        this.display = new Display(this.terminal, true);

        // Set initial terminal size (clamp to safe fallback size like 80x24 if columns/rows are 0)
        int cols = Math.max(80, this.terminal.getWidth());
        int rows = Math.max(24, this.terminal.getHeight());
        this.display.resize(rows, cols);
        this.state.handleResize(cols, rows);

        this.renderer = new TuiRenderer();
    }

    public static void main(@NotNull String[] args) {
        System.setProperty("file.encoding", "UTF-8");

        ClientOptions options = ClientOptions.parse(args);
        options.configureLogging();

        try {
            VetoTui tui = new VetoTui(options.address());
            tui.start();
        } catch (Exception e) {
            System.err.println("Fatal: TUI initialization failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void start() {
        // --- ZMQ consumer thread ---
        Thread networkConsumer =
                new Thread(
                        () -> {
                            while (running) {
                                try {
                                    IpcFrame.ServerFrame frame = client.receive();
                                    if (frame != null) {
                                        eventQueue.put(new TuiEvent.ZmqMessage(frame));
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (Exception e) {
                                    log.warn("ZMQ read error", e);
                                    break;
                                }
                            }
                        },
                        "tui-zmq-consumer");
        networkConsumer.setDaemon(true);
        networkConsumer.start();

        // --- Keyboard input thread ---
        Thread keyReader =
                new Thread(
                        () -> {
                            BindingReader bindingReader = new BindingReader(terminal.reader());
                            KeyMap<TuiAction> keyMap = new KeyMap<>();

                            // Bind key combinations
                            String pageUpKey = KeyMap.key(terminal, Capability.key_ppage);
                            if (pageUpKey != null) {
                                keyMap.bind(TuiAction.SCROLL_UP, pageUpKey);
                            }
                            keyMap.bind(TuiAction.SCROLL_UP, "\033[5~"); // ANSI Page Up

                            String pageDownKey = KeyMap.key(terminal, Capability.key_npage);
                            if (pageDownKey != null) {
                                keyMap.bind(TuiAction.SCROLL_DOWN, pageDownKey);
                            }
                            keyMap.bind(TuiAction.SCROLL_DOWN, "\033[6~"); // ANSI Page Down

                            keyMap.bind(TuiAction.CURSOR_LEFT, "\033[D");
                            keyMap.bind(TuiAction.CURSOR_RIGHT, "\033[C");
                            keyMap.bind(TuiAction.SUBMIT, "\r", "\n");
                            keyMap.bind(TuiAction.BACKSPACE, "\177", "\b");
                            keyMap.bind(TuiAction.CANCEL, "\003"); // Ctrl+C
                            keyMap.bind(TuiAction.COMPLETE, "\t");
                            keyMap.bind(TuiAction.CLEAR_INPUT, "\033"); // Escape

                            keyMap.setNomatch(TuiAction.SELF_INSERT);

                            while (running) {
                                try {
                                    TuiAction action = bindingReader.readBinding(keyMap);
                                    if (action != null) {
                                        if (action == TuiAction.SELF_INSERT) {
                                            String last = bindingReader.getLastBinding();
                                            if (last != null && !last.isEmpty()) {
                                                for (int i = 0; i < last.length(); i++) {
                                                    eventQueue.put(
                                                            new TuiEvent.KeyInput(
                                                                    last.charAt(i), last));
                                                }
                                            }
                                        } else {
                                            eventQueue.put(
                                                    new TuiEvent.KeyInput(-1, action.name()));
                                        }
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                } catch (Exception e) {
                                    log.warn("Error reading keystrokes", e);
                                    break;
                                }
                            }
                        },
                        "tui-key-reader");
        keyReader.setDaemon(true);
        keyReader.start();

        // --- WINCH (Resize) signal handler ---
        terminal.handle(
                Terminal.Signal.WINCH,
                signal -> {
                    eventQueue.offer(new TuiEvent.Resize(terminal.getSize()));
                });

        // --- Heartbeat thread ---
        Thread heartbeat =
                new Thread(
                        () -> {
                            while (running) {
                                try {
                                    Thread.sleep(30_000);
                                    eventQueue.offer(new TuiEvent.HeartbeatTick());
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        },
                        "tui-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();

        // Initial Redraw
        redraw();

        // --- Main Event Loop ---
        try {
            while (running) {
                TuiEvent event = eventQueue.take();
                if (event instanceof TuiEvent.Shutdown) {
                    break;
                }
                processEvent(event);
                redraw();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            cleanup();
        }
    }

    private void processEvent(@NotNull TuiEvent event) {
        if (event instanceof TuiEvent.Resize resize) {
            int cols = Math.max(20, resize.size().getColumns());
            int rows = Math.max(10, resize.size().getRows());
            state.handleResize(cols, rows);
            display.resize(rows, cols);
        } else if (event instanceof TuiEvent.ZmqMessage zmq) {
            if (zmq.frame() instanceof IpcFrame.Terminate) {
                state.processServerFrame(zmq.frame());
                running = false;
            } else {
                IpcFrame.ClientFrame reply = state.processServerFrame(zmq.frame());
                if (reply != null) {
                    client.send(reply);
                }
            }
        } else if (event instanceof TuiEvent.HeartbeatTick) {
            client.send(new IpcFrame.Heartbeat());
        } else if (event instanceof TuiEvent.KeyInput key) {
            if (key.keyChar() != -1) {
                state.insertChar((char) key.keyChar());
            } else {
                TuiAction action = TuiAction.valueOf(key.keyName());
                switch (action) {
                    case BACKSPACE -> state.backspace();
                    case CURSOR_LEFT -> state.cursorLeft();
                    case CURSOR_RIGHT -> state.cursorRight();
                    case SCROLL_UP -> state.scrollUp(5);
                    case SCROLL_DOWN -> state.scrollDown(5);
                    case CLEAR_INPUT -> {
                        if (!state.getAutocompleteCandidates().isEmpty()) {
                            String orig = state.getOriginalInputBeforeAutocomplete();
                            state.clearInput();
                            state.insertString(orig);
                        } else {
                            state.clearInput();
                        }
                    }
                    case CANCEL -> {
                        IpcFrame.Cancel cancelFrame = state.handleCancel();
                        if (cancelFrame != null) {
                            client.send(cancelFrame);
                        } else {
                            eventQueue.offer(new TuiEvent.Shutdown());
                        }
                    }
                    case SUBMIT -> {
                        IpcFrame.ClientFrame submitFrame = state.handleSubmit();
                        if (submitFrame != null) {
                            client.send(submitFrame);
                        }
                    }
                    case COMPLETE -> {
                        if (!state.getAutocompleteCandidates().isEmpty()) {
                            int nextIdx =
                                    (state.getSelectedAutocompleteIndex() + 1)
                                            % state.getAutocompleteCandidates().size();
                            state.setSelectedAutocompleteIndex(nextIdx);
                            state.applyAutocompleteSelection(
                                    state.getAutocompleteCandidates().get(nextIdx));
                        } else {
                            String fullLine = state.getCommandBuffer().toString();
                            if (fullLine.startsWith("/")) {
                                IpcFrame.CompleteResult compResult =
                                        client.complete(fullLine, 3, TimeUnit.SECONDS);
                                if (compResult != null
                                        && compResult.candidates() != null
                                        && !compResult.candidates().isEmpty()) {
                                    List<IpcFrame.Completion> candidates = compResult.candidates();
                                    if (candidates.size() == 1) {
                                        String val = candidates.get(0).value();
                                        state.clearInput();
                                        state.insertString(val);
                                    } else {
                                        state.getAutocompleteCandidates().clear();
                                        for (IpcFrame.Completion c : candidates) {
                                            state.getAutocompleteCandidates().add(c.value());
                                        }
                                        state.setOriginalInputBeforeAutocomplete(fullLine);
                                        state.setSelectedAutocompleteIndex(0);
                                        state.applyAutocompleteSelection(candidates.get(0).value());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private void redraw() {
        TuiRenderer.RenderResult result = renderer.render(state);
        display.update(result.lines(), result.cursorOffset());
    }

    private void cleanup() {
        running = false;
        try {
            client.send(new IpcFrame.Bye());
            // Small sleep to flush Bye packet
            Thread.sleep(100);
        } catch (Exception ignored) {
        }
        client.close();
        if (originalAttributes != null) {
            terminal.setAttributes(originalAttributes);
        }
        try {
            terminal.close();
        } catch (Exception ignored) {
        }
    }
}
