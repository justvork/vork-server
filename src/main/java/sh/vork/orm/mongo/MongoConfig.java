package sh.vork.orm.mongo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Loads MongoDB connection settings from {@code conf.d/database.properties}
 * (relative to the working directory at startup) and exposes the necessary
 * Spring beans.
 *
 * <p>Active only when {@code db.backend=mongo}. The connection is configured
 * with a single {@code mongo.uri} value, which can point to a local server or
 * a managed service such as MongoDB Atlas.
 */
@Configuration
@ConditionalOnProperty(name = "db.backend", havingValue = "mongo")
public class MongoConfig {

    @Value("${mongo.uri:mongodb://localhost:27017/vork}")
    private String uri;

    @Bean(destroyMethod = "close")
    public MongoClient mongoClient() {
        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .build();
        return MongoClients.create(settings);
    }

    @Bean
    public MongoDatabase mongoDatabase(MongoClient mongoClient) {
        ConnectionString connectionString = new ConnectionString(uri);
        String databaseName = connectionString.getDatabase();
        if (databaseName == null || databaseName.isBlank()) {
            databaseName = "vork";
        }
        return mongoClient.getDatabase(databaseName);
    }

    /**
     * Shared {@link ObjectMapper} configured with all available Jackson modules
     * (including native Java record support via the parameter-names module).
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
