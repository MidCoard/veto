package top.focess.veto.contract;

import org.jetbrains.annotations.NotNull;

/**
 * Parsed command-line options for a Veto client (terminal / TUI). Pure data — no logging or other
 * side effects. Logging bootstrap is an application-startup concern and lives in each client
 * module's {@code main()}, not here.
 *
 * <h3>Supported flags</h3>
 *
 * <ul>
 *   <li>{@code --debug} / {@code -d} — enable debug logging.
 *   <li>{@code --address <addr>} / {@code -a <addr>} — backend connect address (default {@code
 *       tcp://127.0.0.1:5555}).
 * </ul>
 *
 * <p>Unknown flags are rejected with an {@link IllegalArgumentException} rather than silently
 * ignored, so a typo (e.g. {@code --debog}) fails loudly instead of leaving debug silently off.
 *
 * @param debug whether debug logging is enabled
 * @param address the backend connect address
 */
public record ClientOptions(boolean debug, @NotNull String address) {

    /** Default backend connect address. */
    public static final String DEFAULT_ADDRESS = "tcp://127.0.0.1:5555";

    /**
     * Parses the command-line arguments into a {@link ClientOptions}.
     *
     * @param args the command-line arguments
     * @return the parsed options
     * @throws IllegalArgumentException if an unknown flag is encountered or {@code --address} has
     *     no value
     */
    @NotNull
    public static ClientOptions parse(@NotNull String[] args) {
        boolean debug = false;
        String address = DEFAULT_ADDRESS;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--debug".equals(arg) || "-d".equals(arg)) {
                debug = true;
            } else if ("--address".equals(arg) || "-a".equals(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--address requires a value");
                }
                address = args[++i];
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }

        return new ClientOptions(debug, address);
    }
}
