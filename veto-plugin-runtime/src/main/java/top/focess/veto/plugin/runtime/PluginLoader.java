package top.focess.veto.plugin.runtime;

import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import top.focess.veto.api.plugin.VetoPlugin;

/**
 * Loading prepares an inactive instance; initialization/start/publication are host
 * responsibilities.
 */
public interface PluginLoader<P extends VetoPlugin> {
    /** Loads an inactive plugin instance from the given package directory. */
    @NonNull P load(@NonNull Path directory) throws IOException;
}
