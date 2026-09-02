package sh.vork.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import sh.vork.ai.slack.SlackSessionRegistry;
import sh.vork.notification.GlobalAddress;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.slack.SlackApiClient;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Channel provider backed by admin-registered Slack global addresses.
 * channelName is the GlobalAddress label.
 */
@Component
public class SlackChannelProvider implements ChannelProvider {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelProvider.class);

    private final DatabaseRepository<GlobalAddress> globalAddressRepository;
    private final DatabaseRepository<NotificationProviderConfig> providerConfigRepository;
    private final SlackApiClient slackApiClient;
    private final SlackSessionRegistry slackSessionRegistry;

    public SlackChannelProvider(DatabaseRepository<GlobalAddress> globalAddressRepository,
                                DatabaseRepository<NotificationProviderConfig> providerConfigRepository,
                                SlackApiClient slackApiClient,
                                SlackSessionRegistry slackSessionRegistry) {
        this.globalAddressRepository = globalAddressRepository;
        this.providerConfigRepository = providerConfigRepository;
        this.slackApiClient = slackApiClient;
        this.slackSessionRegistry = slackSessionRegistry;
    }

    @Override
    public String providerKey() {
        return "slack";
    }

    @Override
    public Optional<ChannelRef> resolveByChannelName(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return Optional.empty();
        }

        String normalized = ChannelService.normalize(channelName);
        try (var stream = globalAddressRepository.search(0, Integer.MAX_VALUE, "label", SortOrder.ASC,
                SearchQuery.eq("mediaType", NotificationMediaType.SLACK))) {
            return stream
                    .filter(this::isSlackAddress)
                    .filter(addr -> ChannelService.normalize(addr.label()).equals(normalized))
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
        try (var stream = globalAddressRepository.search(0, Integer.MAX_VALUE, "label", SortOrder.ASC,
                SearchQuery.eq("mediaType", NotificationMediaType.SLACK))) {
            stream.filter(this::isSlackAddress)
                    .forEach(addr -> {
                        if (result.size() >= limit) {
                            return;
                        }
                        String label = addr.label() == null ? "" : addr.label();
                        if (label.toLowerCase(Locale.ROOT).contains(q)) {
                            result.add(toChannelRef(addr));
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

        Map<String, GlobalAddress> byNormalizedLabel = new LinkedHashMap<>();
        try (var stream = globalAddressRepository.search(0, Integer.MAX_VALUE, "label", SortOrder.ASC,
                SearchQuery.eq("mediaType", NotificationMediaType.SLACK))) {
            stream.filter(this::isSlackAddress)
                    .forEach(addr -> byNormalizedLabel.putIfAbsent(ChannelService.normalize(addr.label()), addr));
        }

        String title = (subject == null || subject.isBlank()) ? "Request Information" : subject.trim();
        String baseBody = (message == null || message.isBlank())
            ? "A campaign request needs your response."
                : message.trim();

        for (Map.Entry<ChannelRef, String> entry : channelRefsToUrl.entrySet()) {
            ChannelRef ref = entry.getKey();
            String actionUrl = entry.getValue();
            if (ref == null || ref.channelName() == null || ref.channelName().isBlank() || actionUrl == null || actionUrl.isBlank()) {
                continue;
            }

            GlobalAddress target = byNormalizedLabel.get(ChannelService.normalize(ref.channelName()));
            if (target == null) {
                log.warn("Slack channel notification target not found [channelName={}]", ref.channelName());
                continue;
            }

            NotificationProviderConfig config = providerConfigRepository.get(target.providerConfigId());
            if (config == null) {
                log.warn("Slack channel notification config missing [channelName={}, configId={}]",
                        ref.channelName(), target.providerConfigId());
                continue;
            }
            if (!"slack".equals(config.providerKey())) {
                log.warn("Global address config is not Slack [channelName={}, providerKey={}]",
                        ref.channelName(), config.providerKey());
                continue;
            }

            String botToken = config.settings().getOrDefault("botToken", "").trim();
            if (botToken.isBlank()) {
                log.warn("Slack channel notification config has no bot token [channelName={}]", ref.channelName());
                continue;
            }

            String sessionUuid = queryParam(actionUrl, "sessionUuid");
            if (sessionUuid == null || sessionUuid.isBlank()) {
                log.warn("Campaign child session UUID missing from action URL [channelName={}]", ref.channelName());
                continue;
            }

            try {
                String prompt = title
                        + "\n"
                        + baseBody
                    + "\n\nPlease reply in this thread with your answer."
                    + " Channel members will see updates here as responses arrive.";
                String rootTs = slackApiClient.sendMessageWithTs(botToken, target.address(), prompt);
                if (rootTs != null && !rootTs.isBlank()) {
                    slackSessionRegistry.bindCampaignChannelSession(
                            target.address(),
                            rootTs,
                            sessionUuid,
                            ref.channelName());
                }
                log.debug("Slack campaign prompt sent and bound [channelName={}, channelId={}, threadTs={}, sessionUuid={}]",
                        ref.channelName(), target.address(), rootTs, sessionUuid);
            } catch (Exception ex) {
                log.warn("Slack campaign notification failed [channelName={}, error={}]",
                        ref.channelName(), ex.getMessage());
            }
        }
    }

    private boolean isSlackAddress(GlobalAddress address) {
        if (address == null || address.providerConfigId() == null || address.providerConfigId().isBlank()) {
            return false;
        }
        NotificationProviderConfig config = providerConfigRepository.get(address.providerConfigId());
        return config != null && "slack".equals(config.providerKey());
    }

    private ChannelRef toChannelRef(GlobalAddress address) {
        String label = address.label() == null ? "" : address.label().trim();
        String displayName = label.isBlank() ? "Slack Channel" : label + " (Slack)";
        return new ChannelRef(label, displayName, providerKey());
    }

    private static String queryParam(String url, String key) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return "";
        }
        String query = url.substring(q + 1);
        for (String token : query.split("&")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
