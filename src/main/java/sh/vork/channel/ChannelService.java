package sh.vork.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregates all ChannelProvider implementations and exposes unified lookup rules.
 */
@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private final List<ChannelProvider> providers;

    public ChannelService(List<ChannelProvider> providers) {
        this.providers = providers == null ? List.of() : providers;
    }

    public Optional<ChannelRef> resolveByChannelName(String channelName) {
        String normalized = normalize(channelName);
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        for (ChannelProvider provider : providers) {
            Optional<ChannelRef> found = provider.resolveByChannelName(channelName);
            if (found.isPresent() && normalize(found.get().channelName()).equals(normalized)) {
                return found;
            }
        }
        return Optional.empty();
    }

    public List<ChannelRef> search(String query, int limit) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }

        int safeLimit = Math.max(1, limit);
        Map<String, ChannelRef> deduped = new LinkedHashMap<>();
        for (ChannelProvider provider : providers) {
            List<ChannelRef> items = provider.search(trimmed, safeLimit);
            for (ChannelRef item : items) {
                String key = normalize(item.channelName());
                if (key.isBlank() || deduped.containsKey(key)) {
                    continue;
                }
                deduped.put(key, item);
                if (deduped.size() >= safeLimit) {
                    return new ArrayList<>(deduped.values());
                }
            }
        }

        return new ArrayList<>(deduped.values());
    }

    public void assertChannelNameAvailable(String channelName) {
        String normalized = normalize(channelName);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Channel name is required.");
        }

        Optional<ChannelRef> existing = resolveByChannelName(channelName);
        if (existing.isPresent()) {
            ChannelRef ref = existing.get();
            log.warn("Channel name conflict [channelName={}, provider={}]", ref.channelName(), ref.providerKey());
            throw new IllegalArgumentException("Channel name already exists: " + ref.channelName());
        }
    }

    public static String normalize(String channelName) {
        if (channelName == null) {
            return "";
        }
        return channelName.trim().toLowerCase(Locale.ROOT);
    }
}
