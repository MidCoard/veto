package top.focess.veto.contract;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.logging.LogManager;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * Common configuration options parsed from client command line arguments. Shared between the
 * terminal and TUI modules via veto-contract.
 */
public record ClientOptions(boolean debug, String address) {

    /**
     * Parses the command line arguments array into a ClientOptions instance.
     *
     * @param args the command line arguments
     * @return the parsed options
     */
    public static ClientOptions parse(String[] args) {
        boolean debug = false;
        String address = "tcp://127.0.0.1:5555";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--debug".equals(arg) || "-d".equals(arg)) {
                debug = true;
            } else if (("--address".equals(arg) || "-a".equals(arg)) && i + 1 < args.length) {
                address = args[i + 1];
                i++;
            }
        }

        return new ClientOptions(debug, address);
    }

    /** Configures the Logback logging framework level depending on the parsed options. */
    public void configureLogging() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context) {
            Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (this.debug) {
                rootLogger.setLevel(Level.DEBUG);
            } else {
                rootLogger.setLevel(Level.OFF);
            }
        }

        // Route java.util.logging (JUL) through SLF4J to silence standard console errors (like
        // JLine fallback warnings)
        try {
            SLF4JBridgeHandler.removeHandlersForRootLogger();
            SLF4JBridgeHandler.install();
            java.util.logging.Logger julRoot = LogManager.getLogManager().getLogger("");
            if (julRoot != null) {
                julRoot.setLevel(
                        this.debug ? java.util.logging.Level.FINEST : java.util.logging.Level.OFF);
            }
        } catch (Throwable ignored) {
        }
    }
}
