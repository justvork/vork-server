package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record SearchTextFileRequest(
        @JsonProperty(value = "path", required = true)
        @JsonPropertyDescription("Relative path to the text/log file to search.")
        String path,

        @JsonProperty(value = "query", required = true)
        @JsonPropertyDescription("Search query string. Required for CONTAINS, EXACT, and REGEX modes.")
        String query,

        @JsonProperty(value = "matchType", required = false)
        @JsonPropertyDescription("Matching mode: CONTAINS (default), EXACT, or REGEX.")
        String matchType,

        @JsonProperty(value = "caseSensitive", required = false)
        @JsonPropertyDescription("Whether matching is case-sensitive. Default false.")
        Boolean caseSensitive,

        @JsonProperty(value = "beforeLines", required = false)
        @JsonPropertyDescription("Number of lines to include before each match. Default 0.")
        Integer beforeLines,

        @JsonProperty(value = "afterLines", required = false)
        @JsonPropertyDescription("Number of lines to include after each match. Default 0.")
        Integer afterLines,

        @JsonProperty(value = "maxMatches", required = false)
        @JsonPropertyDescription("Maximum number of matches to return as contextual blocks. Conservative default is applied when omitted.")
        Integer maxMatches,

        @JsonProperty(value = "startLine", required = false)
        @JsonPropertyDescription("Optional inclusive 1-based lower line bound for searching.")
        Long startLine,

        @JsonProperty(value = "endLine", required = false)
        @JsonPropertyDescription("Optional inclusive 1-based upper line bound for searching.")
        Long endLine,

        @JsonProperty(value = "area", required = false)
        @JsonPropertyDescription("Storage area: SESSION (default) or SHARED.")
        String area
) {}
