package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ScriptExecutionModeTest {
    @Test
    void trustAcknowledgementCannotOverrideIsolatedMode() {
        assertThrows(IOException.class, () -> ScriptExecutionMode.ISOLATED.requireAvailable(true));
        assertThrows(IOException.class, () -> ScriptExecutionMode.ISOLATED.requireAvailable(false));
    }

    @Test
    void trustedModeStillRequiresExplicitAcknowledgement() {
        assertThrows(IOException.class, () -> ScriptExecutionMode.TRUSTED.requireAvailable(false));
        assertDoesNotThrow(() -> ScriptExecutionMode.TRUSTED.requireAvailable(true));
    }
}
