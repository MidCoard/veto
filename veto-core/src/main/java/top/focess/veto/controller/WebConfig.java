package top.focess.veto.controller;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration to register the {@link SecurityContextInterceptor} for all API endpoints.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final @NonNull SecurityContextInterceptor interceptor;

    public WebConfig(@NonNull SecurityContextInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "https://localhost:*",
                        "http://127.0.0.1:*",
                        "https://127.0.0.1:*",
                        "http://[::1]:*",
                        "https://[::1]:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept-Language", "X-Veto-Session-Token")
                .maxAge(3600);
    }
}
