package top.focess.veto.plugin.runtime;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.VetoPlugin;

/**
 * Private Java package loader. Its parent shares veto-api contracts, but does not expose another
 * installed plugin or the core implementation. Closing releases JAR handles after lifecycle drain;
 * actual class unloading also requires plugins to release their threads and all external
 * references.
 */
public final class PluginClassLoader extends URLClassLoader {
    private final @NonNull String pluginId;
    private final @NonNull AtomicBoolean closed = new AtomicBoolean();

    /** Creates one classloader for one installed plugin's artifact and private libraries. */
    public PluginClassLoader(@NonNull String pluginId, @NonNull List<URL> jars) {
        super(jars.toArray(URL[]::new), new ApiParent());
        this.pluginId = pluginId;
    }

    /** Stable identity of the package whose classes this loader owns. */
    public @NonNull String pluginId() {
        return pluginId;
    }

    /** Whether host lifecycle closure has released this loader's JAR resources. */
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    protected @NonNull Class<?> findClass(@NonNull String name) throws ClassNotFoundException {
        if (closed.get()) throw new ClassNotFoundException(name);
        return super.findClass(name);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) super.close();
    }

    private static final class ApiParent extends ClassLoader {
        ApiParent() {
            super(ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected @NonNull Class<?> loadClass(@NonNull String name, boolean resolve)
                throws ClassNotFoundException {
            if (name.startsWith("top.focess.veto.api.")
                    || name.startsWith("org.jspecify.")
                    || name.startsWith("com.fasterxml.jackson.")) {
                ClassLoader api = VetoPlugin.class.getClassLoader();
                if (api == null) throw new ClassNotFoundException(name);
                return api.loadClass(name);
            }
            return super.loadClass(name, resolve);
        }
    }
}
