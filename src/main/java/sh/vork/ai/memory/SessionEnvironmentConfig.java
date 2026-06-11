package sh.vork.ai.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SessionEnvironmentConfig {

    @Bean
    @ConditionalOnMissingBean(SessionEnvironmentService.class)
    public SessionEnvironmentService sessionEnvironmentService() {
        return new NoOpSessionEnvironmentService();
    }
}
