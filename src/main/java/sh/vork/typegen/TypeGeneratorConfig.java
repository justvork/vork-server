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

    @Bean
    public DatabaseRepository<TypeRecordBindingScope> typeRecordBindingScopeRepository(RepositoryFactory factory) {
        return factory.create(TypeRecordBindingScope.class);
    }

    @Bean
    public DatabaseRepository<TypeRecordVersionMetadata> typeRecordVersionMetadataRepository(RepositoryFactory factory) {
        return factory.create(TypeRecordVersionMetadata.class);
    }
}
