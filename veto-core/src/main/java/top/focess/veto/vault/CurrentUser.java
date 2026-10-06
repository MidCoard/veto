package top.focess.veto.vault;

import java.util.UUID;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads Veto's canonical UUID from Spring Security without storing separate identity state. */
public final class CurrentUser {
    private CurrentUser() {}

    /** Returns the authenticated UUID, or null for anonymous or unsupported principals. */
    public static UUID id() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                        && !(authentication instanceof AnonymousAuthenticationToken)
                        && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof UUID userId
                ? userId
                : null;
    }
}
