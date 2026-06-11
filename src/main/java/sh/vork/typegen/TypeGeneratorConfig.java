package sh.vork.typegen;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TypeGeneratorConfig {

    @Bean
    public DatabaseRepository<JavaType> javaTypeRepository(RepositoryFactory factory) {
        return factory.create(JavaType.class);
    }
}
