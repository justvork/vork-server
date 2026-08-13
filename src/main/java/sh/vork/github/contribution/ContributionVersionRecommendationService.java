package sh.vork.github.contribution;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ContributionVersionRecommendationService {

    private static final Logger log = LoggerFactory.getLogger(ContributionVersionRecommendationService.class);

    private static final String OFFICIAL_OWNER = "justvork";
    private static final String OFFICIAL_REPOSITORY = "vork-central";
    private static final String STAGING_BRANCH = "staging";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)$");

    private final GitHubContributionApiClient contributionApiClient;

    public ContributionVersionRecommendationService(GitHubContributionApiClient contributionApiClient) {
        this.contributionApiClient = contributionApiClient;
    }

    public Recommendation recommendNextVersion(String artifactType,
                                               String groupId,
                                               String artifactId,
                                               boolean breakingChange) {
        log.debug("ENTER recommendNextVersion: type={}, groupId={}, artifactId={}, breakingChange={}",
                artifactType, groupId, artifactId, breakingChange);

        String versionsPath = artifactType + "/" + groupId + "/" + artifactId;
        List<String> candidates;
        try {
            candidates = contributionApiClient.listDirectoryNamesInBranch(
                OFFICIAL_OWNER,
                OFFICIAL_REPOSITORY,
                STAGING_BRANCH,
                versionsPath);
        } catch (RuntimeException ex) {
            log.warn("Version lookup failed; falling back to initial release recommendation [type={}, groupId={}, artifactId={}]: {}",
                artifactType, groupId, artifactId, ex.getMessage());
            candidates = List.of();
        }

        Version latest = candidates.stream()
                .map(ContributionVersionRecommendationService::parseVersion)
                .filter(v -> v != null)
                .max(Comparator.naturalOrder())
                .orElse(null);

        Version recommended = recommend(latest, breakingChange);
        Recommendation response = new Recommendation(
                latest == null ? null : latest.toString(),
                recommended.toString(),
                breakingChange ? "major" : "minor");

        log.debug("EXIT recommendNextVersion: latestVersion={}, recommendedVersion={}, strategy={}",
                response.latestVersion(), response.recommendedVersion(), response.strategy());
        return response;
    }

    private static Version recommend(Version latest, boolean breakingChange) {
        if (latest == null) {
            return new Version(1, 0);
        }
        if (breakingChange) {
            return new Version(latest.major() + 1, 0);
        }
        return new Version(latest.major(), latest.minor() + 1);
    }

    private static Version parseVersion(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        return new Version(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)));
    }

    private record Version(int major, int minor) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            int majorCmp = Integer.compare(this.major, other.major);
            if (majorCmp != 0) {
                return majorCmp;
            }
            return Integer.compare(this.minor, other.minor);
        }

        @Override
        public String toString() {
            return major + "." + minor;
        }
    }

    public record Recommendation(String latestVersion,
                                 String recommendedVersion,
                                 String strategy) {
    }
}
