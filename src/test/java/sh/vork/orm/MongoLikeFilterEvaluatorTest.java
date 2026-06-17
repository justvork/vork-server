package sh.vork.orm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class MongoLikeFilterEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void matches_supportsComparisonAndLogicalOperators() {
        Map<String, Object> doc = Map.of(
                "active", true,
                "age", 34,
                "profile", Map.of("city", "London"));

        Map<String, Object> filter = MongoLikeFilterEvaluator.parseFilter(
                mapper,
                "{\"$and\":[{\"active\":true},{\"age\":{\"$gte\":18}},{\"profile.city\":\"London\"}]}"
        );

        assertTrue(MongoLikeFilterEvaluator.matches(doc, filter));
    }

    @Test
    void matches_supportsRegexAndExists() {
        Map<String, Object> doc = Map.of(
                "name", "Alice Johnson",
                "meta", Map.of("region", "eu-west"));

        Map<String, Object> regexFilter = MongoLikeFilterEvaluator.parseFilter(
                mapper,
                "{\"name\":{\"$regex\":\"alice\",\"$options\":\"i\"}}"
        );
        Map<String, Object> existsFilter = MongoLikeFilterEvaluator.parseFilter(
                mapper,
                "{\"meta.region\":{\"$exists\":true},\"meta.zone\":{\"$exists\":false}}"
        );

        assertTrue(MongoLikeFilterEvaluator.matches(doc, regexFilter));
        assertTrue(MongoLikeFilterEvaluator.matches(doc, existsFilter));
    }

    @Test
    void matches_returnsFalseForNonMatchingFilter() {
        Map<String, Object> doc = Map.of("status", "active", "score", 7);
        Map<String, Object> filter = MongoLikeFilterEvaluator.parseFilter(
                mapper,
                "{\"$or\":[{\"status\":\"archived\"},{\"score\":{\"$gt\":10}}]}"
        );

        assertFalse(MongoLikeFilterEvaluator.matches(doc, filter));
    }
}
