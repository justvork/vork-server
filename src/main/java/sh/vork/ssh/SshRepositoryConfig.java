package sh.vork.ssh;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.database.DatabaseRepository;
import sh.vork.database.DatabaseRepositoryFactory;

@Configuration
public class SshRepositoryConfig {

    @Bean
    public DatabaseRepository<VorkNode> vorkNodeRepository(DatabaseRepositoryFactory factory) {
        return factory.create(VorkNode.class);
    }
}
