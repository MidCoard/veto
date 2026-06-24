package top.focess.veto.client.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import java.util.logging.LogManager;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Application-startup logging bootstrap shared by both Veto clients (terminal + TUI). Configures
 * the Logback root level from the parsed {@link top.focess.veto.contract.ClientOptions} and routes
 * {@code java.util.logging} (e.g. JLine's fallback warnings) through SLF4J.
 *
 * <p>This is an application concern, not a wire-protocol concern — it coexists with the wire-types
 * in {@code veto-protocol}, which depends on the SLF4J facade only and never mandates a logging
 * implementation. Logback + the JUL→SLF4J bridge are {@code compileOnly} in {@code veto-protocol}
 * so this class compiles without forcing a backend on consumers; each client application declares
 * its own Logback at runtime.
 */
public final class Logging {

    private Logging() {}

    /**
     * Configures the logging level: {@code DEBUG} when {@code debug} is true, otherwise {@code OFF}
     * (the clients are interactive; normal output goes through the renderer, not logs).
     *
     * @param debug whether to enable debug-level logging
     */
    public static void configure(boolean debug) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
                    .setLevel(debug ? Level.DEBUG : Level.OFF);
        }

        // Route JUL through SLF4J to silence JLine fallback warnings on the console.
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
