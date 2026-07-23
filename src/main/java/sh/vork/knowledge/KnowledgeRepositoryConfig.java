package sh.vork.knowledge;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

/**
 * Declares the {@link DatabaseRepository} bean for {@link KnowledgeEntry}.
 */
@Configuration
public class KnowledgeRepositoryConfig {

    @Bean
    public DatabaseRepository<KnowledgeEntry> knowledgeRepository(RepositoryFactory factory) {
        return factory.create(KnowledgeEntry.class);
    }
}
