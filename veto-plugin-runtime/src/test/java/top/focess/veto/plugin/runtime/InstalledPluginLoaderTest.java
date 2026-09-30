package top.focess.veto.plugin.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;

class InstalledPluginLoaderTest {
    @Test
    void javaPackageLoadsThroughPrivateClassloader(@TempDir @NonNull Path root) throws Exception {
        Path directory = Files.createDirectory(root.resolve("one"));
        writeJavaPackage(directory);
        var loader =
                new InstalledPluginLoader(
                        Path.of(""), Duration.ofSeconds(5), false, ScriptExecutionMode.TRUSTED);
        var plugins = loader.load(root);
        assertEquals(1, plugins.size());
        InstalledPlugin installed = plugins.getFirst();
        PluginClassLoader pluginLoader;
        try {
            assertEquals("sample.install", installed.identity().id());
            assertEquals("Installed Sample", installed.displayName());
            VetoPlugin plugin =
                    installed.create(
                            new PluginContext(
                                    installed.identity(),
                                    () -> {},
                                    () -> PluginState.NEW,
                                    Map.of(),
                                    Map.of()),
                            new JsonValue.ObjectValue(Map.of()));
            assertNotSame(InstalledSamplePlugin.class, plugin.getClass());
            ClassLoader candidate = plugin.getClass().getClassLoader();
            if (!(candidate instanceof PluginClassLoader loaded))
                throw new AssertionError("Missing plugin classloader");
            pluginLoader = loaded;
            assertEquals("sample.install", pluginLoader.pluginId());
            assertFalse(pluginLoader.isClosed());
            assertThrows(
                    ClassNotFoundException.class,
                    () ->
                            Class.forName(
                                    "top.focess.veto.plugin.runtime.PluginServiceRegistry",
                                    false,
                                    pluginLoader));
        } finally {
            installed.close();
        }
        assertTrue(pluginLoader.isClosed());
    }

    @Test
    void absentRootIsValidAndMalformedPackageFails(@TempDir @NonNull Path root) throws Exception {
        var loader =
                new InstalledPluginLoader(
                        Path.of(""), Duration.ofSeconds(5), false, ScriptExecutionMode.TRUSTED);
        assertTrue(loader.load(root.resolve("absent")).isEmpty());
        Files.createDirectory(root.resolve("broken"));
        assertThrows(IOException.class, () -> loader.load(root));
    }

    @Test
    void disabledPackageIsInventoriedWithoutOpeningItsArtifact(@TempDir @NonNull Path root)
            throws Exception {
        Path directory = Files.createDirectory(root.resolve("disabled"));
        writeJavaPackage(directory);
        Files.delete(directory.resolve("plugin.jar"));
        var loader =
                new InstalledPluginLoader(
                        Path.of(""), Duration.ofSeconds(5), false, ScriptExecutionMode.TRUSTED);
        var discovery = loader.discover(root, Set.of("sample.install"));
        assertTrue(discovery.plugins().isEmpty());
        assertEquals("sample.install", discovery.disabled().getFirst().id());
        assertThrows(IOException.class, () -> loader.load(root));
    }

    private static void writeJavaPackage(@NonNull Path directory) throws IOException {
        Files.writeString(
                directory.resolve("plugin.json"),
                """
                {
                  "schemaVersion": 1,
                  "id": "sample.install",
                  "name": "Installed Sample",
                  "version": "1.0.0",
                  "type": "java",
                  "entryPoint": "top.focess.veto.plugin.runtime.InstalledSamplePlugin",
                  "artifact": "plugin.jar"
                }
                """);
        String className = "top/focess/veto/plugin/runtime/InstalledSamplePlugin.class";
        try (var stream =
                        InstalledSamplePlugin.class.getResourceAsStream(
                                "InstalledSamplePlugin.class");
                var jar =
                        new JarOutputStream(
                                Files.newOutputStream(directory.resolve("plugin.jar")))) {
            if (stream == null) throw new IOException("Sample plugin class is unavailable");
            jar.putNextEntry(new JarEntry(className));
            stream.transferTo(jar);
            jar.closeEntry();
        }
    }
}
