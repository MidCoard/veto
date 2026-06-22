package top.focess.veto.tui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.util.logging.LogManager;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Application-startup logging bootstrap for the TUI. Configures the Logback root level from the
 * parsed {@link top.focess.veto.contract.ClientOptions} and routes {@code java.util.logging}
 * through SLF4J.
 *
 * <p>Application concern, not a contract concern — lives in the client module so {@code
 * veto-contract} depends on the SLF4J facade only.
 */
public final class Logging {

    private Logging() {}

    /**
     * Configures the logging level: {@code DEBUG} when {@code debug} is true, otherwise {@code
     * OFF}.
     *
     * @param debug whether to enable debug-level logging
     */
    public static void configure(boolean debug) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                    .setLevel(debug ? Level.DEBUG : Level.OFF);
        }

        try {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
            java.util.logging.Logger julRoot = LogManager.getLogManager().getLogger("");
            if (julRoot != null) {
                julRoot.setLevel(
                        debug ? java.util.logging.Level.FINEST : java.util.logging.Level.OFF);
            }
        } catch (Throwable ignored) {
        }
    }
}
