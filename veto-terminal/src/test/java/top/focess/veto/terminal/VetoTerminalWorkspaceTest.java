package top.focess.veto.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ch.qos.logback.classic.LoggerContext;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Isolated("Terminal startup configures process properties, logging and console streams")
class VetoTerminalWorkspaceTest {
    @ParameterizedTest
    @ValueSource(strings = {"explicit", "default", "missing"})
    // This isolated negative test deliberately clears properties and restores their original state.
    @SuppressWarnings("clear.system.property")
    void rejectsUnavailableResolvedWorkspaceBeforeStartingTerminal(@NonNull String source)
            throws InterruptedException {
        var previousWorkspace = System.getProperty("user.dir");
        var previousEncoding = System.getProperty("file.encoding");
        var previousOut = System.out;
        var previousErr = System.err;
        var rootLogger =
                ((LoggerContext) LoggerFactory.getILoggerFactory())
                        .getLogger(Logger.ROOT_LOGGER_NAME);
        var previousLogLevel = rootLogger.getLevel();
        var julRoot = java.util.logging.Logger.getLogger("");
        var previousJulLevel = julRoot.getLevel();
        var previousJulHandlers = julRoot.getHandlers();
        var unavailable =
                Path.of("missing-terminal-workspace-" + UUID.randomUUID())
                        .toAbsolutePath()
                        .toString();
        assertFalse(Files.exists(Path.of(unavailable)));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(output, true, StandardCharsets.UTF_8);
                var stderr = new PrintStream(error, true, StandardCharsets.UTF_8)) {
            System.setOut(stdout);
            System.setErr(stderr);
            var args = new String[0];
            switch (source) {
                case "explicit" -> args = new String[] {"--workspace", unavailable};
                case "default" -> System.setProperty("user.dir", unavailable);
                case "missing" -> System.clearProperty("user.dir");
                default -> throw new AssertionError("Unknown workspace source: " + source);
            }

            VetoTerminal.main(args);

            assertEquals("", output.toString(StandardCharsets.UTF_8));
            assertEquals(
                    (source.equals("missing")
                                    ? "Cannot determine workspace: user.dir is not set"
                                    : "Workspace not a directory: " + unavailable)
                            + System.lineSeparator(),
                    error.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
            if (previousWorkspace == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", previousWorkspace);
            if (previousEncoding == null) System.clearProperty("file.encoding");
            else System.setProperty("file.encoding", previousEncoding);
            rootLogger.setLevel(previousLogLevel);
            for (var handler : julRoot.getHandlers()) julRoot.removeHandler(handler);
            for (var handler : previousJulHandlers) julRoot.addHandler(handler);
            julRoot.setLevel(previousJulLevel);
        }
    }
}
