package top.focess.veto.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import top.focess.veto.vault.SessionManager;
import top.focess.veto.vault.UserRegistry;

/** Resolves Veto's opaque session token independently on each HTTP dispatch. */
final class SessionTokenAuthenticationFilter extends OncePerRequestFilter {
    private final @NonNull SessionManager sessions;
    private final @NonNull UserRegistry users;

    SessionTokenAuthenticationFilter(
            @NonNull SessionManager sessions, @NonNull UserRegistry users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain)
            throws ServletException, IOException {
        // Each HTTP dispatch authenticates independently on its current thread.
        SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
        try {
            String token = request.getHeader("X-Veto-Session-Token");
            String path = request.getServletPath();
            if ((token == null || token.isBlank())
                    && ("/ws/veto/bus".equals(path) || path.startsWith("/ws/veto/bus/"))) {
                // Browser WebSocket APIs cannot set the custom token header.
                token = request.getParameter("token");
            }
            if (token != null && !token.isBlank()) {
                var session = sessions.validate(token).orElse(null);
                if (session != null) {
                    var user = users.findByUserId(session.userId()).orElse(null);
                    if (user != null) {
                        var authentication =
                                UsernamePasswordAuthenticationToken.authenticated(
                                        session.userId(),
                                        "",
                                        List.of(
                                                new SimpleGrantedAuthority(
                                                        "ROLE_" + user.getRole())));
                        authentication.eraseCredentials();
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(authentication);
                        SecurityContextHolder.setContext(context);
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
