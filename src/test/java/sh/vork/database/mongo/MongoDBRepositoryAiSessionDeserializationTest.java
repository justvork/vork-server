package sh.vork.database.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import sh.vork.ai.entity.AiSession;

class MongoDBRepositoryAiSessionDeserializationTest {

    @Test
    @SuppressWarnings("unchecked")
    void deserialize_flattensNestedEnvironmentVariablesForAiSession() throws Exception {
        MongoDatabase mongoDatabase = mock(MongoDatabase.class);
        MongoCollection<Document> collection = (MongoCollection<Document>) mock(MongoCollection.class);
        when(mongoDatabase.getCollection("ai_session")).thenReturn(collection);

        MongoDBRepository<AiSession> repository = new MongoDBRepository<>(
                AiSession.class,
                mongoDatabase,
                new ObjectMapper().findAndRegisterModules());

        Document nestedEnv = new Document("ssh-username-10", new Document("0", new Document("22", new Document("22", "ubuntu"))));
        Document raw = new Document()
                .append("_id", "session-1")
                .append("uuid", "session-1")
                .append("provider", "GEMINI")
                .append("originMode", "WEB")
                .append("username", "admin")
                .append("name", "Untitled")
                .append("createdAt", 1L)
                .append("currentRoundCount", 0)
                .append("messages", List.of())
                .append("environmentVariables", nestedEnv)
                .append("status", "RUNNING");

        Method deserialize = MongoDBRepository.class.getDeclaredMethod("deserialize", Document.class);
        deserialize.setAccessible(true);

        AiSession session = (AiSession) deserialize.invoke(repository, raw);
        assertNotNull(session);
        assertEquals("ubuntu", session.environmentVariables().get("ssh-username-10.0.22.22"));
    }
}
