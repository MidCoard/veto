package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import top.focess.veto.api.agent.tool.ToolDocs;
import top.focess.veto.builtin.BuiltinPlugin;
import top.focess.veto.builtin.skills.SkillRuntime;
import top.focess.veto.integration.plugins.PluginManager;
import top.focess.veto.plugin.runtime.ManagedPlugin;

class LegacySkillImportTest {
    @Test
    void readsOldAnchorsWithoutChangingBackup() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var sql = new JdbcTemplate(source);
        sql.execute(
                "CREATE TABLE skill_hashes (skill_directory VARCHAR(1024), content_hash VARCHAR(64))");
        String path = Path.of("legacy", "review").toAbsolutePath().normalize().toString();
        String hash = "a".repeat(64);
        sql.update("INSERT INTO skill_hashes VALUES (?,?)", path, hash);
        var manager = mock(PluginManager.class);
        var managed = mock(ManagedPlugin.class);
        var builtin = mock(ToolDocs.nonNullClass(BuiltinPlugin.class));
        var skills = mock(ToolDocs.nonNullClass(SkillRuntime.class));
        when(manager.plugins()).thenReturn(List.of(managed));
        when(managed.implementation()).thenReturn(builtin);
        when(builtin.skillRuntime()).thenReturn(skills);
        new LegacySkillImport(source, manager).run(new DefaultApplicationArguments());
        String normalized = Path.of(path).resolve("SKILL.md").toString();
        if (System.getProperty("os.name", "").startsWith("Windows"))
            normalized = normalized.toLowerCase(Locale.ROOT);
        verify(skills).importLegacy(SkillRuntime.hash(normalized), hash);
        var count = sql.queryForObject("SELECT COUNT(*) FROM skill_hashes", Integer.class);
        if (count == null) throw new AssertionError("Missing count result");
        assertEquals(1, count);
    }
}
