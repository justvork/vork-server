package sh.vork.github.contribution;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class ContributionRepositoryConfig {

    @Bean
    public DatabaseRepository<ContributionSubmission> contributionSubmissionRepository(RepositoryFactory factory) {
        return factory.create(ContributionSubmission.class);
    }
}
