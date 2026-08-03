package top.focess.veto.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import top.focess.veto.vault.SessionManager;
import top.focess.veto.vault.UserContext;

/**
 * Interceptor that intercepts all incoming REST API requests to resolve the authenticated user from
 * the "X-Veto-Session-Token" header and sets it in the thread-local {@link UserContext}.
 */
@Component
public class SecurityContextInterceptor implements HandlerInterceptor {

    private final @NonNull SessionManager sessionManager;

    public SecurityContextInterceptor(@NonNull SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler) {
        String token = request.getHeader("X-Veto-Session-Token");
        if (token != null) {
            sessionManager
                    .validate(token)
                    .ifPresent(
                            session -> {
                                UserContext.set(session.username());
                            });
        }
        return true;
    }

    @Override
    public void afterCompletion(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler,
            @Nullable Exception ex) {
        UserContext.clear();
    }
}
