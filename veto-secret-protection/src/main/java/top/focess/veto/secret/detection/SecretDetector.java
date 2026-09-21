package top.focess.veto.secret.detection;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Secret-span detection shared by SECRET_REF capture and observation masking. */
public interface SecretDetector {
    /** Capture-worthy secret spans: credential classes only (the SECRET_REF candidate source). */
    @NonNull List<SecretMasker.SecretMatch> detect(@NonNull String text);

    /** All sensitive spans, including mask-only categories. */
    @NonNull List<SecretMasker.SecretMatch> detectAll(@NonNull String text);

    /** Masks all detected spans; categories become {@code [REDACTED_*]} markers. */
    default @NonNull String mask(@NonNull String text) {
        return SecretMasker.mask(text, detectAll(text));
    }

    /** The deterministic detector: fixed patterns for both capture and masking. */
    static @NonNull SecretDetector deterministic() {
        return new SecretDetector() {
            @Override
            public @NonNull List<SecretMasker.SecretMatch> detect(@NonNull String text) {
                return SecretMasker.credentialMatches(text);
            }

            @Override
            public @NonNull List<SecretMasker.SecretMatch> detectAll(@NonNull String text) {
                return SecretMasker.matches(text);
            }
        };
    }
}
