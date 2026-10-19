package top.focess.veto.session;

import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import top.focess.veto.vault.ExecutionSecurity;

/**
 * Installs a connection worker's identity and locale until the same thread closes this scope.
 * Thread-confined; nested scopes restore the caller's complete context, including an absent locale.
 */
final class ConnectionExecutionScope implements AutoCloseable {
    private final @NonNull ExecutionSecurity security;
    private final LocaleContext previousLocale;

    ConnectionExecutionScope(UUID userId, @NonNull Locale locale) {
        previousLocale = LocaleContextHolder.getLocaleContext();
        security = ExecutionSecurity.open(userId);
        LocaleContextHolder.setLocale(locale);
    }

    @Override
    public void close() {
        try {
            security.close();
        } finally {
            LocaleContextHolder.setLocaleContext(previousLocale);
        }
    }
}
