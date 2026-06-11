package sh.vork.scheduling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.scheduling.domain.JobResult;
import sh.vork.scheduling.domain.ScheduledJob;

@Configuration
public class SchedulingRepositoryConfig {

    @Bean
    public DatabaseRepository<ScheduledJob> scheduledJobRepository(RepositoryFactory factory) {
        return factory.create(ScheduledJob.class);
    }

    @Bean
    public DatabaseRepository<JobResult> jobResultRepository(RepositoryFactory factory) {
        return factory.create(JobResult.class);
    }
}
