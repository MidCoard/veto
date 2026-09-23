package top.focess.veto.plugin.secrets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static top.focess.veto.util.Nullness.requireNonNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.plugin.runtime.PluginHostServices;
import top.focess.veto.secret.api.CredentialImportAccess;
import top.focess.veto.secret.api.SecretDetectionModel;
import top.focess.veto.vault.KeysteadVault;
import top.focess.veto.veto.LlamaCppBridge;

class SecretProtectionConfigurationTest {
    @Test
    void exposesTheImportAccessServiceOnlyWithAVault() {
        new ApplicationContextRunner()
                .withUserConfiguration(requireNonNull(SecretProtectionConfiguration.class))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var services =
                                    context.getBean(requireNonNull(PluginHostServices.class));
                            assertTrue(
                                    services.services().isEmpty(),
                                    "Without a vault the host grants no import access");
                        });
    }

    @Test
    void vaultBackedInstallGrantsTheImportAccessService() {
        new ApplicationContextRunner()
                .withUserConfiguration(requireNonNull(SecretProtectionConfiguration.class))
                .withBean(
                        requireNonNull(KeysteadVault.class),
                        () -> mock(ToolDocs.nonNullClass(KeysteadVault.class)))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var services =
                                    context.getBean(requireNonNull(PluginHostServices.class));
                            assertTrue(
                                    services.services()
                                            .containsKey(
                                                    ToolDocs.nonNullClass(
                                                            CredentialImportAccess.class)));
                        });
    }

    @Test
    void detectionModelServiceRequiresAnAvailableBridge() {
        new ApplicationContextRunner()
                .withUserConfiguration(requireNonNull(SecretProtectionConfiguration.class))
                .withBean(
                        requireNonNull(LlamaCppBridge.class),
                        () -> {
                            LlamaCppBridge bridge =
                                    mock(ToolDocs.nonNullClass(LlamaCppBridge.class));
                            when(bridge.isAvailable()).thenReturn(true);
                            when(bridge.infer(anyString(), anyString()))
                                    .thenReturn(CompletableFuture.completedFuture("[\"x\"]"));
                            return bridge;
                        })
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var services =
                                    context.getBean(requireNonNull(PluginHostServices.class));
                            var service =
                                    services.services()
                                            .get(ToolDocs.nonNullClass(SecretDetectionModel.class));
                            if (!(service instanceof SecretDetectionModel model))
                                throw new AssertionError("Detection model service missing");
                            assertTrue(model.isAvailable());
                            assertEquals(
                                    Optional.of("[\"x\"]"),
                                    model.complete(
                                            "secret-detection", Map.of("text", "synthetic-input")));
                        });
    }

    @Test
    void detectionModelServiceIsNotGrantedWithAnUnavailableBridge() {
        new ApplicationContextRunner()
                .withUserConfiguration(requireNonNull(SecretProtectionConfiguration.class))
                .withBean(
                        requireNonNull(LlamaCppBridge.class),
                        () -> mock(ToolDocs.nonNullClass(LlamaCppBridge.class)))
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            var services =
                                    context.getBean(requireNonNull(PluginHostServices.class));
                            assertFalse(
                                    services.services()
                                            .containsKey(
                                                    ToolDocs.nonNullClass(
                                                            SecretDetectionModel.class)));
                        });
    }
}
