package sh.vork.channel;

import org.springframework.stereotype.Component;
import sh.vork.orm.DatabaseRepository;
import sh.vork.security.VorkUser;
import sh.vork.scheduling.service.BackgroundNotificationService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Channel provider backed by Vork users.
 * channelName is the immutable username.
 */
@Component
public class UserChannelProvider implements ChannelProvider {

    private final DatabaseRepository<VorkUser> userRepository;
    private final BackgroundNotificationService backgroundNotificationService;

    public UserChannelProvider(DatabaseRepository<VorkUser> userRepository,
                               BackgroundNotificationService backgroundNotificationService) {
        this.userRepository = userRepository;
        this.backgroundNotificationService = backgroundNotificationService;
    }

    @Override
    public String providerKey() {
        return "user";
    }

    @Override
    public Optional<ChannelRef> resolveByChannelName(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return Optional.empty();
        }

        String normalized = channelName.trim().toLowerCase(Locale.ROOT);
        try (var stream = userRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(user -> user.uuid() != null
                            && user.uuid().trim().toLowerCase(Locale.ROOT).equals(normalized))
                    .findFirst()
                    .map(this::toChannelRef);
        }
    }

    @Override
    public List<ChannelRef> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isBlank()) {
            return List.of();
        }

        List<ChannelRef> result = new ArrayList<>();
        try (var stream = userRepository.list(0, Integer.MAX_VALUE)) {
            stream.forEach(user -> {
                if (result.size() >= limit) {
                    return;
                }
                String username = user.uuid() == null ? "" : user.uuid();
                String displayName = user.displayName() == null ? "" : user.displayName();
                String usernameLc = username.toLowerCase(Locale.ROOT);
                String displayLc = displayName.toLowerCase(Locale.ROOT);

                if (usernameLc.contains(q) || displayLc.contains(q)) {
                    result.add(toChannelRef(user));
                }
            });
        }

        result.sort(Comparator.comparing(ChannelRef::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ChannelRef::channelName, String.CASE_INSENSITIVE_ORDER));
        if (result.size() <= limit) {
            return result;
        }
        return new ArrayList<>(result.subList(0, limit));
    }

    @Override
    public void notifyChannelsWithUrls(Map<ChannelRef, String> channelRefsToUrl,
                                       String subject,
                                       String message) {
        if (channelRefsToUrl == null || channelRefsToUrl.isEmpty()) {
            return;
        }

        Map<String, String> userToUrl = new LinkedHashMap<>();
        for (Map.Entry<ChannelRef, String> entry : channelRefsToUrl.entrySet()) {
            ChannelRef ref = entry.getKey();
            String url = entry.getValue();
            if (ref == null || ref.channelName() == null || ref.channelName().isBlank() || url == null || url.isBlank()) {
                continue;
            }
            userToUrl.put(ref.channelName(), url);
        }

        if (!userToUrl.isEmpty()) {
            backgroundNotificationService.notifyUsersWithUrls(userToUrl, subject, message);
        }
    }

    private ChannelRef toChannelRef(VorkUser user) {
        String channelName = user.uuid() == null ? "" : user.uuid();
        String displayName = user.displayName() == null || user.displayName().isBlank()
                ? channelName
                : user.displayName() + " (" + channelName + ")";
        return new ChannelRef(channelName, displayName, providerKey());
    }
}
