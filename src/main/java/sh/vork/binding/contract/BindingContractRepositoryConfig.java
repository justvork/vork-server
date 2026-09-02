package sh.vork.binding.contract;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class BindingContractRepositoryConfig {

    @Bean
    public DatabaseRepository<BindingContract> bindingContractRepository(RepositoryFactory factory) {
        return factory.create(BindingContract.class);
    }
}
