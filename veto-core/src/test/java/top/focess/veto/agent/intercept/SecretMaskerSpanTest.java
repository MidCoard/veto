package top.focess.veto.agent.intercept;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SecretMaskerSpanTest {
    @Test
    void originalOffsetsSurviveUnicodeAndRepeatedAssignments() {
        String input = "😀 password = alpha-secret; token: beta-secret token=beta-secret";
        var matches = SecretMasker.matches(input);
        assertEquals(
                List.of("alpha-secret;", "beta-secret", "beta-secret"),
                matches.stream()
                        .map(match -> input.substring(match.start(), match.end()))
                        .toList());
        assertEquals(input.indexOf("alpha-secret"), matches.getFirst().start());
        assertFalse(matches.toString().contains("alpha-secret"));
    }

    @Test
    void privateKeyBlockDominatesNestedTokenWithoutChangingLegacyMasking() {
        String input =
                "prefix -----BEGIN PRIVATE KEY-----\nAKIA1234567890123456\n-----END PRIVATE KEY----- suffix";
        var matches = SecretMasker.matches(input);
        assertEquals(1, matches.size());
        assertEquals("[REDACTED_PRIVATE_KEY]", matches.getFirst().category());
        assertEquals(input.indexOf("-----BEGIN"), matches.getFirst().start());
        assertEquals(input.indexOf(" suffix"), matches.getFirst().end());
        assertEquals("prefix [REDACTED_PRIVATE_KEY] suffix", SecretMasker.mask(input));
    }

    @Test
    void genericAndGithubPatternsDoNotCreateOverlappingReferences() {
        String token = "ghp_" + "a1".repeat(18);
        String input = "token=" + token + " plain prose";
        var matches = SecretMasker.matches(input);
        assertEquals(1, matches.size());
        assertEquals(token, input.substring(matches.getFirst().start(), matches.getFirst().end()));
        assertTrue(SecretMasker.matches("ordinary words with no credentials").isEmpty());
    }
}
