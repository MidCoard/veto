package top.focess.veto.integration.plugins;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;

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
                        "",
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(null),
                        configuration)) {
            assertEquals(PluginState.ACTIVE, manager.plugin("sample.installed").state());
            assertEquals(1, manager.declined().size());
            assertEquals("sample.declined", manager.declined().getFirst().id());
            assertEquals("UNSUPPORTED_ENVIRONMENT", manager.declined().getFirst().reason().name());
            assertTrue(manager.isDeclined("sample.declined"));
            assertEquals(1, manager.disabled().size());
            assertTrue(manager.isDisabled("sample.disabled"));
            assertEquals(
                    "Installed service",
                    manager.plugin("sample.installed").implementation().displayName());
            var request = new JsonValue.StringValue("hello");
            assertEquals(
                    request,
                    manager.services().find("sample:echo", 1).orElseThrow().invoke(request));
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
        String className = "top/focess/veto/integration/plugins/" + resource;
        try (var stream = getClass().getResourceAsStream(resource);
                var jar =
                        new JarOutputStream(
                                Files.newOutputStream(directory.resolve("plugin.jar")))) {
            if (stream == null) throw new IOException("Sample provider class is unavailable");
            jar.putNextEntry(new JarEntry(className));
            stream.transferTo(jar);
            jar.closeEntry();
        }
    }
}
