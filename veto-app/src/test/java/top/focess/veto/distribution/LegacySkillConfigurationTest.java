package top.focess.veto.distribution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import top.focess.veto.integration.plugins.PluginConfigurations;

class LegacySkillConfigurationTest {
    @Test
    void explicitPluginRootsAndProjectSelectorWin() {
        var configuration = new PluginConfigurations();
        configuration.setCatalogueRoots(
                Map.of("top.focess.builtin", Map.of("personal", "explicit")));
        configuration.setConfiguration(
                Map.of("top.focess.builtin", Map.of("skills-project-directory", ".custom/skills")));
        new LegacySkillConfiguration(
                        new MockEnvironment().withProperty("veto.skills.project-dir", "old"))
                .postProcessBeforeInitialization(configuration, "configuration");
        var roots = configuration.getCatalogueRoots().get("top.focess.builtin");
        var values = configuration.getConfiguration().get("top.focess.builtin");
        if (roots == null || values == null)
            throw new AssertionError("Builtin configuration missing");
        assertEquals("explicit", roots.get("personal"));
        assertEquals(".custom/skills", values.get("skills-project-directory"));
    }
}
