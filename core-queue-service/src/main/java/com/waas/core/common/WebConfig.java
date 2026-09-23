package com.waas.core.common;

import com.waas.core.admission.RateLimitInterceptor;
import com.waas.core.auth.CurrentUserResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserResolver currentUser;
    private final RateLimitInterceptor rateLimit;

    public WebConfig(CurrentUserResolver currentUser, RateLimitInterceptor rateLimit) {
        this.currentUser = currentUser;
        this.rateLimit = rateLimit;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit).addPathPatterns("/api/waitlists/**");
    }
}
