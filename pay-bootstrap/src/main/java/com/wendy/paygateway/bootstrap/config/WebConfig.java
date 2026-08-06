package com.wendy.paygateway.bootstrap.config;

import com.wendy.paygateway.common.auth.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web layer wiring.
 *
 * <p>Deciding which paths require authentication is an application-wide orchestration choice that
 * belongs to no single business domain, so the interceptor registration lives in the bootstrap
 * module while the interceptor itself — a reusable capability — stays in pay-common.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/pay/**", "/api/refund/**")
                // Third-party webhooks authenticate via channel signature verification, not JWT
                .excludePathPatterns("/api/webhooks/**", "/api/auth/**", "/api/ops/**");
    }
}
