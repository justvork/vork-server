package sh.vork.surface;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link DatabaseRepository} bean for {@link Surface} records.
 */
@Configuration
public class SurfaceRepositoryConfig {

    @Bean
    public DatabaseRepository<Surface> surfaceRepository(RepositoryFactory factory) {
        return factory.create(Surface.class);
    }
}
