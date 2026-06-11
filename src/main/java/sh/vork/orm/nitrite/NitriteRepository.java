package sh.vork.orm.nitrite;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dizitart.no2.Nitrite;
import org.dizitart.no2.collection.Document;
import org.dizitart.no2.collection.DocumentCursor;
import org.dizitart.no2.collection.NitriteCollection;
import org.dizitart.no2.filters.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseException;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.dizitart.no2.filters.FluentFilter.where;

/**
 * Nitrite-backed implementation of {@link DatabaseRepository}.
 *
 * <p>Each entity is serialised to JSON and stored in a single string field
 * ({@code _data}) inside a Nitrite {@link Document}, alongside an indexed
 * {@code uuid} field for fast single-entity lookups.  No external server is
 * required — data is persisted to a single file via MVStore.
 *
 * <p>Search and sort operations perform a full collection scan and apply
 * {@link SearchQuery#test} predicates in-memory.  This implementation is
 * suitable for small-to-medium datasets and embedded / single-user deployments.
 */
public class NitriteRepository<T extends DatabaseEntity> implements DatabaseRepository<T> {

    private static final Logger log = LoggerFactory.getLogger(NitriteRepository.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String DATA_FIELD = "_data";
    private static final String UUID_FIELD  = "uuid";

    private final Class<T>         entityClass;
    private final NitriteCollection collection;
    private final ObjectMapper      objectMapper;

    public NitriteRepository(Class<T> entityClass, Nitrite nitrite, ObjectMapper objectMapper) {
        this.entityClass  = entityClass;
        this.objectMapper = objectMapper;
        String name = collectionName(entityClass);
        this.collection = nitrite.getCollection(name);
        if (!this.collection.hasIndex(UUID_FIELD)) {
            this.collection.createIndex(UUID_FIELD);
        }
        log.debug("NitriteRepository ready: collection={}", name);
    }

    // ─── Core CRUD ────────────────────────────────────────────────────────────

    @Override
    public T get(String uuid) {
        log.debug("ENTER get: uuid={}", uuid);
        Document doc = collection.find(where(UUID_FIELD).eq(uuid)).firstOrNull();
        T result = fromDocument(doc);
        log.debug("EXIT get: found={}", result != null);
        return result;
    }

    @Override
    public T get(SearchQuery... queries) {
        log.debug("ENTER get by queries: count={}", queries.length);
        for (Document doc : collection.find()) {
            Map<String, Object> map = toMap(doc);
            if (map != null && matchesAll(map, queries)) {
                log.debug("EXIT get by queries: found");
                return fromMap(map);
            }
        }
        log.debug("EXIT get by queries: not found");
        return null;
    }

    @Override
    public void save(T entity) {
        log.debug("ENTER save: uuid={}", entity.uuid());
        try {
            String json = objectMapper.writeValueAsString(entity);
            Document doc = Document.createDocument(UUID_FIELD, entity.uuid())
                    .put(DATA_FIELD, json);
            // Upsert: remove any existing document, then insert fresh
            collection.remove(where(UUID_FIELD).eq(entity.uuid()));
            collection.insert(doc);
            log.debug("EXIT save: ok");
        } catch (Exception e) {
            throw new DatabaseException("Failed to save entity: " + entity.uuid(), e);
        }
    }

    @Override
    public void delete(String uuid) {
        log.debug("ENTER delete: uuid={}", uuid);
        collection.remove(where(UUID_FIELD).eq(uuid));
        log.debug("EXIT delete: ok");
    }

    @Override
    public long count() {
        long n = collection.size();
        log.debug("count: {}", n);
        return n;
    }

    // ─── Paged listing ────────────────────────────────────────────────────────

    @Override
    public Stream<T> list(int page, int pageSize) {
        log.debug("ENTER list: page={}, pageSize={}", page, pageSize);
        List<Document> all = new ArrayList<>(collection.find().toList());
        // Sort by uuid for stable ordering across pages
        all.sort(Comparator.comparing(d -> String.valueOf(d.get(UUID_FIELD))));
        int start = page * pageSize;
        List<T> results = new ArrayList<>();
        for (int i = start, end = Math.min(start + pageSize, all.size()); i < end; i++) {
            T entity = fromDocument(all.get(i));
            if (entity != null) results.add(entity);
        }
        log.debug("EXIT list: returned={}", results.size());
        return results.stream();
    }

    // ─── Search ───────────────────────────────────────────────────────────────

    @Override
    public Stream<T> search(int page, int pageSize,
                            String sortField, SortOrder sortOrder,
                            SearchQuery... queries) {
        log.debug("ENTER search: page={}, pageSize={}, sortField={}, sortOrder={}, queries={}",
                page, pageSize, sortField, sortOrder, queries.length);

        List<Map<String, Object>> matched = new ArrayList<>();
        for (Document doc : collection.find()) {
            Map<String, Object> map = toMap(doc);
            if (map != null && matchesAll(map, queries)) {
                matched.add(map);
            }
        }
        matched.sort(mapComparator(sortField, sortOrder));

        List<T> results = matched.stream()
                .skip((long) page * pageSize)
                .limit(pageSize)
                .map(this::fromMap)
                .filter(Objects::nonNull)
                .toList();

        log.debug("EXIT search: returned={}", results.size());
        return results.stream();
    }

    @Override
    public long searchCount(SearchQuery... queries) {
        log.debug("ENTER searchCount: queries={}", queries.length);
        if (queries.length == 0) return collection.size();
        long count = 0;
        for (Document doc : collection.find()) {
            Map<String, Object> map = toMap(doc);
            if (map != null && matchesAll(map, queries)) count++;
        }
        log.debug("EXIT searchCount: {}", count);
        return count;
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private T fromDocument(Document doc) {
        if (doc == null) return null;
        Object data = doc.get(DATA_FIELD);
        if (data == null) return null;
        try {
            return objectMapper.readValue(data.toString(), entityClass);
        } catch (Exception e) {
            log.warn("Failed to deserialise Nitrite document: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> toMap(Document doc) {
        if (doc == null) return null;
        Object data = doc.get(DATA_FIELD);
        if (data == null) return null;
        try {
            return objectMapper.readValue(data.toString(), MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse Nitrite document to map: {}", e.getMessage());
            return null;
        }
    }

    private T fromMap(Map<String, Object> map) {
        try {
            return objectMapper.convertValue(map, entityClass);
        } catch (Exception e) {
            log.warn("Failed to convert map to {}: {}", entityClass.getSimpleName(), e.getMessage());
            return null;
        }
    }

    private static boolean matchesAll(Map<String, Object> map, SearchQuery[] queries) {
        for (SearchQuery q : queries) {
            if (!q.test(map)) return false;
        }
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<Map<String, Object>> mapComparator(String field, SortOrder order) {
        Comparator<Map<String, Object>> cmp = (a, b) -> {
            Object va = getNestedValue(a, field);
            Object vb = getNestedValue(b, field);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;
            if (vb == null) return -1;
            if (va instanceof Comparable ca && vb instanceof Comparable cb) {
                try { return ca.compareTo(vb); } catch (ClassCastException ignored) { /* fall through */ }
            }
            return va.toString().compareTo(vb.toString());
        };
        return order == SortOrder.DESC ? cmp.reversed() : cmp;
    }

    @SuppressWarnings("unchecked")
    private static Object getNestedValue(Map<String, Object> map, String dotPath) {
        String[] parts = dotPath.split("\\.", 2);
        Object value = map.get(parts[0]);
        if (parts.length == 1 || !(value instanceof Map)) return value;
        return getNestedValue((Map<String, Object>) value, parts[1]);
    }

    static String collectionName(Class<?> cls) {
        return cls.getSimpleName()
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_")
                .toLowerCase();
    }
}
