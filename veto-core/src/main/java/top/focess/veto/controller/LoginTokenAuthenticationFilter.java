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
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.UserRegistry;

/** Resolves Veto's opaque login token independently on each HTTP dispatch. */
final class LoginTokenAuthenticationFilter extends OncePerRequestFilter {
    private final @NonNull LoginSessionManager loginSessions;
    private final @NonNull UserRegistry users;

    LoginTokenAuthenticationFilter(
            @NonNull LoginSessionManager loginSessions, @NonNull UserRegistry users) {
        this.loginSessions = loginSessions;
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
            if ((token == null || token.isBlank()) && "/ws/veto/bus".equals(path)) {
                // Browser WebSocket APIs cannot set the custom token header.
                token = request.getParameter("token");
            }
            if (token != null && !token.isBlank()) {
                var loginSession = loginSessions.validateToken(token).orElse(null);
                if (loginSession != null) {
                    var user = users.findByUserId(loginSession.userId()).orElse(null);
                    if (user != null) {
                        var authentication =
                                UsernamePasswordAuthenticationToken.authenticated(
                                        loginSession.userId(),
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
