package top.focess.veto.plugin.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;

/** Loads one manifest-based package per immediate subdirectory of an installation root. */
public final class InstalledPluginLoader {
    /** Manifest-only identity for a package disabled before code loading. */
    public record DisabledPackage(
            @NonNull String id, @NonNull String name, @NonNull String version) {}

    /** Loadable package descriptors and disabled package metadata from one directory scan. */
    public record Discovery(
            @NonNull List<InstalledPlugin> plugins, @NonNull List<DisabledPackage> disabled) {}

    private final @NonNull Path node;
    private final @NonNull Duration timeout;
    private final boolean trustedCode;
    private final @NonNull ScriptExecutionMode scriptMode;

    /** Creates a loader for independently installed Java and script packages. */
    public InstalledPluginLoader(
            @NonNull Path node,
            @NonNull Duration timeout,
            boolean trustedCode,
            @NonNull ScriptExecutionMode scriptMode) {
        this.node = node;
        this.timeout = timeout;
        this.trustedCode = trustedCode;
        this.scriptMode = scriptMode;
    }

    /** Returns packages in directory-name order; an absent installation root is empty. */
    public @NonNull List<InstalledPlugin> load(@NonNull Path installationRoot) throws IOException {
        return discover(installationRoot, Set.of()).plugins();
    }

    /** Loads only the installed package with the requested manifest identity. */
    public @NonNull InstalledPlugin loadById(@NonNull Path installationRoot, @NonNull String id)
            throws IOException {
        if (Files.isSymbolicLink(installationRoot) || !Files.isDirectory(installationRoot))
            throw new IOException("Plugin installation root is unavailable");
        try (var children = Files.list(installationRoot)) {
            for (Path directory : children.sorted().toList()) {
                if (isActivationState(directory)) continue;
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))
                    throw new IOException(
                            "Plugin installation root contains a non-directory entry");
                JsonNode manifest = readManifest(directory);
                if (id.equals(ScriptPlugin.text(manifest, "id")))
                    return loadPackage(directory, manifest);
            }
        }
        throw new IOException("Installed plugin identity was not found");
    }

    /** Scans installed packages, never constructing the entry class for a disabled ID. */
    public @NonNull Discovery discover(
            @NonNull Path installationRoot, @NonNull Set<String> disabledIds) throws IOException {
        if (!Files.exists(installationRoot, LinkOption.NOFOLLOW_LINKS))
            return new Discovery(List.of(), List.of());
        if (Files.isSymbolicLink(installationRoot) || !Files.isDirectory(installationRoot))
            throw new IOException("Plugin installation root must be a directory");
        List<Path> directories;
        try (var children = Files.list(installationRoot)) {
            directories =
                    children.filter(path -> !isActivationState(path))
                            .sorted(Comparator.comparing(Path::toString))
                            .toList();
        }
        if (directories.size() > 64) throw new IOException("Too many installed plugin packages");
        List<InstalledPlugin> loaded = new ArrayList<>();
        List<DisabledPackage> disabled = new ArrayList<>();
        try {
            for (Path directory : directories) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory))
                    throw new IOException(
                            "Plugin installation root contains a non-directory entry");
                JsonNode manifest = readManifest(directory);
                String id = ScriptPlugin.text(manifest, "id");
                if (disabledIds.contains(id)) {
                    String version = ScriptPlugin.text(manifest, "version");
                    PluginIdentity identity = new PluginIdentity(id, version);
                    String name = ScriptPlugin.text(manifest, "name");
                    if (name.isBlank() || name.length() > 128)
                        throw new IOException("Invalid plugin name");
                    disabled.add(new DisabledPackage(identity.id(), name, identity.version()));
                } else loaded.add(loadPackage(directory, manifest));
            }
            return new Discovery(List.copyOf(loaded), List.copyOf(disabled));
        } catch (IOException | RuntimeException failure) {
            for (InstalledPlugin plugin : loaded.reversed()) {
                try {
                    plugin.close();
                } catch (RuntimeException ignored) {
                    // The original package failure remains the startup diagnosis.
                }
            }
            throw failure;
        }
    }

    private static boolean isActivationState(@NonNull Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString();
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && (name.equals(".plugin-activation")
                        || (name.startsWith(".plugin-activation-") && name.endsWith(".tmp")));
    }

    private static @NonNull JsonNode readManifest(@NonNull Path directory) throws IOException {
        Path manifestFile = directory.resolve("plugin.json");
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Installed plugin has no plugin.json");
        return ScriptPlugin.parse(ScriptPlugin.readFile(manifestFile));
    }

    private @NonNull InstalledPlugin loadPackage(
            @NonNull Path directory, @NonNull JsonNode manifest) throws IOException {
        String type = ScriptPlugin.text(manifest, "type");
        return switch (type) {
            case "java" -> loadJava(directory, manifest);
            case "script" -> loadScript(directory, manifest);
            default -> throw new IOException("Unsupported plugin package type");
        };
    }

    private @NonNull InstalledPlugin loadScript(@NonNull Path directory, @NonNull JsonNode manifest)
            throws IOException {
        scriptMode.requireAvailable(trustedCode);
        String name = ScriptPlugin.text(manifest, "name");
        if (name.isBlank() || name.length() > 128) throw new IOException("Invalid plugin name");
        return new InstalledPlugin(new ScriptPluginLoader(node, timeout).load(directory));
    }

    private @NonNull InstalledPlugin loadJava(@NonNull Path directory, @NonNull JsonNode manifest)
            throws IOException {
        PluginSchema.fields(
                manifest,
                Set.of("schemaVersion", "id", "name", "version", "type", "entryPoint", "artifact"));
        PluginSchema.require(
                manifest.path("schemaVersion").isIntegralNumber()
                        && manifest.path("schemaVersion").canConvertToInt()
                        && manifest.path("schemaVersion").asInt() == 1);
        String id = ScriptPlugin.text(manifest, "id");
        String version = ScriptPlugin.text(manifest, "version");
        PluginIdentity identity = new PluginIdentity(id, version);
        String name = ScriptPlugin.text(manifest, "name");
        if (name.isBlank() || name.length() > 128) throw new IOException("Invalid plugin name");
        String entryPoint = ScriptPlugin.text(manifest, "entryPoint");
        if (!entryPoint.matches("[a-zA-Z_$][a-zA-Z0-9_$]*(?:\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+"))
            throw new IOException("Invalid Java plugin entry point");
        String artifact = ScriptPlugin.text(manifest, "artifact");
        if (!artifact.matches("[a-zA-Z0-9_-][a-zA-Z0-9._-]*\\.jar"))
            throw new IOException("Invalid Java plugin artifact");
        List<URL> urls = new ArrayList<>();
        urls.add(jarUrl(directory.resolve(artifact)));
        Path libraries = directory.resolve("lib");
        if (Files.exists(libraries, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(libraries) || !Files.isDirectory(libraries))
                throw new IOException("Plugin lib must be a directory");
            try (var files = Files.list(libraries)) {
                for (Path file : files.sorted().toList()) urls.add(jarUrl(file));
            }
        }
        var loader = new PluginClassLoader(id, urls);
        try {
            Class<?> implementation = Class.forName(entryPoint, false, loader);
            if (!VetoPlugin.class.isAssignableFrom(implementation))
                throw new IOException("Java entry point must implement VetoPlugin");
            Constructor<? extends VetoPlugin> constructor =
                    implementation
                            .asSubclass(VetoPlugin.class)
                            .getConstructor(PluginContext.class, JsonValue.ObjectValue.class);
            return new InstalledPlugin(identity, name, constructor, loader);
        } catch (ReflectiveOperationException | LinkageError | IOException failure) {
            loader.close();
            throw new IOException("Java plugin loading failed: " + id, failure);
        }
    }

    private static @NonNull URL jarUrl(@NonNull Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toString().endsWith(".jar"))
            throw new IOException("Plugin library must be a regular JAR file");
        return file.toUri().toURL();
    }
}
