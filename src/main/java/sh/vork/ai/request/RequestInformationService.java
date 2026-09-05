package sh.vork.ai.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.lifecycle.AgentTemplateSeeder;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.attention.AttentionAlertService;
import sh.vork.attention.AttentionResolutionPolicy;
import sh.vork.attention.AttentionSourceType;
import sh.vork.channel.ChannelRef;
import sh.vork.channel.ChannelService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;
import sh.vork.web.RequestOriginContext;
import sh.vork.setup.SystemSettings;
import sh.vork.setup.SystemSettingsService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RequestInformationService {

    private static final Logger log = LoggerFactory.getLogger(RequestInformationService.class);
    private static final String INFORMATION_REQUEST_SOURCE = "Information Request";

    private static final Pattern AT_LEAST_PATTERN = Pattern.compile("\\bat\\s+least\\s+(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final String REQUEST_CAMPAIGN_RESPONSE_INTENT = "REQUEST_CAMPAIGN_RESPONSE";

    private final DatabaseRepository<RequestInformationCampaign> campaignRepository;
    private final DatabaseRepository<RequestInformationResponse> responseRepository;
    private final DatabaseRepository<AiSession> sessionRepository;
    private final ChannelService channelService;
    private final AttentionAlertService attentionAlertService;
    private final SystemSettingsService systemSettingsService;
    private final ObjectMapper objectMapper;
    private final String configuredRelayHost;

    @Value("${vork.request-information.child-sessions.enabled:true}")
    private boolean childSessionRoutingEnabled;

    public RequestInformationService(RepositoryFactory repositoryFactory,
                                     ChannelService channelService,
                                     AttentionAlertService attentionAlertService,
                                     SystemSettingsService systemSettingsService,
                                     ObjectMapper objectMapper,
                                     @Value("${vork.relay.host:}") String configuredRelayHost) {
        this.campaignRepository = repositoryFactory.create(RequestInformationCampaign.class);
        this.responseRepository = repositoryFactory.create(RequestInformationResponse.class);
        this.sessionRepository = repositoryFactory.create(AiSession.class);
        this.channelService = channelService;
        this.attentionAlertService = attentionAlertService;
        this.systemSettingsService = systemSettingsService;
        this.objectMapper = objectMapper;
        this.configuredRelayHost = configuredRelayHost;
    }

    public LaunchResult createCampaignAndDispatch(CreateCampaignCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("request command is required");
        }
        if (command.sessionUuid() == null || command.sessionUuid().isBlank()) {
            throw new IllegalArgumentException("sessionUuid is required");
        }
        if (command.eventId() == null || command.eventId().isBlank()) {
            throw new IllegalArgumentException("eventId is required");
        }
        if (command.promptText() == null || command.promptText().isBlank()) {
            throw new IllegalArgumentException("promptText is required");
        }

        List<String> channels = resolveChannels(command.channelNames());
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("At least one valid channel is required");
        }

        PolicyResolution resolvedPolicy = resolvePolicy(command.responsePolicy(), command.quorumCount(), channels.size(), command.promptText());

        long now = System.currentTimeMillis();
        String campaignUuid = UUID.randomUUID().toString();
        RequestInformationCampaign campaign = new RequestInformationCampaign(
                campaignUuid,
                command.sessionUuid(),
                command.eventId(),
                safe(command.createdBy()),
                command.promptText().trim(),
                List.copyOf(channels),
                resolvedPolicy.policy(),
                resolvedPolicy.requiredResponses(),
                List.of(),
                RequestCampaignStatus.OPEN,
                false,
                now,
                now,
                null,
                command.sessionUuid(),
                Map.of(),
                RequestResponseRouteMode.EXTERNAL_FORM,
                childSessionRoutingEnabled);

                String responderIntroOverride = (command.alertDescription() != null && !command.alertDescription().isBlank())
                    ? command.alertDescription().trim()
                    : command.promptText();
                campaign = maybeEnableChildSessionRouting(campaign, responderIntroOverride);

        campaignRepository.save(campaign);

        String baseUrl = resolveRequestBaseUrl(command.sessionUuid());
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Request base URL unavailable; using relative chat links [session={}]",
                    command.sessionUuid());
            baseUrl = "";
        }

        Map<String, String> channelUrls = new LinkedHashMap<>();
        List<String> alertUuids = new ArrayList<>();
        for (String channel : channels) {
            String childSessionUuid = findChildSessionUuidForChannel(campaign.childSessionUuidsByChannel(), channel);
            if (campaign.responseRouteMode() != RequestResponseRouteMode.CHILD_SESSION
                    || childSessionUuid == null
                    || childSessionUuid.isBlank()) {
                throw new IllegalStateException("Request campaign requires child-session routing with a valid child session link");
            }
            String url = buildChildSessionChatUrl(baseUrl, childSessionUuid);
            channelUrls.put(channel, url);

            AttentionAlertService.CreateAttentionAlertCommand alertCommand =
                    new AttentionAlertService.CreateAttentionAlertCommand(
                            List.of(channel),
                            command.alertName() == null || command.alertName().isBlank()
                                    ? "Information Requested"
                                    : command.alertName().trim(),
                        command.alertDescription() == null || command.alertDescription().isBlank()
                            ? command.promptText().trim()
                            : command.alertDescription().trim(),
                            parseAlertResolutionPolicy(command.alertResolutionPolicy()),
                            url,
                            command.attentionAt() == null ? now : command.attentionAt(),
                            AttentionSourceType.CUSTOM,
                            "request-campaign:" + campaignUuid);

            alertUuids.add(attentionAlertService.create(alertCommand).uuid());
        }

        if (command.sendNotifications()) {
            String notificationMessage = (command.alertDescription() != null && !command.alertDescription().isBlank())
                ? command.alertDescription().trim()
                : command.promptText().trim();
            channelService.notifyChannelsWithUrls(
                channelUrls,
                "Request Information",
                notificationMessage);
        }

        log.info("Request-information campaign created [campaign={}, session={}, event={}, channels={}, policy={}, requiredResponses={}, notifications={}]",
                campaignUuid, command.sessionUuid(), command.eventId(), channels.size(),
                resolvedPolicy.policy(), resolvedPolicy.requiredResponses(), command.sendNotifications());
        log.debug("Request-information campaign route metadata [campaign={}, routeMode={}, parentSession={}, childRoutingEnabled={}, childLinks={}]",
            campaignUuid,
            campaign.responseRouteMode(),
            campaign.parentSessionUuid(),
            campaign.childSessionRoutingEnabled(),
            campaign.childSessionUuidsByChannel().size());

        return new LaunchResult(
                campaignUuid,
                resolvedPolicy.policy(),
                resolvedPolicy.requiredResponses(),
                channels.size(),
                Map.copyOf(channelUrls),
                List.copyOf(alertUuids));
    }

        public String ensureCampaignForSuspension(String sessionUuid,
                              String eventId,
                              String sessionUsername,
                              String toolName,
                              String promptText,
                              ToolSuspensionException.SuspensionCampaign requestedCampaign) {
        RequestInformationCampaign existing = campaignRepository.get(
            SearchQuery.eq("sessionUuid", sessionUuid),
            SearchQuery.eq("eventId", eventId));
        if (existing != null) {
            boolean exactMatch = sessionUuid.equals(existing.sessionUuid())
                    && eventId.equals(existing.eventId());
            if (exactMatch) {
                return existing.uuid();
            }
            log.warn("Ignoring non-exact existing campaign lookup result [requestedSession={}, requestedEvent={}, foundCampaign={}, foundSession={}, foundEvent={}]",
                    sessionUuid,
                    eventId,
                    existing.uuid(),
                    existing.sessionUuid(),
                    existing.eventId());
        }

        List<String> targetChannels = requestedCampaign == null
            ? List.of(sessionUsername)
            : resolveChannels(requestedCampaign.channelNames());
        if (targetChannels.isEmpty()) {
            targetChannels = List.of(sessionUsername);
        }

        RequestResponsePolicy requestedPolicy = requestedCampaign == null
            ? RequestResponsePolicy.FIRST
            : (requestedCampaign.responsePolicy() == null ? RequestResponsePolicy.AUTO : requestedCampaign.responsePolicy());
        Integer quorumCount = requestedCampaign == null ? null : requestedCampaign.quorumCount();
        PolicyResolution resolvedPolicy = resolvePolicy(requestedPolicy, quorumCount, targetChannels.size(), promptText);

        long now = System.currentTimeMillis();
        RequestInformationCampaign campaign = new RequestInformationCampaign(
            UUID.randomUUID().toString(),
            sessionUuid,
            eventId,
            safe(sessionUsername),
            safe(promptText).isBlank() ? "Tool '" + safe(toolName) + "' requested input." : promptText.trim(),
            List.copyOf(targetChannels),
            resolvedPolicy.policy(),
            resolvedPolicy.requiredResponses(),
            List.of(),
            RequestCampaignStatus.OPEN,
            false,
            now,
            now,
            null,
            sessionUuid,
            Map.of(),
            RequestResponseRouteMode.EXTERNAL_FORM,
            childSessionRoutingEnabled);

        campaign = maybeEnableChildSessionRouting(
            campaign,
            requestedCampaign == null ? null : requestedCampaign.alertDescription());
        campaignRepository.save(campaign);

        if (requestedCampaign != null) {
            dispatchCampaignRequests(campaign,
                requestedCampaign.sendNotifications() == null || requestedCampaign.sendNotifications(),
                requestedCampaign.alertName(),
                campaign.promptText(),
                requestedCampaign.alertResolutionPolicy(),
                requestedCampaign.attentionAt());
        }

        log.info("Suspension campaign materialized [campaign={}, session={}, event={}, channels={}, policy={}, requiredResponses={}, explicit={}]",
            campaign.uuid(), sessionUuid, eventId, targetChannels.size(),
            campaign.policy(), campaign.requiredResponses(), requestedCampaign != null);
        log.debug("Suspension campaign route metadata [campaign={}, routeMode={}, parentSession={}, childRoutingEnabled={}, childLinks={}]",
            campaign.uuid(),
            campaign.responseRouteMode(),
            campaign.parentSessionUuid(),
            campaign.childSessionRoutingEnabled(),
            campaign.childSessionUuidsByChannel().size());
        return campaign.uuid();
        }

    public ResponseGateResult recordResponseAndEvaluate(String campaignUuid,
                                                        String responderChannel,
                                                        String action,
                                                        Map<String, String> fields) {
        RequestInformationCampaign campaign = requireCampaign(campaignUuid);
        if (campaign.status() != RequestCampaignStatus.OPEN) {
            return new ResponseGateResult(false, false, campaign.uuid(), 0, campaign.requiredResponses(),
                    "This request is no longer accepting responses.");
        }

        String normalizedResponder = ChannelService.normalize(responderChannel);
        if (normalizedResponder == null || normalizedResponder.isBlank()) {
            throw new IllegalArgumentException("responder channel is required");
        }

        List<String> normalizedTargets = campaign.targetChannels().stream()
                .map(ChannelService::normalize)
                .toList();
        if (!normalizedTargets.contains(normalizedResponder)) {
            return new ResponseGateResult(false, false, campaign.uuid(), 0, campaign.requiredResponses(),
                    "This response link does not match an active campaign recipient.");
        }

        RequestInformationResponse existing = responseRepository.get(
                SearchQuery.eq("campaignUuid", campaign.uuid()),
                SearchQuery.eq("responderChannel", normalizedResponder));
        if (existing != null) {
            int alreadyCount = campaign.respondedChannels() == null ? 0 : campaign.respondedChannels().size();
            return new ResponseGateResult(true, false, campaign.uuid(), alreadyCount, campaign.requiredResponses(),
                    "Your response was already recorded for this request.");
        }

        long now = System.currentTimeMillis();
        responseRepository.save(new RequestInformationResponse(
                UUID.randomUUID().toString(),
                campaign.uuid(),
                normalizedResponder,
                normalizeAction(action),
                fields == null ? Map.of() : Map.copyOf(fields),
                now));

        LinkedHashSet<String> responders = new LinkedHashSet<>(campaign.respondedChannels() == null
                ? List.of()
                : campaign.respondedChannels().stream().map(ChannelService::normalize).toList());
        responders.add(normalizedResponder);

        int respondedCount = responders.size();
        boolean satisfied = respondedCount >= campaign.requiredResponses();

        RequestInformationCampaign updated = new RequestInformationCampaign(
                campaign.uuid(),
                campaign.sessionUuid(),
                campaign.eventId(),
                campaign.createdBy(),
                campaign.promptText(),
                campaign.targetChannels(),
                campaign.policy(),
                campaign.requiredResponses(),
                List.copyOf(responders),
                satisfied ? RequestCampaignStatus.SATISFIED : RequestCampaignStatus.OPEN,
                campaign.resumeTriggered(),
                campaign.createdAt(),
                now,
                satisfied ? Long.valueOf(now) : campaign.satisfiedAt(),
                campaign.parentSessionUuid(),
                campaign.childSessionUuidsByChannel(),
                campaign.responseRouteMode(),
                campaign.childSessionRoutingEnabled());

        campaignRepository.save(updated);

        if (satisfied) {
            attentionAlertService.resolveBySource(AttentionSourceType.CUSTOM, "request-campaign:" + campaign.uuid());
        }

        String message = satisfied
                ? "Thanks. Required responses received; processing will continue."
                : "Thanks. Response recorded (" + respondedCount + "/" + campaign.requiredResponses() + ").";

        return new ResponseGateResult(true, satisfied, campaign.uuid(), respondedCount, campaign.requiredResponses(), message);
    }

    public boolean markResumeStarted(String campaignUuid) {
        synchronized (this) {
            RequestInformationCampaign campaign = requireCampaign(campaignUuid);
            if (campaign.resumeTriggered()) {
                return false;
            }
            if (campaign.status() != RequestCampaignStatus.SATISFIED) {
                return false;
            }

            RequestInformationCampaign updated = new RequestInformationCampaign(
                    campaign.uuid(),
                    campaign.sessionUuid(),
                    campaign.eventId(),
                    campaign.createdBy(),
                    campaign.promptText(),
                    campaign.targetChannels(),
                    campaign.policy(),
                    campaign.requiredResponses(),
                    campaign.respondedChannels(),
                    campaign.status(),
                    true,
                    campaign.createdAt(),
                    System.currentTimeMillis(),
                    campaign.satisfiedAt(),
                    campaign.parentSessionUuid(),
                    campaign.childSessionUuidsByChannel(),
                    campaign.responseRouteMode(),
                    campaign.childSessionRoutingEnabled());

            campaignRepository.save(updated);
            return true;
        }
    }

    public Map<String, String> buildResumeFields(String campaignUuid) {
        RequestInformationCampaign campaign = requireCampaign(campaignUuid);

        List<RequestInformationResponse> responses;
        try (var stream = responseRepository.search(0, Integer.MAX_VALUE, "createdAt", SortOrder.ASC,
                SearchQuery.eq("campaignUuid", campaign.uuid()))) {
            responses = stream.toList();
        }

        List<Map<String, Object>> payload = responses.stream().map(resp -> Map.<String, Object>of(
                "channel", resp.responderChannel(),
                "action", resp.action(),
                "fields", resp.fields(),
                "createdAt", resp.createdAt())).toList();

        String responsesJson;
        try {
            responsesJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize request responses", ex);
        }

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("requestCampaignId", campaign.uuid());
        fields.put("responsesJson", responsesJson);
        fields.put("responseCount", String.valueOf(payload.size()));
        return fields;
    }

    public RequestInformationCampaign getCampaign(String campaignUuid) {
        return requireCampaign(campaignUuid);
    }

    public RequestInformationCampaign findCampaignForSessionEvent(String sessionUuid, String eventId) {
        if (sessionUuid == null || sessionUuid.isBlank() || eventId == null || eventId.isBlank()) {
            return null;
        }
        return campaignRepository.get(
                SearchQuery.eq("sessionUuid", sessionUuid),
                SearchQuery.eq("eventId", eventId));
    }

    public RequestInformationCampaign findOpenCampaignForSession(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return null;
        }
        try (var stream = campaignRepository.search(0, Integer.MAX_VALUE, "createdAt", SortOrder.DESC,
                SearchQuery.eq("sessionUuid", sessionUuid),
                SearchQuery.eq("status", RequestCampaignStatus.OPEN))) {
            return stream.findFirst().orElse(null);
        }
    }

    public RequestInformationCampaign findLatestCampaignForSession(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return null;
        }
        try (var stream = campaignRepository.search(0, 1, "createdAt", SortOrder.DESC,
                SearchQuery.eq("sessionUuid", sessionUuid))) {
            return stream.findFirst().orElse(null);
        }
    }

    public boolean isChildSessionRoutingEnabled() {
        return childSessionRoutingEnabled;
    }

    public boolean cancelCampaign(String campaignUuid) {
        RequestInformationCampaign campaign = requireCampaign(campaignUuid);
        if (campaign.status() != RequestCampaignStatus.OPEN) {
            return false;
        }

        RequestInformationCampaign updated = new RequestInformationCampaign(
                campaign.uuid(),
                campaign.sessionUuid(),
                campaign.eventId(),
                campaign.createdBy(),
                campaign.promptText(),
                campaign.targetChannels(),
                campaign.policy(),
                campaign.requiredResponses(),
                campaign.respondedChannels(),
                RequestCampaignStatus.CANCELLED,
                campaign.resumeTriggered(),
                campaign.createdAt(),
                System.currentTimeMillis(),
                campaign.satisfiedAt(),
                campaign.parentSessionUuid(),
                campaign.childSessionUuidsByChannel(),
                campaign.responseRouteMode(),
                campaign.childSessionRoutingEnabled());
        campaignRepository.save(updated);

        attentionAlertService.resolveBySource(AttentionSourceType.CUSTOM, "request-campaign:" + campaign.uuid());
        return true;
    }

    private RequestInformationCampaign requireCampaign(String campaignUuid) {
        RequestInformationCampaign campaign = campaignRepository.get(campaignUuid);
        if (campaign == null) {
            throw new IllegalArgumentException("Request campaign not found: " + campaignUuid);
        }
        return campaign;
    }

    private RequestInformationCampaign maybeEnableChildSessionRouting(RequestInformationCampaign campaign,
                                                                      String responderIntroOverride) {
        if (campaign == null) {
            return null;
        }
        if (!childSessionRoutingEnabled) {
            throw new IllegalStateException("Request campaign requires child-session routing; out-of-chat form routing is disabled");
        }

        Map<String, String> childLinks = createChildSessionsForCampaign(campaign, responderIntroOverride);
        if (childLinks.isEmpty()) {
            throw new IllegalStateException("Child-session routing enabled but no child sessions were created for request campaign "
                    + campaign.uuid());
        }

        RequestInformationCampaign updated = new RequestInformationCampaign(
                campaign.uuid(),
                campaign.sessionUuid(),
                campaign.eventId(),
                campaign.createdBy(),
                campaign.promptText(),
                campaign.targetChannels(),
                campaign.policy(),
                campaign.requiredResponses(),
                campaign.respondedChannels(),
                campaign.status(),
                campaign.resumeTriggered(),
                campaign.createdAt(),
                campaign.updatedAt(),
                campaign.satisfiedAt(),
                campaign.sessionUuid(),
                childLinks,
                RequestResponseRouteMode.CHILD_SESSION,
                true);
        log.info("Child session links created for request campaign [campaign={}, session={}, recipients={}, links={}]",
                campaign.uuid(), campaign.sessionUuid(),
                campaign.targetChannels() == null ? 0 : campaign.targetChannels().size(),
                childLinks.size());
        return updated;
    }

    private Map<String, String> createChildSessionsForCampaign(RequestInformationCampaign campaign,
                                                               String responderIntroOverride) {
        if (campaign == null || campaign.targetChannels() == null || campaign.targetChannels().isEmpty()) {
            return Map.of();
        }

        AiSession parentSession = sessionRepository.get(campaign.sessionUuid());
        String provider = (parentSession != null && parentSession.provider() != null && !parentSession.provider().isBlank())
                ? parentSession.provider()
                : AiProvider.GEMINI.name();
        String modelId = parentSession == null ? null : parentSession.modelId();

        LinkedHashMap<String, String> links = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String channel : campaign.targetChannels()) {
            if (channel == null || channel.isBlank()) {
                continue;
            }
            String normalized = ChannelService.normalize(channel);
            String childUsername = (normalized == null || normalized.isBlank()) ? channel.trim() : normalized;
                String requesterParticipant = resolveDisplayNameOrFallback(
                    campaign == null ? null : campaign.createdBy());
                if (requesterParticipant == null || requesterParticipant.isBlank()) {
                requesterParticipant = "requester";
                }

            AiSession existingChild = findExistingChildSession(campaign.sessionUuid(), channel);
            if (existingChild != null) {
            Map<String, String> childEnv = new LinkedHashMap<>(existingChild.environmentVariables() == null
                ? AiSession.defaultEnvironmentVariables()
                : existingChild.environmentVariables());
            childEnv.put("REQUEST_CAMPAIGN_ID", campaign.uuid());
            childEnv.put("REQUEST_CAMPAIGN_PARENT_SESSION_UUID", campaign.sessionUuid());
            childEnv.put("REQUEST_CAMPAIGN_RECIPIENT_CHANNEL", channel);
            childEnv.put("REQUEST_CAMPAIGN_ROUTE_MODE", RequestResponseRouteMode.CHILD_SESSION.name());

            List<AiChatMessage> updatedMessages = new ArrayList<>(existingChild.messages() == null
                ? List.of()
                : existingChild.messages());
            updatedMessages.add(new AiChatMessage(
                UUID.randomUUID().toString(),
                "EXTERNAL",
                buildResponderIntroMessage(campaign, channel, responderIntroOverride),
                now,
                null,
                INFORMATION_REQUEST_SOURCE,
                requesterParticipant));
            updatedMessages.add(new AiChatMessage(
                UUID.randomUUID().toString(),
                "PROMPT_REQUIRED",
                buildChildSessionPromptFrameJson(campaign, channel),
                now,
                null));

            AiSession updatedChild = new AiSession(
                existingChild.uuid(),
                existingChild.provider(),
                existingChild.originMode(),
                existingChild.username(),
                existingChild.name(),
                existingChild.createdAt(),
                existingChild.currentRoundCount(),
                List.copyOf(updatedMessages),
                childEnv,
                AiSessionStatus.RUNNING,
                existingChild.activeAgentTemplateId(),
                existingChild.modelId(),
                existingChild.skillStack(),
                existingChild.sessionSkillUuids(),
                existingChild.sessionToolIds());
            sessionRepository.save(updatedChild);
            links.put(channel, existingChild.uuid());
            log.debug("Reused child request session for campaign [campaign={}, parentSession={}, childSession={}, channel={}, username={}]",
                campaign.uuid(), campaign.sessionUuid(), existingChild.uuid(), channel, childUsername);
            continue;
            }

            String childSessionUuid = UUID.randomUUID().toString();
            Map<String, String> childEnv = AiSession.defaultEnvironmentVariables();
            childEnv.put("REQUEST_CAMPAIGN_ID", campaign.uuid());
            childEnv.put("REQUEST_CAMPAIGN_PARENT_SESSION_UUID", campaign.sessionUuid());
            childEnv.put("REQUEST_CAMPAIGN_RECIPIENT_CHANNEL", channel);
            childEnv.put("REQUEST_CAMPAIGN_ROUTE_MODE", RequestResponseRouteMode.CHILD_SESSION.name());

            List<AiChatMessage> childMessages = List.of(
                    new AiChatMessage(
                            UUID.randomUUID().toString(),
                            "EXTERNAL",
                        buildResponderIntroMessage(campaign, channel, responderIntroOverride),
                            now,
                            null,
                            INFORMATION_REQUEST_SOURCE,
                            requesterParticipant),
                    new AiChatMessage(
                        UUID.randomUUID().toString(),
                        "PROMPT_REQUIRED",
                        buildChildSessionPromptFrameJson(campaign, channel),
                        now,
                        null));

            AiSession childSession = new AiSession(
                    childSessionUuid,
                    provider,
                    SessionOriginMode.WEB,
                    childUsername,
                    buildChildSessionName(campaign),
                    now,
                    0,
                    childMessages,
                    childEnv,
                    AiSessionStatus.RUNNING,
                    AgentTemplateSeeder.UUID_CONCIERGE,
                    modelId,
                    null,
                    null,
                    List.of());

            sessionRepository.save(childSession);
            links.put(channel, childSessionUuid);
            log.debug("Child request session created [campaign={}, parentSession={}, childSession={}, channel={}, username={}]",
                    campaign.uuid(), campaign.sessionUuid(), childSessionUuid, channel, childUsername);
        }

        return Map.copyOf(links);
    }

    private AiSession findExistingChildSession(String parentSessionUuid, String recipientChannel) {
        if (parentSessionUuid == null || parentSessionUuid.isBlank() || recipientChannel == null || recipientChannel.isBlank()) {
            return null;
        }
        try (var stream = sessionRepository.search(0, 1, "createdAt", SortOrder.DESC,
                SearchQuery.eq("originMode", SessionOriginMode.WEB.name()),
                SearchQuery.eq("environmentVariables.REQUEST_CAMPAIGN_PARENT_SESSION_UUID", parentSessionUuid),
                SearchQuery.eq("environmentVariables.REQUEST_CAMPAIGN_RECIPIENT_CHANNEL", recipientChannel))) {
            return stream.findFirst().orElse(null);
        }
    }

    private String buildChildSessionPromptFrameJson(RequestInformationCampaign campaign, String channel) {
        String title = "Information Requested";
        String promptText = campaign == null || campaign.promptText() == null || campaign.promptText().isBlank()
                ? "Please provide the requested information."
                : campaign.promptText();

        InteractionFormSchema schema = new InteractionFormSchema(
                REQUEST_CAMPAIGN_RESPONSE_INTENT,
                title,
                promptText,
                List.of(new FormField(
                        "message",
                        "textarea",
                        "Your response",
                "",
                        true,
                        FieldSource.CONVERSATION,
                null),
                new FormField(
                    "requestCampaignId",
                    "hidden",
                    "",
                    "",
                    campaign == null || campaign.uuid() == null ? "" : campaign.uuid(),
                    false,
                    FieldSource.CONTEXT,
                    null)),
                List.of(new FormAction("SUBMIT", "Submit Response", "success")));

        UiEventFrame frame = new UiEventFrame(
                UUID.randomUUID().toString(),
                "PROMPT_REQUIRED",
                REQUEST_CAMPAIGN_RESPONSE_INTENT,
            promptText,
                schema);

        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize child-session prompt frame", ex);
        }
    }

    private String buildResponderIntroMessage(RequestInformationCampaign campaign,
                                              String responderChannel,
                                              String responderIntroOverride) {
        if (responderIntroOverride != null && !responderIntroOverride.isBlank()) {
            return responderIntroOverride.trim();
        }

        String requester = campaign == null || campaign.createdBy() == null || campaign.createdBy().isBlank()
                ? "Someone"
                : resolveDisplayNameOrFallback(campaign.createdBy().trim());
        String question = campaign == null || campaign.promptText() == null || campaign.promptText().isBlank()
                ? "some information"
                : campaign.promptText().trim();
        String responder = resolveDisplayNameOrFallback(responderChannel);
        if (responder == null || responder.isBlank()) {
            return requester + " would like to know: " + question;
        }
        return requester + " would like to know: " + question + " Please respond, " + responder + ".";
    }

    private String resolveDisplayNameOrFallback(String channelLike) {
        if (channelLike == null || channelLike.isBlank()) {
            return "";
        }
        String lookup = channelLike.trim();
        try {
            java.util.Optional<ChannelRef> resolvedOptional = channelService.resolveByChannelName(lookup);
            ChannelRef resolved = resolvedOptional == null ? null : resolvedOptional.orElse(null);
            if (resolved != null && resolved.displayName() != null && !resolved.displayName().isBlank()) {
                return normalizeParticipantLabel(resolved.displayName().trim(), lookup);
            }
        } catch (Exception ex) {
            log.debug("Failed to resolve display name for channel [{}]: {}", lookup, ex.getMessage());
        }
        return normalizeParticipantLabel(lookup, lookup);
    }

    private static String normalizeParticipantLabel(String label, String fallbackChannel) {
        if (label == null) {
            return fallbackChannel == null ? "" : fallbackChannel.trim();
        }
        String trimmed = label.trim();
        int open = trimmed.lastIndexOf(" (");
        int close = trimmed.endsWith(")") ? trimmed.length() - 1 : -1;
        if (open > 0 && close > open + 2) {
            String base = trimmed.substring(0, open).trim();
            String bracket = trimmed.substring(open + 2, close).trim();
            if (!base.isBlank() && !bracket.isBlank()) {
                if (base.equalsIgnoreCase(bracket)) {
                    return base;
                }
                if (fallbackChannel != null && !fallbackChannel.isBlank()
                        && bracket.equalsIgnoreCase(fallbackChannel.trim())) {
                    return base;
                }
            }
        }
        return trimmed;
    }

    private String buildChildSessionName(RequestInformationCampaign campaign) {
        String base = campaign == null ? "Campaign Request" : safe(campaign.promptText());
        if (base.isBlank()) {
            return "Campaign Request";
        }
        String trimmed = base.trim();
        if (trimmed.length() <= 56) {
            return "Request: " + trimmed;
        }
        return "Request: " + trimmed.substring(0, 56) + "...";
    }

    private List<String> resolveChannels(List<String> requestedChannels) {
        if (requestedChannels == null || requestedChannels.isEmpty()) {
            return List.of();
        }

        LinkedHashMap<String, String> channels = new LinkedHashMap<>();
        for (String requested : requestedChannels) {
            if (requested == null || requested.isBlank()) {
                continue;
            }
            String normalized = ChannelService.normalize(requested);
            if (normalized == null || normalized.isBlank()) {
                continue;
            }

            ChannelRef resolved = channelService.resolveByChannelName(requested).orElse(null);
            String channelName = resolved == null ? requested.trim() : resolved.channelName();
            channels.put(ChannelService.normalize(channelName), channelName);
        }

        return List.copyOf(channels.values());
    }

    private PolicyResolution resolvePolicy(RequestResponsePolicy requested,
                                           Integer quorumCount,
                                           int channelCount,
                                           String promptText) {
        if (channelCount <= 0) {
            throw new IllegalArgumentException("At least one channel is required");
        }

        RequestResponsePolicy policy = requested == null ? RequestResponsePolicy.AUTO : requested;
        if (policy == RequestResponsePolicy.AUTO) {
            policy = inferAutoPolicy(promptText);
        }

        int requiredResponses;
        switch (policy) {
            case FIRST -> requiredResponses = 1;
            case ALL -> requiredResponses = channelCount;
            case QUORUM -> {
                int quorum = quorumCount == null ? Math.min(channelCount, 1) : quorumCount;
                if (quorum < 1 || quorum > channelCount) {
                    throw new IllegalArgumentException("quorumCount must be between 1 and the number of channels");
                }
                requiredResponses = quorum;
            }
            default -> throw new IllegalArgumentException("Unsupported response policy: " + policy);
        }

        return new PolicyResolution(policy, requiredResponses);
    }

    private RequestResponsePolicy inferAutoPolicy(String promptText) {
        String text = safe(promptText).toLowerCase(Locale.ROOT);
        Matcher atLeastMatcher = AT_LEAST_PATTERN.matcher(text);
        if (atLeastMatcher.find()) {
            return RequestResponsePolicy.QUORUM;
        }
        if (text.contains("everyone") || text.contains("all participants") || text.contains("all")) {
            return RequestResponsePolicy.ALL;
        }
        if (text.contains("any") || text.contains("first")) {
            return RequestResponsePolicy.FIRST;
        }
        return RequestResponsePolicy.ALL;
    }

    private String resolveRequestBaseUrl(String sessionUuid) {
        String fromCurrentRequest = RequestOriginContext.resolveBaseUrlFromCurrentRequest();
        if (fromCurrentRequest != null && !fromCurrentRequest.isBlank()) {
            return trimTrailingSlash(fromCurrentRequest);
        }
        SystemSettings settings = systemSettingsService.getGlobal();
        if (settings != null && settings.appBaseUrl() != null && !settings.appBaseUrl().isBlank()) {
            return trimTrailingSlash(settings.appBaseUrl());
        }
        if (configuredRelayHost != null && !configuredRelayHost.isBlank()) {
            return trimTrailingSlash(configuredRelayHost);
        }
        log.debug("Request thread-local base URL unavailable; using relative input-form links [session={}]",
                sessionUuid);
        return null;
    }

    private static String buildChildSessionChatUrl(String baseUrl, String childSessionUuid) {
        return safe(baseUrl)
                + "/chat?sessionUuid="
                + URLEncoder.encode(childSessionUuid, StandardCharsets.UTF_8);
    }

    private static String findChildSessionUuidForChannel(Map<String, String> channelToSessionUuid, String channel) {
        if (channelToSessionUuid == null || channelToSessionUuid.isEmpty() || channel == null || channel.isBlank()) {
            return null;
        }

        String direct = channelToSessionUuid.get(channel);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }

        String normalized = ChannelService.normalize(channel);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }

        for (Map.Entry<String, String> entry : channelToSessionUuid.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String candidate = ChannelService.normalize(entry.getKey());
            if (normalized.equals(candidate)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "ONCE";
        }
        return action.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private AttentionResolutionPolicy parseAlertResolutionPolicy(String rawPolicy) {
        if (rawPolicy == null || rawPolicy.isBlank()) {
            return AttentionResolutionPolicy.ACTION_REQUIRED;
        }
        try {
            return AttentionResolutionPolicy.valueOf(rawPolicy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid request-information alertResolutionPolicy [{}], defaulting to ACTION_REQUIRED", rawPolicy);
            return AttentionResolutionPolicy.ACTION_REQUIRED;
        }
    }

    private void dispatchCampaignRequests(RequestInformationCampaign campaign,
                                          boolean sendNotifications,
                                          String alertName,
                                          String alertDescription,
                                          String alertResolutionPolicy,
                                          Long attentionAt) {
        String baseUrl = resolveRequestBaseUrl(campaign.sessionUuid());
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Request base URL unavailable; using relative chat links [session={}, campaign={}]",
                campaign.sessionUuid(), campaign.uuid());
            baseUrl = "";
        }

        Map<String, String> channelUrls = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        long effectiveAttentionAt = now;
        if (attentionAt != null && attentionAt > now) {
            log.warn("Ignoring future request-information attentionAt to keep suspension alerts immediately visible [campaign={}, requestedAttentionAt={}, now={}]",
                campaign.uuid(), attentionAt, now);
        }
        for (String channel : campaign.targetChannels()) {
            log.debug("Dispatching request campaign alert [campaign={}, session={}, event={}, channel={}]",
                campaign.uuid(), campaign.sessionUuid(), campaign.eventId(), channel);
            String childSessionUuid = findChildSessionUuidForChannel(campaign.childSessionUuidsByChannel(), channel);
            if (campaign.responseRouteMode() != RequestResponseRouteMode.CHILD_SESSION
                    || childSessionUuid == null
                    || childSessionUuid.isBlank()) {
                throw new IllegalStateException("Request campaign requires child-session routing with a valid child session link");
            }
            String url = buildChildSessionChatUrl(baseUrl, childSessionUuid);
            channelUrls.put(channel, url);

            AttentionAlertService.CreateAttentionAlertCommand alertCommand =
                    new AttentionAlertService.CreateAttentionAlertCommand(
                            List.of(channel),
                            alertName == null || alertName.isBlank() ? "Information Requested" : alertName.trim(),
                            alertDescription == null || alertDescription.isBlank()
                                    ? campaign.promptText()
                                    : alertDescription.trim(),
                            parseAlertResolutionPolicy(alertResolutionPolicy),
                            url,
                                effectiveAttentionAt,
                            AttentionSourceType.CUSTOM,
                            "request-campaign:" + campaign.uuid());

            attentionAlertService.create(alertCommand);
                        log.debug("Dispatched request campaign alert [campaign={}, channel={}, actionUrl={}]",
                            campaign.uuid(), channel, url);
        }

        if (sendNotifications) {
            String notificationMessage = (alertDescription == null || alertDescription.isBlank())
                    ? campaign.promptText()
                    : alertDescription.trim();
            channelService.notifyChannelsWithUrls(
                    channelUrls,
                    "Request Information",
                    notificationMessage);
        }
    }

    private static String trimTrailingSlash(String url) {
        String value = safe(url).trim();
        if (value.isBlank()) {
            return null;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record CreateCampaignCommand(
            String sessionUuid,
            String eventId,
            String createdBy,
            List<String> channelNames,
            String promptText,
            RequestResponsePolicy responsePolicy,
            Integer quorumCount,
            boolean sendNotifications,
            String alertName,
            String alertDescription,
                String alertResolutionPolicy,
            Long attentionAt
    ) {
    }

    public record LaunchResult(
            String campaignUuid,
            RequestResponsePolicy resolvedPolicy,
            int requiredResponses,
            int targetCount,
            Map<String, String> actionUrls,
            List<String> alertUuids
    ) {
    }

    public record ResponseGateResult(
            boolean accepted,
            boolean shouldResume,
            String campaignUuid,
            int responseCount,
            int requiredResponses,
            String userMessage
    ) {
    }

    private record PolicyResolution(RequestResponsePolicy policy, int requiredResponses) {
    }
}
