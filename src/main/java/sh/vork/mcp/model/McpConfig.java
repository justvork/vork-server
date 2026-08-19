package sh.vork.mcp.model;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

@Configuration
public class McpConfig {

    @Bean
    public DatabaseRepository<McpBinding> mcpBindingRepository(RepositoryFactory factory) {
        return factory.create(McpBinding.class);
    }

    @Bean
    public DatabaseRepository<McpBindingTool> mcpBindingToolRepository(RepositoryFactory factory) {
        return factory.create(McpBindingTool.class);
    }

    @Bean
    public DatabaseRepository<McpBindingResource> mcpBindingResourceRepository(RepositoryFactory factory) {
        return factory.create(McpBindingResource.class);
    }

    @Bean
    public DatabaseRepository<McpBindingPrompt> mcpBindingPromptRepository(RepositoryFactory factory) {
        return factory.create(McpBindingPrompt.class);
    }
}
