package cn.langchat.openclaw.weixin.web.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @since 2026-04-21
 * @author LangChat Team
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class OpenClawWeixinWebBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenClawWeixinWebBackendApplication.class, args);
    }
}
