package top.focess.veto.i18n;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Lookup for user-facing messages (API error strings) keyed off the request's locale.
 *
 * <p>Static rather than a Spring bean: the lookup is needed deep in services ({@code
 * DefaultModelTierService}, {@code SessionService}) that unit tests construct directly, so
 * constructor wiring would force every such test to import the bean. The bundle is read straight
 * from the classpath (basename {@code messages}), which behaves identically in tests and
 * production.
 *
 * <p>The locale comes from {@link LocaleContextHolder}: on request threads it is the {@code
 * Accept-Language} value resolved by the {@code localeResolver} bean ({@link I18nConfig}); on
 * non-request threads (IPC handlers, agent virtual threads, tests) no locale is set and the message
 * falls back to English - the base bundle.
 */
public final class Msg {

    private static final @NonNull MessageSource MESSAGES = createMessageSource();

    private Msg() {}

    /**
     * Renders {@code code} with {@code args} in the current thread's locale, falling back to the
     * English default when no locale is bound to the thread or the key is unknown (then the code
     * itself is returned rather than throwing).
     */
    public static @NonNull String get(@NonNull String code, @Nullable Object... args) {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale == null) {
            // No request locale on this thread (agent/IPC/test) - default to English.
            locale = Locale.ENGLISH;
        }
        return MESSAGES.getMessage(code, args, locale);
    }

    /**
     * Variant with an explicit locale - for agent/virtual-thread code that carries the session's
     * locale (captured on the request thread at submit time) because {@link LocaleContextHolder} is
     * empty off the request thread.
     */
    public static @NonNull String get(
            @NonNull Locale locale, @NonNull String code, @Nullable Object... args) {
        return MESSAGES.getMessage(code, args, locale);
    }

    private static @NonNull MessageSource createMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        // Never fall back to the JVM host locale (a zh host would leak Chinese into the
        // English-default paths); a locale without a bundle falls back to the base bundle.
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.ENGLISH);
        source.setUseCodeAsDefaultMessage(true);
        return source;
    }
}
