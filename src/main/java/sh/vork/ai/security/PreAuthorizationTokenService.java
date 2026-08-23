package sh.vork.ai.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and consumes pre-authorization tokens for exact restricted tool payloads.
 */
@Service
public class PreAuthorizationTokenService {

    private static final Logger log = LoggerFactory.getLogger(PreAuthorizationTokenService.class);

    private static final int DEFAULT_TTL_SECONDS = 900;
    private static final int MAX_TTL_SECONDS = 3600;

    private final DatabaseRepository<PreAuthorizationTokenRecord> tokenRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public record IssuedToken(
            String token,
            String toolName,
            String scope,
            String argumentsSha256,
            long expiresAt
    ) {
    }

    public PreAuthorizationTokenService(RepositoryFactory repositoryFactory) {
        this.tokenRepository = repositoryFactory.create(PreAuthorizationTokenRecord.class);
    }

    public IssuedToken issueToken(String username,
                                  String sessionUuid,
                                  String toolName,
                                  String argumentsJson,
                                  Integer ttlSeconds,
                                  String scope,
                                  String issuedReason) {
        String normalizedUser = normalize(username);
        String normalizedSession = normalize(sessionUuid);
        String normalizedTool = normalize(toolName);
        String normalizedScope = normalizeScope(scope);
        String canonicalArgs = canonicalizeArguments(argumentsJson);
        String argsDigest = sha256Hex(canonicalArgs);

        int effectiveTtl = ttlSeconds == null ? DEFAULT_TTL_SECONDS : Math.max(30, Math.min(ttlSeconds, MAX_TTL_SECONDS));
        long now = System.currentTimeMillis();
        long expiresAt = now + (effectiveTtl * 1000L);
        String token = UUID.randomUUID().toString().replace("-", "");

        PreAuthorizationTokenRecord record = new PreAuthorizationTokenRecord(
                token,
                normalizedUser,
                normalizedSession,
                normalizedTool,
                canonicalArgs,
                argsDigest,
                normalizedScope,
                "OPEN",
                now,
                expiresAt,
                null,
                issuedReason == null ? "" : issuedReason.trim());

        tokenRepository.save(record);
        log.info("Pre-authorization token issued [token={}, user={}, session={}, tool={}, scope={}, expiresAt={}]",
                shortToken(token), normalizedUser, normalizedSession, normalizedTool, normalizedScope,
                Instant.ofEpochMilli(expiresAt));
        return new IssuedToken(token, normalizedTool, normalizedScope, argsDigest, expiresAt);
    }

    public boolean consumeMatchingToken(String username,
                                        String sessionUuid,
                                        String toolName,
                                        String argumentsJson) {
        String normalizedUser = normalize(username);
        String normalizedSession = normalize(sessionUuid);
        String normalizedTool = normalize(toolName);
        String canonicalArgs = canonicalizeArguments(argumentsJson);
        String argsDigest = sha256Hex(canonicalArgs);
        long now = System.currentTimeMillis();

        List<PreAuthorizationTokenRecord> candidates;
        try (var stream = tokenRepository.search(
                0,
                Integer.MAX_VALUE,
                "createdAt",
                SortOrder.ASC,
                SearchQuery.eq("status", "OPEN"),
                SearchQuery.eq("username", normalizedUser),
                SearchQuery.eq("toolName", normalizedTool),
                SearchQuery.eq("argumentsSha256", argsDigest))) {
            candidates = new ArrayList<>(stream.toList());
        }

        if (candidates.isEmpty()) {
            log.debug("No matching pre-authorization token found [user={}, session={}, tool={}]",
                    normalizedUser, normalizedSession, normalizedTool);
            return false;
        }

        candidates.sort(Comparator.comparingLong(PreAuthorizationTokenRecord::createdAt));

        for (PreAuthorizationTokenRecord candidate : candidates) {
            if (now > candidate.expiresAt()) {
                expire(candidate, now);
                continue;
            }
            if ("SESSION".equals(candidate.scope())
                    && !safeEquals(candidate.sessionUuid(), normalizedSession)) {
                continue;
            }

            PreAuthorizationTokenRecord consumed = new PreAuthorizationTokenRecord(
                    candidate.uuid(),
                    candidate.username(),
                    candidate.sessionUuid(),
                    candidate.toolName(),
                    candidate.canonicalArguments(),
                    candidate.argumentsSha256(),
                    candidate.scope(),
                    "CONSUMED",
                    candidate.createdAt(),
                    candidate.expiresAt(),
                    now,
                    candidate.issuedReason());
            tokenRepository.save(consumed);
            log.info("Pre-authorization token consumed [token={}, user={}, session={}, tool={}]",
                    shortToken(candidate.uuid()), normalizedUser, normalizedSession, normalizedTool);
            return true;
        }

        log.debug("Pre-authorization token candidates found but none valid for scope/session [user={}, session={}, tool={}]",
                normalizedUser, normalizedSession, normalizedTool);
        return false;
    }

    public String canonicalizeArguments(String argumentsJson) {
        String normalized = normalizeArguments(argumentsJson);
        try {
            JsonNode node = objectMapper.readTree(normalized);
            Object sorted = sortNode(node);
            return objectMapper.writeValueAsString(sorted);
        } catch (Exception ex) {
            // Invalid JSON remains bindable by exact payload text.
            return normalized;
        }
    }

    private void expire(PreAuthorizationTokenRecord candidate, long now) {
        PreAuthorizationTokenRecord expired = new PreAuthorizationTokenRecord(
                candidate.uuid(),
                candidate.username(),
                candidate.sessionUuid(),
                candidate.toolName(),
                candidate.canonicalArguments(),
                candidate.argumentsSha256(),
                candidate.scope(),
                "EXPIRED",
                candidate.createdAt(),
                candidate.expiresAt(),
                now,
                candidate.issuedReason());
        tokenRepository.save(expired);
    }

    private Object sortNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), sortNode(entry.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(sortNode(item));
            }
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.asText();
    }

    private static String normalizeArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "{}";
        }
        return argumentsJson.trim();
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim();
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "SESSION";
        }
        String normalized = scope.trim().toUpperCase();
        return switch (normalized) {
            case "SESSION", "BACKGROUND" -> normalized;
            default -> "SESSION";
        };
    }

    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash pre-authorization payload", ex);
        }
    }

    private static String shortToken(String token) {
        if (token == null || token.length() < 8) {
            return token == null ? "" : token;
        }
        return token.substring(0, 8);
    }

    private static boolean safeEquals(String a, String b) {
        return normalize(a).equals(normalize(b));
    }
}
