package top.focess.veto.terminal;

import org.jline.terminal.Terminal;

/**
 * Unicode braille spinner with a message label. Renders inline using carriage return. Runs on a
 * virtual thread so the main loop stays responsive.
 */
public class Spinner {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    private final Terminal terminal;
    private volatile boolean running;
    private Thread thread;

    public Spinner(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * Start the spinner with a message. Non-blocking.
     */
    public void start(String message) {
        running = true;
        thread =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    int i = 0;
                                    while (running) {
                                        terminal.writer()
                                                .print(
                                                        "\r" + CYAN + FRAMES[i] + RESET + " "
                                                                + message);
                                        terminal.writer().flush();
                                        i = (i + 1) % FRAMES.length;
                                        try {
                                            Thread.sleep(80);
                                        } catch (InterruptedException e) {
                                            break;
                                        }
                                    }
                                    // Clear the spinner line
                                    terminal.writer().print("\r\033[2K");
                                    terminal.writer().flush();
                                });
    }

    /**
     * Stop the spinner and clear its line. Blocks briefly for cleanup.
     */
    public void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
