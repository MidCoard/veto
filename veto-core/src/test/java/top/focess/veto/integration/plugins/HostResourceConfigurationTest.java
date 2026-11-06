package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.focess.veto.api.credentials.VaultAccess;
import top.focess.veto.api.llm.LocalModelRuntime;
import top.focess.veto.api.llm.exceptions.ModelCapabilityException;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.veto.LlamaCppBridge;

class HostResourceConfigurationTest {
    @Test
    void retainedLocalRuntimeCannotOpenModelsAfterPluginStop() throws Exception {
        var runtime = mock(LocalModelRuntime.class);
        when(runtime.endpoint("model.gguf", 2048)).thenReturn("http://127.0.0.1:18081/v1");
        try (var fixture = new WorkflowPluginFixture(List.of())) {
            var bound = new BoundLocalModelRuntime(fixture.runtime, runtime);
            assertEquals("http://127.0.0.1:18081/v1", bound.endpoint("model.gguf", 2048));
            fixture.runtime.close();
            assertThrows(ModelCapabilityException.class, () -> bound.endpoint("model.gguf", 2048));
            verify(runtime, times(1)).endpoint("model.gguf", 2048);
        }
    }

    @Test
    void grantsOnlyPresentHostResourcesWithoutAnyFeaturePluginClass() {
        new ApplicationContextRunner()
                .withUserConfiguration(HostResourceConfiguration.class)
                .run(
                        context ->
                                assertTrue(
                                        context.getBean(PluginHostServices.class)
                                                .services()
                                                .isEmpty()));
        new ApplicationContextRunner()
                .withUserConfiguration(HostResourceConfiguration.class)
                .withBean(KeysteadVault.class, () -> mock(KeysteadVault.class))
                .withBean(LlamaCppBridge.class, () -> mock(LlamaCppBridge.class))
                .run(
                        context -> {
                            var services = context.getBean(PluginHostServices.class).services();
                            assertTrue(services.containsKey(VaultAccess.class));
                            assertTrue(services.containsKey(LocalModelRuntime.class));
                        });
    }
}
