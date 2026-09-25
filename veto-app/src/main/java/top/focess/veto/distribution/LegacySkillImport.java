package top.focess.veto.distribution;

import java.nio.file.Path;
import java.util.Locale;
import javax.sql.DataSource;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.builtin.skills.SkillRuntime;
import top.focess.veto.integration.plugins.PluginManager;

/** Read-only upgrade import; legacy hash rows remain intact as an audit/rollback record. */
@Component
public final class LegacySkillImport implements ApplicationRunner {
    private final @NonNull DataSource database;
    private final @NonNull PluginManager plugins;

    /** Creates the import with its database and plugin-manager collaborators. */
    public LegacySkillImport(@NonNull DataSource database, @NonNull PluginManager plugins) {
        this.database = database;
        this.plugins = plugins;
    }

    /** Re-anchors legacy skill hash rows onto builtin without altering the backup table. */
    public void run(ApplicationArguments arguments) throws Exception {
        BuiltinPlugin builtin = null;
        for (var plugin : plugins.plugins())
            if (plugin.implementation() instanceof BuiltinPlugin value) builtin = value;
        if (builtin == null) return;
        try (var connection = database.getConnection()) {
            boolean exists = false;
            try (var tables =
                    connection.getMetaData().getTables(null, null, "%", new String[] {"TABLE"})) {
                while (tables.next())
                    if ("skill_hashes".equalsIgnoreCase(tables.getString("TABLE_NAME")))
                        exists = true;
            }
            if (!exists) return;
            try (var query =
                            connection.prepareStatement(
                                    "SELECT skill_directory, content_hash FROM skill_hashes");
                    var rows = query.executeQuery()) {
                while (rows.next()) {
                    String directory = rows.getString(1), hash = rows.getString(2);
                    if (directory == null || hash == null || !hash.matches("[a-fA-F0-9]{64}"))
                        throw new IllegalStateException(
                                "Invalid legacy skill anchor; source retained");
                    String path =
                            Path.of(directory)
                                    .toAbsolutePath()
                                    .normalize()
                                    .resolve("SKILL.md")
                                    .toString();
                    if (System.getProperty("os.name", "").startsWith("Windows"))
                        path = path.toLowerCase(Locale.ROOT);
                    String identity = SkillRuntime.hash(path);
                    builtin.skillRuntime().importLegacy(identity, hash);
                }
            }
        }
    }
}
