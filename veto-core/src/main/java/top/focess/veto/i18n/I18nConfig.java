package top.focess.veto.i18n;

import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * Request-locale configuration: the {@code Accept-Language} header selects the locale used for
 * user-facing messages (see {@link Msg}). Absent or unsupported values fall back to English. The
 * DispatcherServlet binds the resolved locale to {@link
 * org.springframework.context.i18n.LocaleContextHolder} for the duration of the request.
 */
@Configuration
public class I18nConfig {

    @Bean
    public @NonNull LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE));
        return resolver;
    }
}
