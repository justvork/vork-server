package sh.vork.ai.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the getKnowledge AI tool.
 * Allows AI to retrieve all knowledge articles in a category.
 */
public record GetKnowledgeRequest(
    @JsonProperty(required = true, value = "base")
    @JsonPropertyDescription("Knowledge base/category to retrieve")
    String base
) {}
