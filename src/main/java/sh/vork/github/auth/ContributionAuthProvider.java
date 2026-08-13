package sh.vork.github.auth;

/**
 * Provider-neutral access token contract for contribution workflows.
 */
public interface ContributionAuthProvider {

    String providerName();

    boolean isAuthenticated(String username);

    ContributionAuthToken requireToken(String username);
}
