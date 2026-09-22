package top.focess.veto.plugin.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarFile;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import top.focess.veto.plugin.contract.*;
import top.focess.veto.plugin.contribution.*;

/** Package-level SPI proof, deliberately not a production package validator or loader. */
class PackagedPluginTest {
    private static <T> @NonNull T require(@Nullable T value) {
        if (value == null) throw new AssertionError("Required test value missing");
        return value;
    }

    private static final Path PACKAGE = Path.of(require(System.getProperty("fixture.package")));
    private static final PluginContext CONTEXT =
            new PluginContext(new PluginIdentity("top.focess.fixture", "0.1.0"));
    private static final JsonValue.ObjectValue EMPTY = new JsonValue.ObjectValue(Map.of());

    /** Parent exports the real defining SPI loader, never the application/system classpath. */
    private static class ApiParent extends ClassLoader {
        ApiParent() {
            super(ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected @NonNull Class<?> loadClass(@NonNull String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("top.focess.veto.plugin.api.")
                    || name.startsWith("top.focess.veto.plugin.contribution.")
                    || name.startsWith("top.focess.veto.plugin.contract."))
                return Class.forName(name, false, VetoPlugin.class.getClassLoader());
            return super.loadClass(name, resolve);
        }
    }

    private static @NonNull URLClassLoader loader() throws Exception {
        return new URLClassLoader(
                new URL[] {PACKAGE.resolve("plugin.jar").toUri().toURL()}, new ApiParent());
    }

    private static @NonNull VetoPlugin instantiate(@NonNull URLClassLoader loader)
            throws Exception {
        JsonNode manifest =
                require(new ObjectMapper().readTree(PACKAGE.resolve("plugin.json").toFile()));
        assertEquals(VetoPlugin.API_VERSION, manifest.path("apiVersion").asInt());
        assertEquals("plugin.jar", manifest.path("artifact").asText());
        return loader.loadClass(require(manifest.path("entryPoint").asText()))
                .asSubclass(VetoPlugin.class)
                .getConstructor()
                .newInstance();
    }

    private static @NonNull ContributionCatalog catalog(
            @NonNull PluginContributions contributions) {
        var catalog =
                new ContributionCatalog.Builder()
                        .validateWith(StandardContributionPoints::validateToolCategories)
                        .define(StandardContributionPoints.TOOLS, tool -> {})
                        .define(StandardContributionPoints.CATEGORIES, category -> {})
                        .define(StandardContributionPoints.PROMPTS, prompt -> {})
                        .define(StandardContributionPoints.OBSERVATION, middleware -> {})
                        .stage(
                                new ContributionSource(
                                        "top.focess.fixture",
                                        "0.1.0",
                                        ContributionSource.Origin.PLUGIN),
                                contributions.entries())
                        .freeze();
        assertEquals(1, catalog.entries(StandardContributionPoints.CATEGORIES).size());
        assertEquals(1, catalog.entries(StandardContributionPoints.OBSERVATION).size());
        return catalog;
    }

    private static @NonNull Tool tool(@NonNull PluginContributions contributions) {
        return catalog(contributions)
                .entries(StandardContributionPoints.TOOLS)
                .getFirst()
                .implementation();
    }

    @Test
    void jarContainsOnlyFixtureCodeAndPackageResourcesExist() throws Exception {
        try (var jar = new JarFile(PACKAGE.resolve("plugin.jar").toFile())) {
            assertTrue(jar.stream().anyMatch(e -> e.getName().endsWith("FixturePlugin.class")));
            assertTrue(
                    jar.stream()
                            .filter(e -> e.getName().endsWith(".class"))
                            .allMatch(e -> e.getName().startsWith("top/focess/veto/fixture/")));
            assertNull(require(jar.getManifest()).getMainAttributes().getValue("Class-Path"));
        }
        assertTrue(Files.isRegularFile(PACKAGE.resolve("config.schema.json")));
        assertTrue(Files.isRegularFile(PACKAGE.resolve("prompts/fixture.md")));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("top.focess.veto.fixture.FixturePlugin"));
    }

    @Test
    void standaloneJarSharesApiButCannotSeeHostLibraries() throws Exception {
        try (var loader = loader();
                var plugin = instantiate(loader)) {
            assertSame(VetoPlugin.class, loader.loadClass(VetoPlugin.class.getName()));
            assertSame(loader, require(plugin.getClass().getClassLoader()));
            assertThrows(
                    ClassNotFoundException.class,
                    () -> loader.loadClass(ObjectMapper.class.getName()));
            var contributions = plugin.initialize(CONTEXT, EMPTY);
            var tool = tool(contributions);
            var middleware =
                    catalog(contributions)
                            .entries(StandardContributionPoints.OBSERVATION)
                            .getFirst()
                            .implementation();
            assertThrows(PluginFailure.class, () -> middleware.transform(" text ", () -> false));
            var arguments =
                    new JsonValue.ObjectValue(Map.of("text", new JsonValue.StringValue("A😀中")));
            assertEquals(
                    PluginFailure.Code.NOT_READY,
                    assertThrows(PluginFailure.class, () -> tool.invoke(arguments, () -> false))
                            .code());
            plugin.start();
            assertEquals("text", middleware.transform(" text ", () -> false));
            assertEquals(
                    new JsonValue.NumberValue(java.math.BigDecimal.valueOf(3)),
                    tool.invoke(arguments, () -> false));
            assertEquals(
                    PluginFailure.Code.CANCELLED,
                    assertThrows(PluginFailure.class, () -> tool.invoke(arguments, () -> true))
                            .code());
        }
    }

    @Test
    void failedStartupNeverMakesStagedHandlersCallableAndCleanupRevokesThem() throws Exception {
        try (var loader = loader()) {
            var plugin = instantiate(loader);
            var contributions =
                    plugin.initialize(
                            CONTEXT,
                            new JsonValue.ObjectValue(
                                    Map.of("failStart", new JsonValue.BooleanValue(true))));
            try {
                assertEquals(
                        PluginFailure.Code.INTERNAL_FAILURE,
                        assertThrows(PluginFailure.class, plugin::start).code());
                assertEquals(
                        PluginFailure.Code.NOT_READY,
                        assertThrows(
                                        PluginFailure.class,
                                        () -> tool(contributions).invoke(EMPTY, () -> false))
                                .code());
            } finally {
                plugin.close();
            }
            // Re-start rejection belongs to the host lifecycle tests, not plugin callbacks.
        }
    }

    @SuppressWarnings("try") // Explicit early close verifies retained-handler revocation.
    @Test
    void closeRevokesPreviouslyPublishedHandlerAndInstancesHaveSeparateState() throws Exception {
        try (var firstLoader = loader();
                var secondLoader = loader();
                var first = instantiate(firstLoader);
                var second = instantiate(secondLoader)) {
            assertNotSame(first.getClass(), second.getClass());
            var contributions = first.initialize(CONTEXT, EMPTY);
            first.start();
            first.close();
            var middleware =
                    catalog(contributions)
                            .entries(StandardContributionPoints.OBSERVATION)
                            .getFirst()
                            .implementation();
            assertEquals(
                    PluginFailure.Code.NOT_READY,
                    assertThrows(
                                    PluginFailure.class,
                                    () -> middleware.transform(" text ", () -> false))
                            .code());
            assertEquals(
                    PluginFailure.Code.NOT_READY,
                    assertThrows(
                                    PluginFailure.class,
                                    () -> tool(contributions).invoke(EMPTY, () -> false))
                            .code());
            second.initialize(CONTEXT, EMPTY);
            second.start();
        }
    }

    @Test
    void invalidConfigurationCanBeClosedAfterPartialStartup() throws Exception {
        try (var loader = loader();
                var plugin = instantiate(loader)) {
            assertEquals(
                    PluginFailure.Code.INVALID_CONFIGURATION,
                    assertThrows(
                                    PluginFailure.class,
                                    () ->
                                            plugin.initialize(
                                                    CONTEXT,
                                                    new JsonValue.ObjectValue(
                                                            Map.of(
                                                                    "typo",
                                                                    JsonValue.NullValue.INSTANCE))))
                            .code());
        }
    }
}
