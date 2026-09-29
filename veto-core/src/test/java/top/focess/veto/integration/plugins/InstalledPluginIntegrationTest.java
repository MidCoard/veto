package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.service.ServiceException;
import top.focess.veto.plugin.runtime.PluginClassLoader;

class InstalledPluginIntegrationTest {
    @Test
    void manifestPackagePublishesServiceThroughHost(@TempDir @NonNull Path root) throws Exception {
        Path packageDirectory = Files.createDirectory(root.resolve("service-provider"));
        writePackage(packageDirectory);
        Path decliningDirectory = Files.createDirectory(root.resolve("declining-provider"));
        writeDecliningPackage(decliningDirectory);
        Path disabledDirectory = Files.createDirectory(root.resolve("disabled-provider"));
        writeDisabledPackage(disabledDirectory);
        var configuration = new PluginConfigurations();
        configuration.setDisabled(Set.of("sample.disabled"));
        try (var manager =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        configuration)) {
            assertEquals(PluginState.ACTIVE, manager.plugin("sample.installed").state());
            assertEquals(1, manager.declined().size());
            assertEquals("sample.declined", manager.declined().getFirst().id());
            assertEquals("UNSUPPORTED_ENVIRONMENT", manager.declined().getFirst().reason().name());
            assertTrue(manager.isDeclined("sample.declined"));
            assertEquals(1, manager.disabled().size());
            assertTrue(manager.isDisabled("sample.disabled"));
            assertEquals("Installed service", manager.plugin("sample.installed").displayName());
            var request = new JsonValue.StringValue("hello");
            assertEquals(
                    request,
                    manager.services().find("sample:echo", 1).orElseThrow().invoke(request));
        }
    }

    @Test
    void liveDisableRevokesOldHandleAndEnableLoadsNewClassloader(@TempDir @NonNull Path root)
            throws Exception {
        writePackage(Files.createDirectory(root.resolve("service-provider")));
        try (var manager =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations())) {
            var first = manager.services().find("sample:echo", 1).orElseThrow();
            ClassLoader firstLoader =
                    manager.catalog()
                            .entries(StandardContributionPoints.SERVICES)
                            .getFirst()
                            .implementation()
                            .getClass()
                            .getClassLoader();
            if (!(firstLoader instanceof PluginClassLoader oldLoader))
                throw new AssertionError("Service did not load in its plugin classloader");
            manager.disable("sample.installed");
            assertTrue(oldLoader.isClosed());
            assertTrue(manager.isDisabled("sample.installed"));
            assertTrue(manager.services().find("sample:echo", 1).isEmpty());
            assertThrows(
                    ServiceException.class, () -> first.invoke(new JsonValue.StringValue("old")));
            manager.enable("sample.installed");
            assertFalse(manager.isDisabled("sample.installed"));
            ClassLoader newLoader =
                    manager.catalog()
                            .entries(StandardContributionPoints.SERVICES)
                            .getFirst()
                            .implementation()
                            .getClass()
                            .getClassLoader();
            if (newLoader == null) throw new AssertionError("Replacement loader is unavailable");
            assertNotSame(oldLoader, newLoader);
            assertEquals(
                    new JsonValue.StringValue("new"),
                    manager.services()
                            .find("sample:echo", 1)
                            .orElseThrow()
                            .invoke(new JsonValue.StringValue("new")));
        }
    }

    @Test
    void disableDrainsPendingDataCleanupBeforeClosingLoader(@TempDir @NonNull Path root)
            throws Exception {
        writePackage(Files.createDirectory(root.resolve("service-provider")));
        try (var manager =
                        new PluginManager(
                                root.toString(),
                                "",
                                false,
                                5000,
                                PluginTestSupport.providerOf(
                                        PluginTestSupport.configurationServices(null)),
                                new PluginConfigurations());
                var worker = Executors.newSingleThreadExecutor()) {
            ClassLoader loader =
                    manager.catalog()
                            .entries(StandardContributionPoints.SERVICES)
                            .getFirst()
                            .implementation()
                            .getClass()
                            .getClassLoader();
            if (!(loader instanceof PluginClassLoader pluginLoader))
                throw new AssertionError("Service did not load in its plugin classloader");
            manager.beginDataCleanup("sample.installed");
            var closing = worker.submit(() -> manager.disable("sample.installed"));
            try {
                for (int attempt = 0;
                        attempt < 100 && !manager.isDisabled("sample.installed");
                        attempt++) Thread.sleep(10);
                assertTrue(manager.isDisabled("sample.installed"));
                assertFalse(closing.isDone());
                assertFalse(pluginLoader.isClosed());
            } finally {
                manager.endDataCleanup("sample.installed");
            }
            closing.get(20, TimeUnit.SECONDS);
            assertTrue(pluginLoader.isClosed());
        }
    }

    private void writePackage(@NonNull Path directory) throws IOException {
        Files.writeString(
                directory.resolve("plugin.json"),
                """
                {
                  "schemaVersion": 1,
                  "id": "sample.installed",
                  "name": "Installed service",
                  "version": "1.0.0",
                  "type": "java",
                  "entryPoint": "top.focess.veto.integration.plugins.InstalledServicePlugin",
                  "artifact": "plugin.jar"
                }
                """);
        writeJar(directory, "InstalledServicePlugin.class");
    }

    private void writeDecliningPackage(@NonNull Path directory) throws IOException {
        Files.writeString(
                directory.resolve("plugin.json"),
                """
                {
                  "schemaVersion": 1,
                  "id": "sample.declined",
                  "name": "Declining service",
                  "version": "1.0.0",
                  "type": "java",
                  "entryPoint": "top.focess.veto.integration.plugins.InstalledDecliningPlugin",
                  "artifact": "plugin.jar"
                }
                """);
        writeJar(directory, "InstalledDecliningPlugin.class");
    }

    private static void writeDisabledPackage(@NonNull Path directory) throws IOException {
        Files.writeString(
                directory.resolve("plugin.json"),
                """
                {
                  "schemaVersion": 1,
                  "id": "sample.disabled",
                  "name": "Disabled service",
                  "version": "1.0.0",
                  "type": "java",
                  "entryPoint": "does.not.exist.DisabledPlugin",
                  "artifact": "missing.jar"
                }
                """);
    }

    private void writeJar(@NonNull Path directory, @NonNull String resource) throws IOException {
        try (var jar =
                new JarOutputStream(Files.newOutputStream(directory.resolve("plugin.jar")))) {
            for (String entry :
                    java.util.List.of(resource, resource.replace(".class", "$1.class"))) {
                try (var stream = getClass().getResourceAsStream(entry)) {
                    if (stream == null) {
                        if (entry.equals(resource))
                            throw new IOException("Sample provider class is unavailable");
                        continue;
                    }
                    jar.putNextEntry(new JarEntry("top/focess/veto/integration/plugins/" + entry));
                    stream.transferTo(jar);
                    jar.closeEntry();
                }
            }
        }
    }
}
