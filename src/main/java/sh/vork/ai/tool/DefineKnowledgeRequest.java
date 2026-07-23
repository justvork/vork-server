package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the defineKnowledge AI tool.
 * Allows AI to persistently store new knowledge articles.
 */
public record DefineKnowledgeRequest(
    @JsonProperty(required = true, value = "base")
    @JsonPropertyDescription("Knowledge base/category name (e.g., 'Deployment', 'Troubleshooting')")
    String base,

    @JsonProperty(required = true, value = "content")
    @JsonPropertyDescription("Knowledge article content (free text, searchable)")
    String content
) {}
