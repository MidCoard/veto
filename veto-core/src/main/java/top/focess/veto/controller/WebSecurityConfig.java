package top.focess.veto.controller;

import jakarta.servlet.DispatcherType;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import top.focess.veto.vault.LoginSessionManager;
import top.focess.veto.vault.UserRegistry;

/** Stateless HTTP authentication using the Veto login-token protocol. */
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {
    @Bean
    public @NonNull SecurityFilterChain securityFilterChain(
            @NonNull HttpSecurity http,
            @NonNull LoginSessionManager loginSessions,
            @NonNull UserRegistry users)
            throws Exception {
        return http
                // Authentication is an explicit header (or WebSocket-only query), never a cookie.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(
                        context ->
                                context.securityContextRepository(
                                        new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, exception) ->
                                                        response.sendError(401))
                                        .accessDeniedHandler(
                                                (request, response, exception) ->
                                                        response.sendError(403)))
                .authorizeHttpRequests(
                        requests ->
                                requests.dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.POST,
                                                "/api/auth/setup",
                                                "/api/auth/login")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET,
                                                "/api/auth/status",
                                                "/api/system/info",
                                                "/actuator/health")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/auth/users",
                                                "/api/plugins",
                                                "/api/plugins/**",
                                                "/api/mcp/servers/**",
                                                "/api/v1/training/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers(
                                                "/api/**", "/ws/veto/bus", "/ws/veto/bus/**")
                                        .authenticated()
                                        .anyRequest()
                                        .denyAll())
                // Constructed only here: never also registered as a servlet-container filter.
                .addFilterBefore(
                        new LoginTokenAuthenticationFilter(loginSessions, users),
                        AnonymousAuthenticationFilter.class)
                .build();
    }
}
