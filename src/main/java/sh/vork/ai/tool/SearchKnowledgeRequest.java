package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the searchKnowledge AI tool.
 * Allows AI to search existing knowledge articles.
 */
public record SearchKnowledgeRequest(
    @JsonProperty(required = true, value = "base")
    @JsonPropertyDescription("Knowledge base/category to search within")
    String base,

    @JsonProperty(required = true, value = "query")
    @JsonPropertyDescription("Search term (case-insensitive substring match)")
    String query
) {}
