package cn.langchat.openclaw.weixin.web.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "DELETE", "PUT", "PATCH", "OPTIONS")
            .allowedHeaders("*");
    }
}
