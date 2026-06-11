package sh.vork.ssh;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class SshRepositoryConfig {

    @Bean
    public DatabaseRepository<VorkNode> vorkNodeRepository(RepositoryFactory factory) {
        return factory.create(VorkNode.class);
    }
}
