package top.focess.veto.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
    public void extendMessageConverters(@NonNull List<HttpMessageConverter<?>> converters) {
        for (HttpMessageConverter<?> converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter json) {
                // Keep strict HTTP input rules separate from the tolerant LLM mapper.
                var mapper = json.getObjectMapper().copy();
                mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                mapper.coercionConfigFor(LogicalType.Textual)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                json.setObjectMapper(mapper);
            }
        }
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
