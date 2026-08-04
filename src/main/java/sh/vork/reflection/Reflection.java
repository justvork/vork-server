package sh.vork.reflection;

import java.util.List;
import java.util.Map;

import sh.vork.orm.DatabaseEntity;

/**
 * Reflection definition that is exposed as an AI tool by {@code id}.
 */
public record Reflection(
        String uuid,
        String id,
        String name,
        String description,
        String groupUuid,
        List<ReflectionInputParameter> inputParameters,
        String method,
        String url,
        Map<String, String> headers,
        Map<String, String> queryParameters,
        String bodyTemplate,
        String requestContentType,
        String responseContentType,
        String outputSchema,
        long version,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public Reflection {
        if (id == null) {
            id = "";
        }
        if (name == null || name.isBlank()) {
            name = "Unnamed Reflection";
        }
        if (description == null) {
            description = "";
        }
        if (groupUuid == null) {
            groupUuid = "";
        }
        if (inputParameters == null) {
            inputParameters = List.of();
        }
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        if (url == null) {
            url = "";
        }
        if (headers == null) {
            headers = Map.of();
        }
        if (queryParameters == null) {
            queryParameters = Map.of();
        }
        if (bodyTemplate == null) {
            bodyTemplate = "";
        }
        if (requestContentType == null || requestContentType.isBlank()) {
            requestContentType = "application/json";
        }
        if (responseContentType == null || responseContentType.isBlank()) {
            responseContentType = "application/json";
        }
        if (outputSchema == null) {
            outputSchema = "";
        }
        if (version < 1) {
            version = 1;
        }
    }
}
