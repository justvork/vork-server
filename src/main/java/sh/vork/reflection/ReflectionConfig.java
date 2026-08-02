package sh.vork.reflection;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class ReflectionConfig {

    @Bean
    public DatabaseRepository<Reflection> reflectionRepository(RepositoryFactory factory) {
        return factory.create(Reflection.class);
    }

    @Bean
    public DatabaseRepository<ReflectionGroup> reflectionGroupRepository(RepositoryFactory factory) {
        return factory.create(ReflectionGroup.class);
    }

    @Bean
    public DatabaseRepository<ReflectionBinding> reflectionBindingRepository(RepositoryFactory factory) {
        return factory.create(ReflectionBinding.class);
    }
}
