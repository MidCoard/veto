package top.focess.veto.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jetbrains.annotations.NotNull;
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

    private final SessionManager sessionManager;

    public SecurityContextInterceptor(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public boolean preHandle(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull Object handler) {
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
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull Object handler,
            Exception ex) {
        UserContext.clear();
    }
}
