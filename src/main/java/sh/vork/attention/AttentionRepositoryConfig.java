package sh.vork.attention;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class AttentionRepositoryConfig {

    @Bean
    public DatabaseRepository<AttentionAlert> attentionAlertRepository(RepositoryFactory factory) {
        return factory.create(AttentionAlert.class);
    }
}
