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
    public DatabaseRepository<RecordReflection> recordReflectionRepository(RepositoryFactory factory) {
        return factory.create(RecordReflection.class);
    }

    @Bean
    public DatabaseRepository<MongoReflection> mongoReflectionRepository(RepositoryFactory factory) {
        return factory.create(MongoReflection.class);
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
