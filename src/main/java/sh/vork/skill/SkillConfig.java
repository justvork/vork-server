package sh.vork.skill;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class SkillConfig {

    @Bean
    public DatabaseRepository<Skill> skillRepository(RepositoryFactory factory) {
        return factory.create(Skill.class);
    }

    @Bean
    public DatabaseRepository<SkillGroup> skillGroupRepository(RepositoryFactory factory) {
        return factory.create(SkillGroup.class);
    }
}
