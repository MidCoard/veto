package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;

class PluginActivationStoreTest {
    @Test
    void persistedChoicesOverrideStartupDefaultsAcrossStoreInstances(@TempDir @NonNull Path root)
            throws Exception {
        var repository = mock(PluginActivationRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var first = new PluginActivationStore(repository, root.toString());
        assertFalse(first.desiredEnabled("sample", Set.of("sample")));
        first.setEnabled("sample", true);
        verify(repository)
                .saveAndFlush(argThat(row -> row.getId().equals("sample") && row.isEnabled()));

        when(repository.findAll()).thenReturn(List.of(new PluginActivationEntity("sample", true)));
        var restarted = new PluginActivationStore(repository, root.toString());
        assertTrue(restarted.desiredEnabled("sample", Set.of("sample")));
        restarted.setEnabled("sample", false);
        assertTrue(restarted.disabledIds(Set.of()).contains("sample"));
    }

    @Test
    void previousFileChoicesAreImportedOnceIntoDatabase(@TempDir @NonNull Path root)
            throws Exception {
        Files.writeString(
                root.resolve(".plugin-activation"), "# previous state\nsample=disabled\n");
        var repository = mock(PluginActivationRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var store = new PluginActivationStore(repository, root.toString());
        assertFalse(store.desiredEnabled("sample", Set.of()));
        verify(repository)
                .saveAndFlush(argThat(row -> row.getId().equals("sample") && !row.isEnabled()));
    }

    @Test
    void databaseFailureDoesNotChangeDesiredState(@TempDir @NonNull Path root) throws Exception {
        var repository = mock(PluginActivationRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("write rejected"));
        var store = new PluginActivationStore(repository, root.toString());
        assertThrows(
                DataIntegrityViolationException.class, () -> store.setEnabled("sample", false));
        assertTrue(store.desiredEnabled("sample", Set.of()));
    }
}
