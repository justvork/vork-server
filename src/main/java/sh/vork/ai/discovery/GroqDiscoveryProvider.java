package sh.vork.ai.discovery;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import sh.vork.ai.AiProvider;
import sh.vork.ai.provider.AiProviderConfig;
import sh.vork.ai.provider.AiProviderConfigService;

/**
 * Discovers available Groq models via {@code GET https://api.groq.com/openai/v1/models}.
 *
 * <p>Groq's API is OpenAI-compatible; the same endpoint and response shape are used.
 * The API key is read from the persisted {@link AiProviderConfig} for {@link AiProvider#GROQ}.
 * Discovery returns an empty list when no key has been configured or the API call fails.
 */
@Component
public class GroqDiscoveryProvider implements ModelDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqDiscoveryProvider.class);
    private static final String BASE_URL = "https://api.groq.com/openai/v1";

    private final RestClient restClient;
    private final AiProviderConfigService configService;

    public GroqDiscoveryProvider(RestClient.Builder restClientBuilder,
                                 AiProviderConfigService configService) {
        this.restClient    = restClientBuilder.baseUrl(BASE_URL).build();
        this.configService = configService;
    }

    @Override
    public String getProviderName() {
        return "groq";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<DiscoveredModel> discoverModels() {
        AiProviderConfig cfg = configService.getConfig(AiProvider.GROQ);
        if (cfg == null || cfg.apiKey() == null || cfg.apiKey().isBlank()) {
            log.debug("Groq: no API key configured, skipping discovery");
            return List.of();
        }
        String apiKey = configService.decryptApiKey(cfg.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Groq: API key could not be decrypted, skipping discovery");
            return List.of();
        }
        log.debug("Groq: discovering models");
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/models")
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            if (response == null) return List.of();
            Object rawData = response.get("data");
            if (!(rawData instanceof List<?> list)) return List.of();
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(o -> (Map<String, Object>) o)
                    .map(m -> {
                        String id = (String) m.get("id");
                        return new DiscoveredModel(id, id, "groq", false);
                    })
                    .filter(m -> m.id() != null && !m.id().isBlank())
                    .sorted((a, b) -> a.id().compareToIgnoreCase(b.id()))
                    .toList();
        } catch (Exception e) {
            log.warn("Groq model discovery failed: {}", e.getMessage());
            return List.of();
        }
    }
}
