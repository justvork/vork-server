package sh.vork.attention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.channel.ChannelService;
import sh.vork.orm.DatabaseRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AttentionAlertService {

    private static final Logger log = LoggerFactory.getLogger(AttentionAlertService.class);

    private final DatabaseRepository<AttentionAlert> attentionRepository;
    private final ChannelService channelService;

    public AttentionAlertService(DatabaseRepository<AttentionAlert> attentionRepository,
                                 ChannelService channelService) {
        this.attentionRepository = attentionRepository;
        this.channelService = channelService;
    }

    public AttentionAlert create(CreateAttentionAlertCommand command) {
        validateCreate(command);

        long now = System.currentTimeMillis();
        long when = command.attentionAt() > 0 ? command.attentionAt() : now;

        AttentionAlert alert = new AttentionAlert(
                UUID.randomUUID().toString(),
                normalizeChannels(command.channelNames()),
                command.alertName().trim(),
                command.description().trim(),
                command.resolutionPolicy(),
                command.actionUrl() == null ? "" : command.actionUrl().trim(),
                when,
                command.sourceType(),
                command.sourceId() == null ? "" : command.sourceId().trim(),
                now,
                now);

        attentionRepository.save(alert);
        log.info("Attention alert created [uuid={}, channels={}, policy={}, sourceType={}, sourceId={}]",
                alert.uuid(), alert.channelNames().size(), alert.resolutionPolicy(), alert.sourceType(), alert.sourceId());
        return alert;
    }

    public List<AttentionAlert> listDueAlertsForChannel(String channelName) {
        String normalized = ChannelService.normalize(channelName);
        if (normalized.isBlank()) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        List<AttentionAlert> due = new ArrayList<>();
        try (var stream = attentionRepository.list(0, Integer.MAX_VALUE)) {
            stream.forEach(alert -> {
                if (alert.attentionAt() > now) {
                    return;
                }
                boolean targeted = alert.channelNames().stream()
                        .map(ChannelService::normalize)
                        .anyMatch(normalized::equals);
                if (targeted) {
                    due.add(alert);
                }
            });
        }
        due.sort(Comparator.comparingLong(AttentionAlert::attentionAt)
                .thenComparing(AttentionAlert::updatedAt, Comparator.reverseOrder()));
        return due;
    }

    public long countDueAlertsForChannel(String channelName) {
        return listDueAlertsForChannel(channelName).size();
    }

    public AttentionAlert remind(String channelName, String alertUuid, long attentionAt) {
        if (attentionAt <= System.currentTimeMillis()) {
            throw new IllegalArgumentException("Reminder time must be in the future.");
        }

        AttentionAlert alert = requireAccessibleAlert(channelName, alertUuid);
        AttentionAlert updated = new AttentionAlert(
                alert.uuid(),
                alert.channelNames(),
                alert.alertName(),
                alert.description(),
                alert.resolutionPolicy(),
                alert.actionUrl(),
                attentionAt,
                alert.sourceType(),
                alert.sourceId(),
                alert.createdAt(),
                System.currentTimeMillis());

        attentionRepository.save(updated);
        return updated;
    }

    public void dismiss(String channelName, String alertUuid) {
        AttentionAlert alert = requireAccessibleAlert(channelName, alertUuid);
        if (alert.resolutionPolicy() != AttentionResolutionPolicy.DISMISSABLE) {
            throw new IllegalStateException("Alert is not dismissable.");
        }
        attentionRepository.delete(alert.uuid());
    }

    public int resolveBySource(AttentionSourceType sourceType, String sourceId) {
        if (sourceType == null || sourceId == null || sourceId.isBlank()) {
            return 0;
        }

        List<String> ids = new ArrayList<>();
        try (var stream = attentionRepository.list(0, Integer.MAX_VALUE)) {
            stream.forEach(alert -> {
                if (alert.sourceType() == sourceType && sourceId.equals(alert.sourceId())) {
                    ids.add(alert.uuid());
                }
            });
        }

        ids.forEach(attentionRepository::delete);
        return ids.size();
    }

    private AttentionAlert requireAccessibleAlert(String channelName, String alertUuid) {
        AttentionAlert alert = attentionRepository.get(alertUuid);
        if (alert == null) {
            throw new IllegalArgumentException("Alert not found: " + alertUuid);
        }
        String normalized = ChannelService.normalize(channelName);
        boolean targeted = alert.channelNames().stream()
                .map(ChannelService::normalize)
                .anyMatch(normalized::equals);
        if (!targeted) {
            throw new IllegalArgumentException("Alert does not belong to the current channel.");
        }
        return alert;
    }

    private List<String> normalizeChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return List.of();
        }

        Map<String, String> deduped = new java.util.LinkedHashMap<>();
        for (String channel : channels) {
            String normalized = ChannelService.normalize(channel);
            if (!normalized.isBlank() && !deduped.containsKey(normalized)) {
                deduped.put(normalized, channel.trim());
            }
        }
        return List.copyOf(deduped.values());
    }

    private void validateCreate(CreateAttentionAlertCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Alert command is required.");
        }
        if (command.channelNames() == null || command.channelNames().isEmpty()) {
            throw new IllegalArgumentException("At least one channel is required.");
        }
        for (String channelName : command.channelNames()) {
            if (channelName == null || channelName.isBlank()) {
                throw new IllegalArgumentException("Channel name is required.");
            }
            if (channelService.resolveByChannelName(channelName).isEmpty()) {
                throw new IllegalArgumentException("Unknown channel: " + channelName);
            }
        }
        if (command.alertName() == null || command.alertName().isBlank()) {
            throw new IllegalArgumentException("alertName is required.");
        }
        if (command.description() == null || command.description().isBlank()) {
            throw new IllegalArgumentException("description is required.");
        }
        if (command.resolutionPolicy() == null) {
            throw new IllegalArgumentException("resolutionPolicy is required.");
        }
        if (command.resolutionPolicy() == AttentionResolutionPolicy.ACTION_REQUIRED
                && (command.actionUrl() == null || command.actionUrl().isBlank())) {
            throw new IllegalArgumentException("actionUrl is required for ACTION_REQUIRED alerts.");
        }
    }

    public record CreateAttentionAlertCommand(
            List<String> channelNames,
            String alertName,
            String description,
            AttentionResolutionPolicy resolutionPolicy,
            String actionUrl,
            long attentionAt,
            AttentionSourceType sourceType,
            String sourceId
    ) {
        public CreateAttentionAlertCommand {
            if (sourceType == null) {
                sourceType = AttentionSourceType.CUSTOM;
            }
        }
    }
}
