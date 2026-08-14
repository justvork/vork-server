package sh.vork.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class OAuthConfig {

    @Bean
    public DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository(RepositoryFactory factory) {
        return factory.create(OAuthTemplateEntity.class);
    }
}
