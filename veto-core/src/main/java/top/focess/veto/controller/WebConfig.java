package top.focess.veto.controller;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
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
}
