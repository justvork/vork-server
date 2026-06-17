package sh.vork.ai.memory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;

@Configuration
public class SessionEnvironmentConfig {

    @Bean
    @ConditionalOnBean(name = "aiSessionRepository")
    @ConditionalOnMissingBean(SessionEnvironmentService.class)
    public SessionEnvironmentService repositorySessionEnvironmentService(
            DatabaseRepository<AiSession> aiSessionRepository) {
        return new RepositorySessionEnvironmentService(aiSessionRepository);
    }

    @Bean
    @ConditionalOnMissingBean(SessionEnvironmentService.class)
    public SessionEnvironmentService sessionEnvironmentService() {
        return new InMemorySessionEnvironmentService();
    }
}
