package top.focess.veto.secret.detection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import top.focess.veto.secret.api.SecretDetectionModel;
import top.focess.veto.secret.references.SecretCandidateStore;

/**
 * Sensitive-data masking (moved from veto-core's removed SemanticRedactor) and the
 * capture-worthy/mask-only category split.
 */
@SuppressWarnings("nullness") // Cross-module class literals read as nullable.
class SensitiveDataMaskingTest {
    private final @NonNull SecretDetector detector = SecretDetector.deterministic();

    @Test
    void masksIpAddressesEmailsAndInternalHosts() {
        String masked =
                detector.mask(
                        "Server 192.168.1.100, ipv6 2001:0db8:85a3:0000:0000:8a2e:0370:7334,"
                                + " contact dev@example.external.com, host server.internal.example.com");
        assertTrue(masked.contains("[REDACTED_IP]"), masked);
        assertTrue(masked.contains("[REDACTED_IPV6]"), masked);
        assertTrue(masked.contains("[REDACTED_EMAIL]"), masked);
        assertTrue(masked.contains("[REDACTED_INTERNAL_HOST]"), masked);
        assertFalse(masked.contains("192.168.1.100"));
        assertFalse(masked.contains("dev@example.external.com"));
    }

    @Test
    void masksSshKeysDbUrlsAuthUrlsAndCredentialPaths() {
        String masked =
                detector.mask(
                        "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\n-----END RSA PRIVATE KEY-----");
        assertEquals("[REDACTED_PRIVATE_KEY]", masked);

        String dbMasked = detector.mask("connect jdbc:postgresql://user:pass@db.internal/db");
        assertTrue(dbMasked.contains("[REDACTED_DB_URL]"), dbMasked);
        assertFalse(dbMasked.contains("pass"));

        String urlMasked = detector.mask("fetch https://user:pass@example.com/x");
        assertFalse(urlMasked.contains("user:pass"), urlMasked);

        for (String path :
                new String[] {
                    "~/.ssh/id_ed25519",
                    "/home/alice/.aws/credentials",
                    "C:\\Users\\alice\\.ssh\\id_rsa",
                    "credentials/service-account.json",
                    "/etc/passwd"
                }) {
            assertEquals(
                    "[REDACTED_CREDENTIAL_PATH]", detector.mask(path), "credential path: " + path);
        }
    }

    @Test
    void masksProprietaryPhysicsParameters() {
        String masked = detector.mask("norm_max: 0.815 peak_min = -1.2");
        assertTrue(masked.contains("[REDACTED_PROPRIETARY_PARAM]"), masked);
        assertFalse(masked.contains("0.815"));
    }

    @Test
    void leavesCleanDataAndCredentialVocabularyAlone() {
        assertEquals(
                "Hello world, this is safe data.",
                detector.mask("Hello world, this is safe data."));
        String vocabulary =
                "Summarize this harmless sentence without revealing any credentials. "
                        + "Rotate credentials, secrets, keys, and passwords regularly.";
        assertEquals(vocabulary, detector.mask(vocabulary));
    }

    @Test
    void captureExcludesMaskOnlyCategories() {
        var store = new SecretCandidateStore();
        var scope = new SecretCandidateStore.Scope("owner", "session", "agent");
        var captured =
                store.capture(
                        scope,
                        "source",
                        "IP 10.0.0.50 email admin@internal.corp password=hunter2x ends");
        // Only the credential-class password becomes a SECRET_REF candidate.
        assertEquals(1, captured.candidates().size());
        assertTrue(captured.text().contains("[SECRET_REF:s_"), captured.text());
        // Mask-only data stays in the captured text; masking (not capture) covers it.
        assertTrue(captured.text().contains("10.0.0.50"), captured.text());
        assertTrue(captured.text().contains("admin@internal.corp"), captured.text());
        String masked = detector.mask("IP 10.0.0.50 email admin@internal.corp ends");
        assertFalse(masked.contains("10.0.0.50"));
        assertFalse(masked.contains("admin@internal.corp"));
    }

    @Test
    void slmSpansUnionWithTheDeterministicRules() {
        SecretDetectionModel model =
                new SecretDetectionModel() {
                    @Override
                    public boolean isAvailable() {
                        return true;
                    }

                    @Override
                    public @NonNull Optional<String> complete(
                            @NonNull String source, @NonNull Map<String, ?> data) {
                        return Optional.of("[\"wEk7-qR9\"]");
                    }
                };
        var detector = new SlmSecretDetector(model);
        String text = "password=hunter2x and wEk7-qR9";
        String masked = detector.mask(text);
        // Deterministic credential span and SLM span both masked; deterministic category wins.
        assertTrue(masked.contains("[REDACTED_PASSWORD]"), masked);
        assertTrue(masked.contains("[REDACTED_SLM_DETECTED]"), masked);
        assertFalse(masked.contains("hunter2x"));
        assertFalse(masked.contains("wEk7-qR9"));
        // Capture view unions the credential rules with the model's slm-detected spans.
        var captureSpans = detector.detect(text);
        assertEquals(2, captureSpans.size());
        assertTrue(captureSpans.stream().allMatch(m -> SecretMasker.captureWorthy(m.category())));
        // Mask-only sensitive categories still apply while the model is available.
        String maskedIp = detector.mask("call 10.0.0.50 now");
        assertTrue(maskedIp.contains("[REDACTED_IP]"), maskedIp);
    }
}
