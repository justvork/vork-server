package sh.vork.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class LoginTemplateTest {

    @Test
    void loginTemplate_doesNotExposeRememberMeOption() throws IOException {
        String template = Files.readString(Path.of("src/main/resources/templates/login.html"));

        assertFalse(template.contains("name=\"remember-me\""));
        assertFalse(template.contains("Remember me for 30 days"));
    }
}
