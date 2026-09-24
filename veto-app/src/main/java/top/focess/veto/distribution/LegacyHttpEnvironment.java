package top.focess.veto.distribution;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Translates old fetch policy before host beans are constructed; new settings take precedence. */
public final class LegacyHttpEnvironment implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(
            @NonNull ConfigurableEnvironment environment, @NonNull SpringApplication application) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (String key :
                new String[] {"timeout-seconds", "max-chars", "allow-private-addresses"}) {
            String value = environment.getProperty("veto.webfetch.fetch." + key);
            if (value != null) defaults.put("veto.http." + key, value);
        }
        if (!defaults.isEmpty())
            environment
                    .getPropertySources()
                    .addLast(new MapPropertySource("legacy-reader-http", defaults));
    }
}
