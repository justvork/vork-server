package sh.vork.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.oauth.OAuthTemplate;
import sh.vork.oauth.OAuthTemplateService;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

class AiConfigOAuthTemplatesToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listOauthTemplates_returnsSummariesOnly() throws Exception {
        JavaTypeClassLoader classLoader = org.mockito.Mockito.mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = org.mockito.Mockito.mock(TypeDatabaseService.class);
        OAuthTemplateService oauthTemplateService = org.mockito.Mockito.mock(OAuthTemplateService.class);

        OAuthTemplate template = new OAuthTemplate(
                UUID.randomUUID(),
                "Google Calendar",
                "google_calendar",
                "Google Calendar OAuth defaults",
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create("https://oauth2.googleapis.com/token"),
                List.of("openid", "email"),
                Map.of("access_type", "offline"));

        when(oauthTemplateService.listTemplates()).thenReturn(List.of(template));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.listOauthTemplates(oauthTemplateService);

        String output = tool.call("{}");
        Map<String, Object> response = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {});

        assertEquals("ok", response.get("status"));
        assertEquals(1, ((Number) response.get("count")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> templates = (List<Map<String, Object>>) response.get("templates");
        assertEquals(1, templates.size());
        assertEquals("google_calendar", templates.get(0).get("clientName"));
        assertEquals("Google Calendar", templates.get(0).get("name"));
        assertEquals("Google Calendar OAuth defaults", templates.get(0).get("description"));
        assertTrue(!templates.get(0).containsKey("authorizeEndpoint"));
    }

    @Test
    void getOauthTemplate_returnsFullTemplateByClientName() throws Exception {
        JavaTypeClassLoader classLoader = org.mockito.Mockito.mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = org.mockito.Mockito.mock(TypeDatabaseService.class);
        OAuthTemplateService oauthTemplateService = org.mockito.Mockito.mock(OAuthTemplateService.class);

        OAuthTemplate template = new OAuthTemplate(
                UUID.randomUUID(),
                "Google Calendar",
                "google_calendar",
                "Google Calendar OAuth defaults",
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"),
                URI.create("https://oauth2.googleapis.com/token"),
                List.of("openid", "email"),
                Map.of("access_type", "offline"));

        when(oauthTemplateService.listTemplates()).thenReturn(List.of(template));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.getOauthTemplate(oauthTemplateService);

        String output = tool.call("{\"clientName\":\"Google Calendar\"}");
        Map<String, Object> response = objectMapper.readValue(output, new TypeReference<Map<String, Object>>() {});

        assertEquals("ok", response.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> fullTemplate = (Map<String, Object>) response.get("template");
        assertEquals("google_calendar", fullTemplate.get("clientName"));
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", fullTemplate.get("authorizeEndpoint"));
        assertEquals("https://oauth2.googleapis.com/token", fullTemplate.get("tokenEndpoint"));
    }
}
