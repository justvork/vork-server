package sh.vork.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Service layer for knowledge management.
 * Provides methods to define, search, retrieve, update, and delete knowledge entries.
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    @Autowired
    private DatabaseRepository<KnowledgeEntry> knowledgeRepository;

    /**
     * Define a new knowledge entry.
     *
     * @param base Category/namespace for the entry
     * @param content Free-text searchable content
     * @return The created KnowledgeEntry
     */
    public KnowledgeEntry define(String base, String content) {
        log.debug("ENTER define: base={}, content length={}", base, content.length());
        String uuid = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        KnowledgeEntry entry = new KnowledgeEntry(uuid, base, content, now, now);
        knowledgeRepository.save(entry);
        log.debug("EXIT define: uuid={}", uuid);
        return entry;
    }

    /**
     * Search knowledge entries by base and content query.
     *
     * @param base Category to search within
     * @param query Search term (case-insensitive substring match)
     * @param page Zero-based page number
     * @param pageSize Results per page
     * @return Stream of matching entries, sorted by createdAt DESC
     */
    public Stream<KnowledgeEntry> search(String base, String query, int page, int pageSize) {
        log.debug("ENTER search: base={}, query={}, page={}, pageSize={}", base, query, page, pageSize);
        return knowledgeRepository.search(
            page, pageSize, "createdAt", SortOrder.DESC,
            SearchQuery.eq("base", base),
            SearchQuery.like("content", query)
        );
    }

    /**
     * Get all knowledge entries for a given base (unfiltered).
     *
     * @param base Category to retrieve
     * @param page Zero-based page number
     * @param pageSize Results per page
     * @return Stream of all entries in base, sorted by createdAt DESC
     */
    public Stream<KnowledgeEntry> getAll(String base, int page, int pageSize) {
        log.debug("ENTER getAll: base={}, page={}, pageSize={}", base, page, pageSize);
        return knowledgeRepository.search(
            page, pageSize, "createdAt", SortOrder.DESC,
            SearchQuery.eq("base", base)
        );
    }

    /**
     * Get a single knowledge entry by UUID.
     *
     * @param uuid Entry identifier
     * @return The entry, or null if not found
     */
    public KnowledgeEntry get(String uuid) {
        log.trace("ENTER get: uuid={}", uuid);
        KnowledgeEntry result = knowledgeRepository.get(uuid);
        log.trace("EXIT get: found={}", result != null);
        return result;
    }

    /**
     * Update an existing knowledge entry.
     *
     * @param uuid Entry identifier
     * @param base New category
     * @param content New content
     * @return The updated entry
     */
    public KnowledgeEntry update(String uuid, String base, String content) {
        log.debug("ENTER update: uuid={}, base={}", uuid, base);
        KnowledgeEntry existing = knowledgeRepository.get(uuid);
        if (existing == null) {
            log.warn("EXIT update: entry not found");
            return null;
        }
        long now = System.currentTimeMillis();
        KnowledgeEntry updated = new KnowledgeEntry(uuid, base, content, existing.createdAt(), now);
        knowledgeRepository.save(updated);
        log.debug("EXIT update: updatedAt={}", now);
        return updated;
    }

    /**
     * Delete a knowledge entry.
     *
     * @param uuid Entry identifier
     */
    public void delete(String uuid) {
        log.debug("ENTER delete: uuid={}", uuid);
        knowledgeRepository.delete(uuid);
        log.debug("EXIT delete");
    }

    /**
     * Get all distinct base categories.
     *
     * @return Set of base names
     */
    public Set<String> getAllBases() {
        log.trace("ENTER getAllBases");
        Set<String> bases = new HashSet<>();
        try (Stream<KnowledgeEntry> all = knowledgeRepository.list(0, Integer.MAX_VALUE)) {
            all.forEach(entry -> bases.add(entry.base()));
        }
        log.trace("EXIT getAllBases: count={}", bases.size());
        return bases;
    }

    /**
     * Get count of knowledge entries for a given base.
     *
     * @param base Category
     * @return Number of entries
     */
    public long countByBase(String base) {
        log.trace("ENTER countByBase: base={}", base);
        long count = knowledgeRepository.searchCount(SearchQuery.eq("base", base));
        log.trace("EXIT countByBase: count={}", count);
        return count;
    }

    /**
     * Get total count of all knowledge entries.
     *
     * @return Total count
     */
    public long countAll() {
        log.trace("ENTER countAll");
        long count = knowledgeRepository.count();
        log.trace("EXIT countAll: count={}", count);
        return count;
    }
}
