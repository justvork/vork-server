package sh.vork.setup;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

/**
 * Registers the {@link DatabaseRepository} bean for setup-related entities.
 */
@Configuration
public class SetupRepositoryConfig {

    @Bean
    public DatabaseRepository<SystemSettings> systemSettingsRepository(RepositoryFactory factory) {
        return factory.create(SystemSettings.class);
    }
}
