package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.plugin.runtime.ScriptExecutionMode;

class IsolatedScriptConfigurationTest {
    @Test
    void isolatedModeRejectsInstalledScriptEvenWhenTrustedCodeIsEnabled(@TempDir @NonNull Path root)
            throws IOException {
        Path script = Files.createDirectory(root.resolve("text"));
        Files.copy(
                Path.of("../veto-plugin-runtime/examples/text-tools/plugin.json"),
                script.resolve("plugin.json"));
        Files.copy(
                Path.of("../veto-plugin-runtime/examples/text-tools/worker.mjs"),
                script.resolve("worker.mjs"));
        var configuration = new PluginConfigurations();
        configuration.setScriptMode(ScriptExecutionMode.ISOLATED);
        for (boolean trusted : new boolean[] {false, true}) {
            var failure =
                    assertThrows(
                            IOException.class,
                            () ->
                                    new PluginManager(
                                            root.toString(),
                                            "missing-node",
                                            trusted,
                                            5000,
                                            PluginTestSupport.providerOf(null),
                                            configuration));
            var cause = failure.getCause();
            if (cause == null) throw new AssertionError("Missing refusal cause");
            var message = cause.getMessage();
            if (message == null) throw new AssertionError("Missing refusal explanation");
            assertTrue(message.contains("no verified strict OS worker"));
            assertTrue(message.contains("Trusted execution was not used"));
        }
    }
}
