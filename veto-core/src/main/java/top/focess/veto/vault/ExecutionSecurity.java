package top.focess.veto.vault;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/** Scopes trusted terminal and agent identities using Spring's sole context holder. */
public final class ExecutionSecurity implements AutoCloseable {
    private final @NonNull SecurityContext previous;

    private ExecutionSecurity(@NonNull SecurityContext context) {
        previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
    }

    /** Creates an owner context; domain execution does not grant HTTP administrator roles. */
    public static @NonNull SecurityContext contextFor(UUID userId) {
        var context = SecurityContextHolder.createEmptyContext();
        if (userId != null) {
            var authentication =
                    UsernamePasswordAuthenticationToken.authenticated(userId, "", List.of());
            authentication.eraseCredentials();
            context.setAuthentication(authentication);
        }
        return context;
    }

    /** Installs an explicitly validated owner until this same-thread scope is closed. */
    public static @NonNull ExecutionSecurity open(UUID userId) {
        return new ExecutionSecurity(contextFor(userId));
    }

    /** Restores the caller's complete context, or clears an originally empty context. */
    @Override
    public void close() {
        if (previous.getAuthentication() == null) SecurityContextHolder.clearContext();
        else SecurityContextHolder.setContext(previous);
    }
}
