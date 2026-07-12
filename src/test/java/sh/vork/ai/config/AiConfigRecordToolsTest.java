package sh.vork.ai.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.function.CreateSkillRequest;
import sh.vork.ai.memory.InMemorySessionEnvironmentService;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.orm.DatabaseEntity;
import sh.vork.security.SecureCredentialStore;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillService;
import sh.vork.skill.SkillVisibility;
import sh.vork.typegen.JavaTypeClassLoader;
import sh.vork.typegen.TypeDatabaseService;

class AiConfigRecordToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void getTypeInstance_returnsRecordJsonWhenFound() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        doReturn(CatRecord.class).when(classLoader).loadClass("sh.vork.generated.Cat");
        when(typeDatabaseService.get(CatRecord.class, "cat-1")).thenReturn(new CatRecord("cat-1", "Milo"));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.getTypeInstance();

        String args = "{\"fqn\":\"sh.vork.generated.Cat\",\"uuid\":\"cat-1\"}";
        String output = tool.call(args);

        var map = objectMapper.readValue(output, new TypeReference<java.util.Map<String, Object>>() {});
        assertEquals("cat-1", map.get("uuid"));
        assertEquals("Milo", map.get("name"));
    }

    @Test
    void countTypeInstances_supportsUnfilteredAndSqlFilteredCounts() {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        try {
            doReturn(CatRecord.class).when(classLoader).loadClass("sh.vork.generated.Cat");
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
        when(typeDatabaseService.count(CatRecord.class)).thenReturn(7L);
        when(typeDatabaseService.searchCountBySql(CatRecord.class, "name LIKE '%mi%'"))
                .thenReturn(2L);

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.countTypeInstances();

        String noFilterOutput = tool.call("{\"fqn\":\"sh.vork.generated.Cat\"}");
        assertTrue(noFilterOutput.contains("\"count\":7"));

        String sqlFilterOutput = tool.call("{\"fqn\":\"sh.vork.generated.Cat\",\"query\":\"name LIKE '%mi%'\",\"queryType\":\"SQL\"}");
        assertTrue(sqlFilterOutput.contains("\"count\":2"));
    }

    @Test
    void getDateTime_returnsLocalDateTimeFields() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.getDateTime();

        String output = tool.call("{}");

        var map = objectMapper.readValue(output, new TypeReference<java.util.Map<String, Object>>() {});
        assertEquals("ok", map.get("status"));
        assertNotNull(map.get("isoDateTime"));
        assertNotNull(map.get("localDate"));
        assertNotNull(map.get("localTime"));
        assertNotNull(map.get("zoneId"));

        String isoDateTime = String.valueOf(map.get("isoDateTime"));
        java.time.ZonedDateTime parsed = java.time.ZonedDateTime.parse(isoDateTime);
        assertNotNull(parsed);
    }

    @Test
    void base64EncodeDecode_roundTripUtf8Content() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

        ToolCallback encode = config.base64EncodeString();
        ToolCallback decode = config.base64DecodeString();

        String plain = "vork-tool-roundtrip";
        String encodedOutput = encode.call("{\"input\":\"" + plain + "\"}");
        String encoded = readStringResult(encodedOutput);
        assertEquals(Base64.getEncoder().encodeToString(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)), encoded);

        String decoded = readStringResult(decode.call("{\"input\":\"" + encoded + "\"}"));
        assertEquals(plain, decoded);
    }

    @Test
    void signAndVerify_supportsPemKeyMaterial() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

        ToolCallback sign = config.signData();
        ToolCallback verify = config.verifyData();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        String privatePem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
        String publicPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());

        String data = "sign me please";
        String signArgs = objectMapper.writeValueAsString(java.util.Map.of(
                "data", data,
                "privateKey", privatePem,
                "algorithm", "SHA256withRSA"
        ));
        String signature = readStringResult(sign.call(signArgs));
        assertNotNull(signature);
        assertFalse(signature.isBlank());

        String verifyArgs = objectMapper.writeValueAsString(java.util.Map.of(
                "data", data,
                "publicKey", publicPem,
                "signature", signature,
                "algorithm", "SHA256withRSA"
        ));
        assertEquals("true", readStringResult(verify.call(verifyArgs)));

        String tamperedVerifyArgs = objectMapper.writeValueAsString(java.util.Map.of(
                "data", data + "!",
                "publicKey", publicPem,
                "signature", signature,
                "algorithm", "SHA256withRSA"
        ));
        assertEquals("false", readStringResult(verify.call(tamperedVerifyArgs)));
    }

    @Test
    void generatePrivateKey_returnsReferences_andStoresPrivateAndPublicMaterial() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));
        try {
            ToolCallback tool = config.generatePrivateKey(secureCredentialStore);

            String output = tool.call("{\"secretName\":\"signing.key.main\",\"keyAlgorithm\":\"RSA\",\"keySize\":2048}");
            var map = objectMapper.readValue(output, new TypeReference<java.util.Map<String, Object>>() {});

            assertEquals("ok", map.get("status"));
            assertEquals("signing.key.main", map.get("secretName"));
            assertEquals("{{signing.key.main}}", map.get("privateKeySecretRef"));
                assertEquals("{{signing.key.main}}", map.get("publicKeyLookupRef"));
            assertEquals("RSA", map.get("keyAlgorithm"));
            assertEquals("SHA256withRSA", map.get("suggestedSigningAlgorithm"));
                assertEquals("Call getPublicKey with secretName to retrieve the Base64 public key.", map.get("nextStep"));

            ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
            verify(secureCredentialStore).saveSecretForUser(
                    org.mockito.ArgumentMatchers.eq("alice"),
                    org.mockito.ArgumentMatchers.eq("signing.key.main"),
                    valueCaptor.capture());
            assertTrue(valueCaptor.getValue().contains("BEGIN PRIVATE KEY"));

                ArgumentCaptor<String> publicValueCaptor = ArgumentCaptor.forClass(String.class);
                verify(secureCredentialStore).saveSecretForUser(
                    org.mockito.ArgumentMatchers.eq("alice"),
                    org.mockito.ArgumentMatchers.eq("signing.key.main.public"),
                    publicValueCaptor.capture());
                assertFalse(publicValueCaptor.getValue().isBlank());
                assertFalse(publicValueCaptor.getValue().contains("BEGIN"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void generatePrivateKey_authorizationDetails_includeKeySizeForRsa() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

        ToolCallback tool = config.generatePrivateKey(secureCredentialStore);
        assertTrue(tool instanceof VisualizableTool);

        String details = ((VisualizableTool) tool).formatAuthorizationDetails(
                "{\"secretName\":\"signing.key.main\",\"keyAlgorithm\":\"RSA\",\"keySize\":3072}");

        assertTrue(details.contains("- Key Algorithm: RSA"));
        assertTrue(details.contains("- Key Size: 3072"));
    }

    @Test
    void generatePrivateKey_authorizationDetails_omitKeySizeForEd25519() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

        ToolCallback tool = config.generatePrivateKey(secureCredentialStore);
        assertTrue(tool instanceof VisualizableTool);

        String details = ((VisualizableTool) tool).formatAuthorizationDetails(
                "{\"secretName\":\"signing.key.main\",\"keyAlgorithm\":\"ED25519\"}");

        assertTrue(details.contains("- Key Algorithm: ED25519"));
        assertFalse(details.contains("Key Size"));
        assertFalse(details.contains("2048"));
    }

            @Test
            void getPublicKey_returnsBase64ForSameIdentifier() throws Exception {
            JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
            TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
            SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
            AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

            when(secureCredentialStore.getSecretForUser("alice", "signing.key.main.public"))
                .thenReturn("MCowBQYDK2VwAyEAXYZfakeBase64PublicKeyForTest==");

            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a"));
            try {
                ToolCallback tool = config.getPublicKey(secureCredentialStore);
                String output = tool.call("{\"secretName\":\"{{signing.key.main}}\"}");
                var map = objectMapper.readValue(output, new TypeReference<java.util.Map<String, Object>>() {});

                assertEquals("ok", map.get("status"));
                assertEquals("signing.key.main", map.get("secretName"));
                assertEquals("MCowBQYDK2VwAyEAXYZfakeBase64PublicKeyForTest==", map.get("publicKeyBase64"));
            } finally {
                SecurityContextHolder.clearContext();
            }
            }

            @Test
            void signAndVerify_supportsEd25519() throws Exception {
            JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
            TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
            AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

            ToolCallback sign = config.signData();
            ToolCallback verify = config.verifyData();

            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = generator.generateKeyPair();

            String privatePem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
            String publicPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());

            String data = "ed25519 payload";
            String signArgs = objectMapper.writeValueAsString(java.util.Map.of(
                "data", data,
                "privateKey", privatePem,
                "algorithm", "Ed25519"
            ));
            String signature = readStringResult(sign.call(signArgs));

            String verifyArgs = objectMapper.writeValueAsString(java.util.Map.of(
                "data", data,
                "publicKey", publicPem,
                "signature", signature,
                "algorithm", "Ed25519"
            ));
            assertEquals("true", readStringResult(verify.call(verifyArgs)));
            }

        @Test
        void verifyDataByRef_usesSameIdentifierAndStoredPublicKey() throws Exception {
            JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
            TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
            SecureCredentialStore secureCredentialStore = mock(SecureCredentialStore.class);
            AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);

            ToolCallback sign = config.signData();
            ToolCallback verifyByRef = config.verifyDataByRef(secureCredentialStore);

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            String privatePem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
            String publicBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            when(secureCredentialStore.getSecretForUser("alice", "signing.key.main.public")).thenReturn(publicBase64);

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("alice", "n/a"));
            try {
                String data = "verify-by-ref";
                String signArgs = objectMapper.writeValueAsString(java.util.Map.of(
                        "data", data,
                        "privateKey", privatePem,
                        "algorithm", "SHA256withRSA"
                ));
                String signature = readStringResult(sign.call(signArgs));

                String verifyArgs = objectMapper.writeValueAsString(java.util.Map.of(
                        "data", data,
                        "signature", signature,
                        "algorithm", "SHA256withRSA",
                        "secretName", "{{signing.key.main}}"
                ));
                assertEquals("true", readStringResult(verifyByRef.call(verifyArgs)));

                String tamperedVerifyArgs = objectMapper.writeValueAsString(java.util.Map.of(
                        "data", data + "-tampered",
                        "signature", signature,
                        "algorithm", "SHA256withRSA",
                        "secretName", "{{signing.key.main}}"
                ));
                assertEquals("false", readStringResult(verifyByRef.call(tamperedVerifyArgs)));
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

    @Test
    void memory_setGetListDelete_managesSessionEnvironmentVariables() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.memory(new InMemorySessionEnvironmentService());

        ToolExecutionContext.bindSessionUuid("session-memory-1");
        try {
            String setOutput = tool.call("{\"operation\":\"set\",\"key\":\"active_target_alias\",\"value\":\"edge-node-01\"}");
            assertTrue(setOutput.contains("\"status\":\"ok\""));
            assertTrue(setOutput.contains("\"operation\":\"set\""));

            String getOutput = tool.call("{\"operation\":\"get\",\"key\":\"active_target_alias\"}");
            var getMap = objectMapper.readValue(getOutput, new TypeReference<java.util.Map<String, Object>>() {});
            assertEquals("ok", getMap.get("status"));
            assertEquals("active_target_alias", getMap.get("key"));
            assertEquals("edge-node-01", getMap.get("value"));
            assertEquals(Boolean.TRUE, getMap.get("found"));

            String listOutput = tool.call("{\"operation\":\"list\",\"prefix\":\"active_\"}");
            var listMap = objectMapper.readValue(listOutput, new TypeReference<java.util.Map<String, Object>>() {});
            assertEquals("ok", listMap.get("status"));
            assertEquals(1, ((Number) listMap.get("count")).intValue());
            @SuppressWarnings("unchecked")
            var entries = (java.util.Map<String, Object>) listMap.get("entries");
            assertEquals("edge-node-01", entries.get("active_target_alias"));

            String deleteOutput = tool.call("{\"operation\":\"delete\",\"key\":\"active_target_alias\"}");
            assertTrue(deleteOutput.contains("\"deleted\":true"));

            String getAfterDeleteOutput = tool.call("{\"operation\":\"get\",\"key\":\"active_target_alias\"}");
            var getAfterDeleteMap = objectMapper.readValue(getAfterDeleteOutput, new TypeReference<java.util.Map<String, Object>>() {});
            assertEquals("", getAfterDeleteMap.get("value"));
            assertEquals(Boolean.FALSE, getAfterDeleteMap.get("found"));
        } finally {
            ToolExecutionContext.clear();
        }
    }

        @Test
        void createSkill_persistsExplicitSkillFields() throws Exception {
        JavaTypeClassLoader classLoader = mock(JavaTypeClassLoader.class);
        TypeDatabaseService typeDatabaseService = mock(TypeDatabaseService.class);
        SkillService skillService = mock(SkillService.class);

        when(skillService.create(org.mockito.ArgumentMatchers.any(SkillService.SkillRequest.class)))
            .thenReturn(new Skill(
                "skill-1",
                "My Skill",
                "desc",
                "group-1",
                    SkillVisibility.PUBLIC,
                List.of(),
                "do steps",
                List.of("listAvailableTools"),
                List.of(),
                List.of(),
                1,
                1,
                1,
                List.of()));

        AiConfig config = new AiConfig(classLoader, typeDatabaseService, objectMapper);
        ToolCallback tool = config.createSkill(skillService);
        assertTrue(tool instanceof VisualizableTool);

        CreateSkillRequest request = new CreateSkillRequest(
            "My Skill",
            "desc",
            "group-1",
            SkillVisibility.PUBLIC,
            List.of(),
            "do steps",
            List.of("listAvailableTools"),
            List.of(),
            List.of(),
            List.of());

        String output = tool.call(objectMapper.writeValueAsString(request));
        var map = objectMapper.readValue(output, new TypeReference<java.util.Map<String, Object>>() {});
        assertEquals("ok", map.get("status"));
        assertEquals("skill-1", map.get("skillUuid"));
        assertEquals("group-1", map.get("groupUuid"));

        String markdown = ((VisualizableTool) tool).formatAuthorizationDetails(objectMapper.writeValueAsString(request));
        assertTrue(markdown.startsWith("## Create Skill"));
        assertTrue(markdown.contains("- **Name:** My Skill"));
        assertTrue(markdown.contains("- **Group UUID:** group-1"));
        }

    private record CatRecord(String uuid, String name) implements DatabaseEntity {}

    private static String toPem(String type, byte[] derBytes) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .encodeToString(derBytes);
        return "-----BEGIN " + type + "-----\n"
                + base64
                + "\n-----END " + type + "-----";
    }

    private String readStringResult(String jsonStringLiteral) throws Exception {
        return objectMapper.readValue(jsonStringLiteral, String.class);
    }
}
