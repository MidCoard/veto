package top.focess.veto.plugin.runtime;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.PluginContext;
import top.focess.veto.api.plugin.PluginDeclinedException;
import top.focess.veto.api.plugin.PluginIdentity;
import top.focess.veto.api.plugin.VetoPlugin;
import top.focess.veto.api.plugin.contract.JsonValue;
import top.focess.veto.api.plugin.contract.PluginFailure;

/** Installed package metadata and owned loader, separate from its constructed plugin instance. */
public final class InstalledPlugin implements AutoCloseable {
    private final @NonNull PluginIdentity identity;
    private final @NonNull String displayName;
    private final Constructor<? extends VetoPlugin> constructor;
    private final PluginClassLoader loader;
    private final ScriptPlugin script;
    private boolean transferred;

    InstalledPlugin(
            @NonNull PluginIdentity identity,
            @NonNull String displayName,
            @NonNull Constructor<? extends VetoPlugin> constructor,
            @NonNull PluginClassLoader loader) {
        this.identity = identity;
        this.displayName = displayName;
        this.constructor = constructor;
        this.loader = loader;
        this.script = null;
    }

    InstalledPlugin(@NonNull ScriptPlugin script) {
        this.identity = script.identity();
        this.displayName = script.displayName();
        this.constructor = null;
        this.loader = null;
        this.script = script;
    }

    /** Stable identity read from the manifest before executing plugin code. */
    public @NonNull PluginIdentity identity() {
        return identity;
    }

    /** Human-readable name read from the manifest. */
    public @NonNull String displayName() {
        return displayName;
    }

    /** Constructs exactly one plugin instance using its bound context and configuration. */
    public @NonNull VetoPlugin create(
            @NonNull PluginContext context, JsonValue.@NonNull ObjectValue configuration)
            throws PluginFailure {
        if (transferred) throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
        ScriptPlugin installedScript = script;
        if (installedScript != null) {
            transferred = true;
            return installedScript;
        }
        Constructor<? extends VetoPlugin> entry = constructor;
        if (entry == null) throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
        try {
            VetoPlugin created = entry.newInstance(context, configuration);
            transferred = true;
            return created;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof PluginDeclinedException declined) throw declined;
            if (cause instanceof PluginFailure declared) throw declared;
            throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
        } catch (ReflectiveOperationException failure) {
            throw new PluginFailure(PluginFailure.Code.INTERNAL_FAILURE);
        }
    }

    /** Closes the package loader, or an untransferred script snapshot. */
    @Override
    public void close() {
        ScriptPlugin installedScript = script;
        if (installedScript != null && !transferred) installedScript.close();
        PluginClassLoader javaLoader = loader;
        if (javaLoader != null) {
            try {
                javaLoader.close();
            } catch (IOException failure) {
                throw new IllegalStateException("Plugin loader could not close", failure);
            }
        }
    }
}
