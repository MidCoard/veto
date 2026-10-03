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
import org.springframework.test.util.ReflectionTestUtils;
import top.focess.veto.api.plugin.PluginState;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.StandardContributionPoints;
import top.focess.veto.api.plugin.contribution.ContributionId;
import top.focess.veto.api.plugin.contribution.ContributionPoint;
import top.focess.veto.api.plugin.contribution.PluginContributionsDirectory;
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
            assertEquals(PluginState.ACTIVE, manager.registry().plugin("sample.installed").state());
            assertEquals(1, manager.registry().declined().size());
            assertEquals("sample.declined", manager.registry().declined().getFirst().id());
            assertEquals(
                    "UNSUPPORTED_ENVIRONMENT",
                    manager.registry().declined().getFirst().reason().name());
            assertTrue(
                    manager.registry().declined().stream()
                            .anyMatch(plugin -> plugin.id().equals("sample.declined")));
            assertEquals(1, manager.registry().disabled().size());
            assertTrue(
                    manager.registry().disabled().stream()
                            .anyMatch(plugin -> plugin.id().equals("sample.disabled")));
            assertEquals(
                    "Installed service",
                    manager.registry().plugin("sample.installed").displayName());
            var request = new JsonValue.StringValue("hello");
            assertEquals(
                    request,
                    manager.services().find("sample:echo", 1).orElseThrow().invoke(request));
        }
    }

    @Test
    void administratorChoiceTakesEffectOnlyAfterRestart(@TempDir @NonNull Path root)
            throws Exception {
        writePackage(Files.createDirectory(root.resolve("service-provider")));
        var choices = new PluginActivationStore();
        var configuration = new PluginConfigurations();
        configuration.setDisabled(Set.of("sample.installed"));
        try (var first =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        configuration,
                        choices)) {
            assertTrue(
                    first.registry().disabled().stream()
                            .anyMatch(plugin -> plugin.id().equals("sample.installed")));
            first.setEnabledOnNextStart("sample.installed", true);
            assertTrue(
                    first.registry().disabled().stream()
                            .anyMatch(plugin -> plugin.id().equals("sample.installed")));
            assertTrue(first.desiredEnabled("sample.installed"));
        }
        try (var second =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        configuration,
                        choices)) {
            assertEquals(PluginState.ACTIVE, second.registry().plugin("sample.installed").state());
            var service = second.services().find("sample:echo", 1).orElseThrow();
            second.setEnabledOnNextStart("sample.installed", false);
            assertFalse(second.desiredEnabled("sample.installed"));
            assertEquals(PluginState.ACTIVE, second.registry().plugin("sample.installed").state());
            assertEquals(
                    new JsonValue.StringValue("running"),
                    service.invoke(new JsonValue.StringValue("running")));
        }
        try (var third =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations(),
                        choices)) {
            assertTrue(
                    third.registry().disabled().stream()
                            .anyMatch(plugin -> plugin.id().equals("sample.installed")));
            third.setEnabledOnNextStart("sample.installed", true);
            assertTrue(
                    third.registry().disabled().stream()
                            .anyMatch(plugin -> plugin.id().equals("sample.installed")));
        }
        try (var fourth =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations(),
                        choices)) {
            assertEquals(PluginState.ACTIVE, fourth.registry().plugin("sample.installed").state());
        }
    }

    @Test
    void earlierPluginRegistersAtStartThroughLaterPluginsPoint(@TempDir @NonNull Path root)
            throws Exception {
        writePointPackage(
                Files.createDirectory(root.resolve("a-consumer")),
                "sample.consumer",
                "InstalledPointConsumerPlugin");
        writePointPackage(
                Files.createDirectory(root.resolve("z-point")),
                "sample.point",
                "InstalledPointPlugin");
        var point =
                new ContributionPoint<>(
                        new ContributionId("sample.point:shared"),
                        1,
                        JsonValue.ObjectValue.class,
                        ContributionPoint.Cardinality.MULTIPLE);
        try (var manager =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations())) {
            var entries = manager.registry().entries(point);
            assertEquals(1, entries.size());
            assertEquals("sample.consumer", entries.getFirst().source().namespace());
            manager.setEnabledOnNextStart("sample.point", false);
            assertFalse(manager.desiredEnabled("sample.point"));
            assertEquals(1, manager.registry().entries(point).size());
            PluginContributionsDirectory directory =
                    ReflectionTestUtils.invokeMethod(
                            manager,
                            "contributionsFor",
                            manager.registry().plugin("sample.consumer"));
            if (directory == null) throw new AssertionError("Missing contribution directory");
            var visible = directory.entries(point.id(), 1);
            assertEquals(1, visible.size());
            assertSame(visible.getFirst(), directory.entries(point.id(), 1).getFirst());
            assertTrue(directory.entries(point.id(), 2).isEmpty());
            manager.registry().plugin("sample.point").close();
            assertTrue(directory.entries(point.id(), 1).isEmpty());
        }
    }

    @Test
    void pendingActivationDoesNotChangePublishedPlugin(@TempDir @NonNull Path root)
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
            manager.setEnabledOnNextStart("sample.installed", false);
            assertEquals(PluginState.ACTIVE, manager.registry().plugin("sample.installed").state());
            assertFalse(
                    manager.registry().disabled().stream()
                            .anyMatch(plugin -> plugin.id().equals("sample.installed")));
            assertFalse(manager.desiredEnabled("sample.installed"));
            assertTrue(manager.services().find("sample:echo", 1).isPresent());
        }
    }

    @Test
    void activePluginCanRegisterServiceAndPendingDisableKeepsIt(@TempDir @NonNull Path root)
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
            assertTrue(manager.services().find("sample:late", 1).isEmpty());
            var request = new JsonValue.StringValue("register");
            assertEquals(
                    request,
                    manager.services().find("sample:echo", 1).orElseThrow().invoke(request));
            assertEquals(
                    new JsonValue.StringValue("late"),
                    manager.services()
                            .find("sample:late", 1)
                            .orElseThrow()
                            .invoke(new JsonValue.StringValue("late")));
            manager.setEnabledOnNextStart("sample.installed", false);
            assertTrue(manager.services().find("sample:late", 1).isPresent());
        }
    }

    @Test
    void desiredDisableDoesNotCloseRunningLoader(@TempDir @NonNull Path root) throws Exception {
        writePackage(Files.createDirectory(root.resolve("service-provider")));
        PluginClassLoader loader;
        try (var manager =
                new PluginManager(
                        root.toString(),
                        "",
                        false,
                        5000,
                        PluginTestSupport.providerOf(PluginTestSupport.configurationServices(null)),
                        new PluginConfigurations())) {
            ClassLoader candidate =
                    manager.registry()
                            .entries(StandardContributionPoints.SERVICES)
                            .getFirst()
                            .implementation()
                            .getClass()
                            .getClassLoader();
            if (!(candidate instanceof PluginClassLoader pluginLoader))
                throw new AssertionError("Service did not load in its plugin classloader");
            loader = pluginLoader;
            manager.setEnabledOnNextStart("sample.installed", false);
            assertFalse(loader.isClosed());
            assertEquals(PluginState.ACTIVE, manager.registry().plugin("sample.installed").state());
        }
        assertTrue(loader.isClosed());
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

    private void writePointPackage(
            @NonNull Path directory, @NonNull String id, @NonNull String entry) throws IOException {
        Files.writeString(
                directory.resolve("plugin.json"),
                """
                {"schemaVersion":1,"id":"%s","name":"%s","version":"1.0.0","type":"java","entryPoint":"top.focess.veto.integration.plugins.%s","artifact":"plugin.jar"}
                """
                        .formatted(id, id, entry));
        writeJar(directory, entry + ".class");
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
                    java.util.List.of(
                            resource,
                            resource.replace(".class", "$1.class"),
                            resource.replace(".class", "$1$1.class"))) {
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
