package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.nio.file.Path;
import java.util.Set;

@DataJpaTest
class PluginActivationDatabaseTest {
    @Autowired private @NonNull PluginActivationRepository repository;

    @Test
    void desiredStateSurvivesStoreReconstruction(@TempDir @NonNull Path root) throws Exception {
        var first = new PluginActivationStore(repository, root.toString());
        first.setEnabled("sample", false);
        assertFalse(repository.findById("sample").orElseThrow().isEnabled());
        var restarted = new PluginActivationStore(repository, root.toString());
        assertTrue(restarted.disabledIds(Set.of()).contains("sample"));
        restarted.setEnabled("sample", true);
        assertTrue(repository.findById("sample").orElseThrow().isEnabled());
    }
}
