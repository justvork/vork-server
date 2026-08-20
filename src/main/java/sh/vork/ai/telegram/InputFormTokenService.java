package sh.vork.ai.telegram;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SortOrder;

/**
 * Issues and validates short-lived tokens for the generic input-form redirect flow.
 *
 * <p>When a tool suspension requires a complex form (password fields or multiple inputs),
 * the system sends the user a URL that embeds a token generated here.  The token is valid
 * for 15 minutes and is single-use (consumed on first successful submission).
 */
@Service
public class InputFormTokenService {

    private static final Logger log = LoggerFactory.getLogger(InputFormTokenService.class);
    private static final int    TTL_MINUTES = 15;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");

    public record TokenClaims(String sessionUuid,
                              String eventId,
                              String username,
                              String requestCampaignUuid,
                              String responderChannel) {
    }

    private final DatabaseRepository<InputFormTokenRecord> tokenRepository;

    public InputFormTokenService(RepositoryFactory repositoryFactory) {
        this.tokenRepository = repositoryFactory.create(InputFormTokenRecord.class);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Generates a token and stores it.
     *
     * @return an opaque 32-hex-char token string
     */
    public String generateToken(String sessionUuid, String eventId, String username) {
        return generateToken(sessionUuid, eventId, username, null, null);
    }

    public String generateToken(String sessionUuid,
                                String eventId,
                                String username,
                                String requestCampaignUuid,
                                String responderChannel) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant expiresAt = Instant.now().plusSeconds(TTL_MINUTES * 60L);
        long now = System.currentTimeMillis();
        tokenRepository.save(new InputFormTokenRecord(
            token,
                sessionUuid,
                eventId,
                username,
                requestCampaignUuid,
            responderChannel,
            expiresAt.toEpochMilli(),
            false,
            now,
            null));
        log.debug("Form token issued [session={}, event={}, expiresAt={}]", sessionUuid, eventId, expiresAt);
        return token;
    }

    /**
     * Validates the token and returns its claims if valid and unexpired.
     *
     * @return claims, or {@code null} if the token is unknown or expired
     */
    public TokenClaims validateToken(String token) {
        if (token == null || token.isBlank()) {
            log.warn("Input-form token rejected: missing token");
            return null;
        }

        String normalizedToken = normalizeToken(token);
        if (!normalizedToken.equals(token)) {
            log.debug("Input-form token normalized [rawLen={}, normalizedLen={}]",
                    token.length(), normalizedToken.length());
        }

        InputFormTokenRecord record = tokenRepository.get(normalizedToken);
        if (record == null) {
            log.warn("Input-form token rejected: not found [tokenPrefix={}]",
                    tokenPrefix(normalizedToken));
            return null;
        }
        if (record.consumed()) {
            log.warn("Input-form token rejected: already consumed [session={}, event={}, consumedAt={}]",
                    record.sessionUuid(), record.eventId(), record.consumedAt());
            return null;
        }
        long now = Instant.now().toEpochMilli();
        if (now > record.expiresAt()) {
            log.warn("Input-form token rejected: expired [session={}, event={}, expiresAt={}, now={}]",
                    record.sessionUuid(), record.eventId(), record.expiresAt(), now);
            tokenRepository.delete(normalizedToken);
            return null;
        }
        return new TokenClaims(
                record.sessionUuid(),
                record.eventId(),
                record.username(),
                record.requestCampaignUuid(),
                record.responderChannel());
    }

    private String normalizeToken(String rawToken) {
        String trimmed = rawToken == null ? "" : rawToken.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        // Accept copied links where wrappers/punctuation were included.
        trimmed = trimmed
            .replaceAll("^[\\\"'(<\\[]+", "")
            .replaceAll("[\\\"')>\\].,;:!?]+$", "");

        Matcher matcher = TOKEN_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        return trimmed;
    }

    private String tokenPrefix(String token) {
        if (token == null || token.isBlank()) {
            return "<empty>";
        }
        return token.length() <= 8 ? token : token.substring(0, 8);
    }

    /**
     * Consumes (removes) a token after use so it cannot be replayed.
     */
    public void consumeToken(String token) {
        InputFormTokenRecord record = tokenRepository.get(token);
        if (record == null || record.consumed()) {
            return;
        }
        tokenRepository.save(new InputFormTokenRecord(
                record.uuid(),
                record.sessionUuid(),
                record.eventId(),
                record.username(),
                record.requestCampaignUuid(),
                record.responderChannel(),
                record.expiresAt(),
                true,
                record.createdAt(),
                System.currentTimeMillis()));
    }

    // ── Maintenance ───────────────────────────────────────────────────────────

    /** Removes expired tokens every 5 minutes. */
    @Scheduled(fixedDelay = 300_000)
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        try (var stream = tokenRepository.search(0, Integer.MAX_VALUE, "createdAt", SortOrder.ASC)) {
            for (InputFormTokenRecord record : stream.toList()) {
                boolean expired = now > record.expiresAt();
                boolean consumedLongAgo = record.consumed()
                        && record.consumedAt() != null
                        && (now - record.consumedAt()) > (TTL_MINUTES * 60_000L);
                if (expired || consumedLongAgo) {
                    tokenRepository.delete(record.uuid());
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("Purged {} expired/consumed input-form tokens", removed);
        }
    }
}
