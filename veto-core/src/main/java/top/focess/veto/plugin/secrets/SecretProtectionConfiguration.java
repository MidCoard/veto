package top.focess.veto.plugin.secrets;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import top.focess.veto.secret.references.SecretCandidateStore;

/** Binds the standalone secret-protection module to Veto's application lifecycle. */
@Configuration(proxyBeanMethods = false)
public class SecretProtectionConfiguration {
    /** Shared across input capture, protected file reads, import, and session/auth lifecycle. */
    @Bean
    public @NonNull SecretCandidateStore secretCandidateStore() {
        return new SecretCandidateStore();
    }

    @Bean
    public @NonNull CandidateExpiry secretCandidateExpiry(
            @NonNull SecretCandidateStore candidates) {
        return new CandidateExpiry(candidates);
    }

    /** Scheduling belongs to the host; the module only owns expiration semantics. */
    public static final class CandidateExpiry {
        private final @NonNull SecretCandidateStore candidates;

        CandidateExpiry(@NonNull SecretCandidateStore candidates) {
            this.candidates = candidates;
        }

        @Scheduled(fixedDelay = 60_000)
        public void expire() {
            candidates.expire();
        }
    }
}
