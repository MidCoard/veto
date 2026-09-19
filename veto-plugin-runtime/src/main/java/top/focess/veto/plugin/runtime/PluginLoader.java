package top.focess.veto.plugin.runtime;

import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.NonNull;
import top.focess.veto.plugin.api.VetoPlugin;

/**
 * Loading prepares an inactive instance; initialization/start/publication are host
 * responsibilities.
 */
public interface PluginLoader<P extends VetoPlugin> {
    @NonNull P load(@NonNull Path directory) throws IOException;
}
