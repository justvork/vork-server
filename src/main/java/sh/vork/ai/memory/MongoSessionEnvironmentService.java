package sh.vork.ai.memory;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

@Service
@ConditionalOnProperty(name = "db.backend", havingValue = "mongo", matchIfMissing = true)
public class MongoSessionEnvironmentService implements SessionEnvironmentService {

    private static final String SESSION_COLLECTION = "ai_session";

    private final MongoCollection<Document> sessionCollection;

    public MongoSessionEnvironmentService(MongoDatabase mongoDatabase) {
        this.sessionCollection = mongoDatabase.getCollection(SESSION_COLLECTION);
    }

    @Override
    public void setEnv(String sessionUuid, String key, String value) {
        if (sessionUuid == null || sessionUuid.isBlank() || key == null || key.isBlank()) {
            return;
        }
        String normalizedValue = value == null ? "" : value;
        String safeKey = encodeKey(key);
        sessionCollection.updateOne(
                Filters.eq("_id", sessionUuid),
                Updates.set("environmentVariables." + safeKey, normalizedValue));
    }

    @Override
    public Map<String, String> getEnv(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return Map.of();
        }

        Document session = sessionCollection.find(Filters.eq("_id", sessionUuid)).first();
        if (session == null) {
            return Map.of();
        }

        Object rawEnv = session.get("environmentVariables");
        if (!(rawEnv instanceof Document envDoc)) {
            return Map.of();
        }

        Map<String, String> result = new LinkedHashMap<>();
        flattenInto(result, "", envDoc);

        Map<String, String> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : result.entrySet()) {
            decoded.put(decodeKey(entry.getKey()), entry.getValue());
        }
        return Map.copyOf(decoded);
    }

    private static void flattenInto(Map<String, String> target, String prefix, Object value) {
        if (value instanceof Document nested) {
            for (Map.Entry<String, Object> entry : nested.entrySet()) {
                String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
                flattenInto(target, key, entry.getValue());
            }
            return;
        }
        if (!prefix.isEmpty()) {
            target.put(prefix, value == null ? "" : String.valueOf(value));
        }
    }

    private static String encodeKey(String key) {
        return key.replace(".", "__dot__");
    }

    private static String decodeKey(String key) {
        return key.replace("__dot__", ".");
    }
}
