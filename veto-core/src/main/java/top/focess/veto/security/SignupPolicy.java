package top.focess.veto.security;

import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads the deployer-configured signup policy ({@code veto.security.signup.mode}) and validates it
 * against the deployer policy at startup.
 *
 * <p>The only hard coupling is: {@code deployer-policy=TENANT} requires a multi-user signup mode
 * ({@code open} or {@code closed}). Every other deployer policy supports all four signup modes
 * (single- or multi-user).
 */
@Component
public class SignupPolicy {

    private static final Logger log = LoggerFactory.getLogger(SignupPolicy.class);

    private final @NonNull SignupMode mode;
    private final @NonNull String deployerPolicy;

    public SignupPolicy(
            @Value("${veto.security.signup.mode:solo}") @NonNull String modeRaw,
            @Value("${veto.security.deployer-policy:FULL_ACCESS}")
                    @NonNull String deployerPolicyRaw) {
        this.mode = parseMode(modeRaw);
        this.deployerPolicy =
                deployerPolicyRaw == null ? "" : deployerPolicyRaw.trim().toUpperCase();
    }

    /** The configured signup mode. */
    public @NonNull SignupMode mode() {
        return mode;
    }

    /**
     * Whether this deployment is multi-user (drives availability of {@code /user} admin command).
     */
    public boolean multiUser() {
        return mode.multiUser();
    }

    @PostConstruct
    void validate() {
        if ("TENANT".equals(deployerPolicy) && !mode.multiUser()) {
            throw new IllegalStateException(
                    "deployer-policy=TENANT requires a multi-user signup mode (public or invite), but "
                            + "veto.security.signup.mode was '"
                            + mode.name().toLowerCase()
                            + "'");
        }
        log.info(
                "Signup policy: mode={}, deployer-policy={}",
                mode.name().toLowerCase(),
                deployerPolicy);
    }

    private static @NonNull SignupMode parseMode(@NonNull String raw) {
        if (raw == null || raw.isBlank()) {
            return SignupMode.SOLO;
        }
        try {
            return SignupMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Unknown veto.security.signup.mode '"
                            + raw
                            + "'. Expected one of: solo, public, invite");
        }
    }
}
