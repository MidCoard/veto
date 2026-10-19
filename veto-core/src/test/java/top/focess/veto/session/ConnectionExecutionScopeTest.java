package top.focess.veto.session;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import top.focess.veto.vault.CurrentUser;
import top.focess.veto.vault.TestUsers;

class ConnectionExecutionScopeTest {
    @Test
    void nestedFailureRestoresTheCallerIdentityAndLocale() {
        var previousSecurity = SecurityContextHolder.getContext();
        var previousLocale = LocaleContextHolder.getLocaleContext();
        try {
            SecurityContextHolder.clearContext();
            LocaleContextHolder.resetLocaleContext();
            var outer = new ConnectionExecutionScope(TestUsers.ALICE, Locale.CHINA);
            try (outer) {
                assertEquals(TestUsers.ALICE, CurrentUser.id());
                assertEquals(Locale.CHINA, LocaleContextHolder.getLocale());
                assertThrows(
                        IllegalStateException.class,
                        () -> {
                            var inner = new ConnectionExecutionScope(TestUsers.BOB, Locale.US);
                            try (inner) {
                                assertEquals(TestUsers.BOB, CurrentUser.id());
                                assertEquals(Locale.US, LocaleContextHolder.getLocale());
                                throw new IllegalStateException("Action failed");
                            }
                        });
                assertEquals(TestUsers.ALICE, CurrentUser.id());
                assertEquals(Locale.CHINA, LocaleContextHolder.getLocale());
            }
            assertNull(CurrentUser.id());
            assertNull(LocaleContextHolder.getLocaleContext());
        } finally {
            SecurityContextHolder.setContext(previousSecurity);
            LocaleContextHolder.setLocaleContext(previousLocale);
        }
    }
}
