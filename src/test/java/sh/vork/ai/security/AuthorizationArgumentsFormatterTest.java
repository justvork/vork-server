package sh.vork.ai.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationArgumentsFormatterTest {

    @Test
    void rendersNestedArgumentsAsDeterministicTable() {
        String json = """
                {
                  "customer":"acme",
                  "adjustment":{"percent":10,"reason":"promo"},
                  "targets":["north","west"]
                }
                """;

        String markdown = AuthorizationArgumentsFormatter.toApprovalMarkdown(json);

        assertTrue(markdown.contains("Exact arguments to be executed:"));
        assertTrue(markdown.contains("| customer | acme |"));
        assertTrue(markdown.contains("| adjustment.percent | 10 |"));
        assertTrue(markdown.contains("| adjustment.reason | promo |"));
        assertTrue(markdown.contains("| targets[0] | north |"));
        assertTrue(markdown.contains("| targets[1] | west |"));
    }

    @Test
    void fallsBackToJsonCodeBlockForInvalidJson() {
        String markdown = AuthorizationArgumentsFormatter.toApprovalMarkdown("not-json");
        assertTrue(markdown.contains("```json"));
        assertTrue(markdown.contains("not-json"));
    }
}
