package top.focess.veto.i18n;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Exercises {@link Msg}: the request-thread overload (via {@link LocaleContextHolder}) and the
 * explicit-locale overload used by agent-thread code, plus the English fallback for unset /
 * unsupported locales.
 */
class MsgTest {

    @Test
    void explicitLocaleSelectsBundle() {
        assertEquals(
                "Pattern already exists: ops",
                Msg.get(Locale.ENGLISH, "error.pattern.duplicate", "ops"));
        assertEquals(
                "模式 'ops' 已存在",
                Msg.get(Locale.SIMPLIFIED_CHINESE, "error.pattern.duplicate", "ops"));
    }

    @Test
    void unsupportedLocaleFallsBackToEnglishBaseBundle() {
        assertEquals("Nothing to compact.", Msg.get(Locale.GERMAN, "error.agent.compactNothing"));
    }

    @Test
    void unsetThreadLocaleFallsBackToEnglish() {
        LocaleContextHolder.resetLocaleContext();
        try {
            assertEquals("Not logged in", Msg.get("error.auth.notLoggedIn"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void threadLocaleIsHonoredWhenSet() {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        try {
            assertEquals("未登录", Msg.get("error.auth.notLoggedIn"));
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void messageFormatParamsInterpolate() {
        assertEquals(
                "Tier MID in profile 'default' is incomplete: provider, model, and credKey are"
                        + " required - set them in Settings > Model tiers (or /modeltier set"
                        + " default MID <field> <value>).",
                Msg.get(Locale.ENGLISH, "error.tier.incomplete", "MID", "default"));
    }
}
