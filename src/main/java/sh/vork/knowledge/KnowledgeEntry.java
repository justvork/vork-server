package sh.vork.knowledge;

import sh.vork.orm.DatabaseEntity;

/**
 * Persistent knowledge entry stored in Nitrite database.
 * Represents a single knowledge article with category (base), content, and timestamps.
 */
public record KnowledgeEntry(
    String uuid,         // PK — generated UUID
    String base,         // Category/namespace (e.g., "Deployment", "Troubleshooting")
    String content,      // Free-text searchable content
    long createdAt,      // Epoch milliseconds at creation
    long updatedAt       // Epoch milliseconds of last update
) implements DatabaseEntity {}
