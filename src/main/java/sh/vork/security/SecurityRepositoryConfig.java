package sh.vork.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sh.vork.ai.security.ApprovalPolicy;
import sh.vork.ai.security.ApprovalPolicyAssignment;
import sh.vork.ai.security.PreAuthorizationTokenRecord;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

/**
 * Configuration for security-related repositories.
 * Registers the DatabaseRepository for AuthorizationRequest and VorkUser.
 */
@Configuration
public class SecurityRepositoryConfig {

    @Bean
    public DatabaseRepository<AuthorizationRequest> authorizationRequestRepository(
            RepositoryFactory factory) {
        return factory.create(AuthorizationRequest.class);
    }

    @Bean
    public DatabaseRepository<VorkUser> vorkUserRepository(RepositoryFactory factory) {
        return factory.create(VorkUser.class);
    }

    @Bean
    public DatabaseRepository<PreAuthorizationTokenRecord> preAuthorizationTokenRepository(
            RepositoryFactory factory) {
        return factory.create(PreAuthorizationTokenRecord.class);
    }

    @Bean
    public DatabaseRepository<ApprovalPolicy> approvalPolicyRepository(RepositoryFactory factory) {
        return factory.create(ApprovalPolicy.class);
    }

    @Bean
    public DatabaseRepository<ApprovalPolicyAssignment> approvalPolicyAssignmentRepository(RepositoryFactory factory) {
        return factory.create(ApprovalPolicyAssignment.class);
    }
}
