package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import top.focess.veto.plugin.runtime.ScriptExecutionMode;

class IsolatedScriptConfigurationTest {
    @Test
    void isolatedModeRejectsBeforeReadingPackagesEvenWhenTrustedCodeIsEnabled() {
        var configuration = new PluginConfigurations();
        configuration.setScriptMode(ScriptExecutionMode.ISOLATED);
        for (boolean trusted : new boolean[] {false, true}) {
            var failure =
                    assertThrows(
                            IOException.class,
                            () ->
                                    new PluginManager(
                                            "missing-package",
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
