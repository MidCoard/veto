package top.focess.veto.distribution;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.focess.veto.integration.plugins.PluginConfigurations;

/** Distribution-only aliases and explicit operator-shared catalogue grant. No filesystem scan. */
@Component
public final class LegacySkillConfiguration implements BeanPostProcessor, Ordered {
    private final @NonNull Environment environment;

    /** Creates the bridge reading the legacy skills properties from the given environment. */
    public LegacySkillConfiguration(@NonNull Environment environment) {
        this.environment = environment;
    }

    /** Runs last, after property binding and before consumers read the configuration bean. */
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /** Grants the personal skills catalogue root and maps the legacy project directory setting. */
    public @NonNull Object postProcessBeforeInitialization(
            @NonNull Object bean, @NonNull String name) {
        if (!(bean instanceof PluginConfigurations configuration)) return bean;
        var roots = new LinkedHashMap<>(configuration.getCatalogueRoots());
        Map<String, String> builtin = new LinkedHashMap<>();
        builtin.put(
                "personal",
                Path.of(System.getProperty("user.home", "."), ".veto", "skills").toString());
        builtin.putAll(roots.getOrDefault("top.focess.builtin", Map.of()));
        roots.put("top.focess.builtin", Map.copyOf(builtin));
        configuration.setCatalogueRoots(roots);
        String project = environment.getProperty("veto.skills.project-dir");
        if (project != null && !project.isBlank()) {
            var configurations = new LinkedHashMap<>(configuration.getConfiguration());
            var values =
                    new LinkedHashMap<>(
                            configurations.getOrDefault("top.focess.builtin", Map.of()));
            values.putIfAbsent("skills-project-directory", project);
            configurations.put("top.focess.builtin", Map.copyOf(values));
            configuration.setConfiguration(configurations);
        }
        return bean;
    }
}
