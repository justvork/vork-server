package sh.vork.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseSetupServiceMongoSettingsTest {

    private static final Path DB_PROPS = Path.of("conf.d/database.properties");

    private Path backupPath;

    @BeforeEach
    void moveExistingConfigOutOfTheWay() throws IOException {
        if (Files.exists(DB_PROPS)) {
            backupPath = Files.createTempFile("database-properties-backup", ".properties");
            Files.move(DB_PROPS, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @AfterEach
    void restoreConfig() throws IOException {
        Files.deleteIfExists(DB_PROPS);
        if (backupPath != null && Files.exists(backupPath)) {
            if (DB_PROPS.getParent() != null) {
                Files.createDirectories(DB_PROPS.getParent());
            }
            Files.move(backupPath, DB_PROPS, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test
    void saveConfig_writesMongoUri() throws IOException {
        DatabaseSetupService service = new DatabaseSetupService();
        String uri = "mongodb+srv://user:password@cluster0.example.mongodb.net/vork?retryWrites=true";
        DatabaseSettings settings = new DatabaseSettings(
                "mongo",
                null,
                0,
                null,
                null,
                null,
                uri
        );

        service.saveConfig(settings);

        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(DB_PROPS)) {
            props.load(is);
        }

        assertEquals("mongo", props.getProperty("db.backend"));
        assertEquals(uri, props.getProperty("mongo.uri"));
    }

    @Test
    void getCurrentSettings_readsMongoUri() throws IOException {
        DatabaseSetupService service = new DatabaseSetupService();
        String uri = "mongodb://mongodb:27017/vork";
        DatabaseSettings settings = new DatabaseSettings(
                "mongo",
                null,
                0,
                null,
                null,
                null,
                uri
        );
        service.saveConfig(settings);

        DatabaseSettings loaded = service.getCurrentSettings();

        assertEquals("mongo", loaded.backend());
        assertEquals(uri, loaded.uri());
    }

    @Test
    void saveConfig_defaultsMongoUri_whenBlank() throws IOException {
        DatabaseSetupService service = new DatabaseSetupService();
        DatabaseSettings settings = new DatabaseSettings(
                "mongo",
                null,
                0,
                null,
                null,
                null,
                ""
        );

        service.saveConfig(settings);

        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(DB_PROPS)) {
            props.load(is);
        }

        assertTrue(props.getProperty("mongo.uri").contains("localhost:27017/vork"));
    }
}
