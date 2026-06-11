package sh.vork.orm.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseException;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Redis-backed implementation of {@link DatabaseRepository}.
 *
 * <h3>Storage strategy</h3>
 * Each entity is serialised to JSON and stored at a Redis key of the form
 * {@code {collection}:{uuid}}, where {@code collection} is derived from the
 * entity class simple name via {@code CamelCase → snake_case}.
 *
 * <h3>Search</h3>
 * All search and filter operations (including {@link #search} and
 * {@link #searchCount}) are performed by scanning all keys for the collection,
 * loading their JSON, and evaluating predicates in memory via
 * {@link SearchQuery#test(Map)}.  This is suitable for small-to-medium collections.
 * For high-volume workloads consider RediSearch or a different backend.
 *
 * <h3>Stream lifecycle</h3>
 * {@link #list} and {@link #search} return in-memory streams; closing them is
 * a no-op but callers should still use try-with-resources to mirror production
 * usage.
 *
 * @param <T> the entity type
 */
public class RedisRepository<T extends DatabaseEntity> implements DatabaseRepository<T> {

    private final Class<T> entityClass;
    private final JedisPool jedisPool;
    private final ObjectMapper mapper;
    private final String keyPrefix;

    public RedisRepository(Class<T> entityClass, JedisPool jedisPool, ObjectMapper mapper) {
        this.entityClass = entityClass;
        this.jedisPool = jedisPool;
        this.mapper = mapper;
        this.keyPrefix = collectionName(entityClass) + ":";
    }

    // -------------------------------------------------------------------------
    // DatabaseRepository implementation
    // -------------------------------------------------------------------------

    @Override
    public T get(String uuid) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = jedis.get(keyPrefix + uuid);
            return json == null ? null : deserialize(json);
        }
    }

    @Override
    public void save(T entity) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(keyPrefix + entity.uuid(), serialize(entity));
        }
    }

    @Override
    public void delete(String uuid) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(keyPrefix + uuid);
        }
    }

    /**
     * Returns a stream over the requested page of entities, sorted by UUID for
     * deterministic ordering (Redis SCAN does not guarantee insertion order).
     *
     * <p><strong>The caller should close this stream</strong> even though the
     * current implementation is backed by an in-memory list.
     */
    @Override
    public Stream<T> list(int page, int pageSize) {
        List<T> sorted = scanAllJson().stream()
                .map(this::deserialize)
                .sorted(Comparator.comparing(DatabaseEntity::uuid))
                .toList();
        return sorted.stream()
                .skip((long) page * pageSize)
                .limit(pageSize);
    }

    @Override
    public long count() {
        try (Jedis jedis = jedisPool.getResource()) {
            return scanKeys(jedis).size();
        }
    }

    @Override
    public T get(SearchQuery... queries) {
        return scanAllJson().stream()
                .map(this::toMap)
                .filter(doc -> matchesAll(doc, queries))
                .findFirst()
                .map(this::fromMap)
                .orElse(null);
    }

    @Override
    public Stream<T> search(int page, int pageSize, String sortField, SortOrder sortOrder,
                             SearchQuery... queries) {
        Comparator<Map<String, Object>> cmp = mapComparator(sortField, sortOrder);
        List<T> results = scanAllJson().stream()
                .map(this::toMap)
                .filter(doc -> matchesAll(doc, queries))
                .sorted(cmp)
                .skip((long) page * pageSize)
                .limit(pageSize)
                .map(this::fromMap)
                .toList();
        return results.stream();
    }

    @Override
    public long searchCount(SearchQuery... queries) {
        return scanAllJson().stream()
                .map(this::toMap)
                .filter(doc -> matchesAll(doc, queries))
                .count();
    }

    // -------------------------------------------------------------------------
    // Redis scan helpers
    // -------------------------------------------------------------------------

    private List<String> scanAllJson() {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> keys = scanKeys(jedis);
            if (keys.isEmpty()) return List.of();
            return jedis.mget(keys.toArray(String[]::new))
                    .stream()
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    private List<String> scanKeys(Jedis jedis) {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        ScanParams params = new ScanParams().match(keyPrefix + "*").count(100);
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
        return keys;
    }

    // -------------------------------------------------------------------------
    // Serialisation helpers
    // -------------------------------------------------------------------------

    private String serialize(T entity) {
        try {
            return mapper.writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            throw new DatabaseException(
                    "Failed to serialise " + entityClass.getSimpleName() + " with uuid=" + entity.uuid(), e);
        }
    }

    private T deserialize(String json) {
        try {
            return mapper.readValue(json, entityClass);
        } catch (JsonProcessingException e) {
            throw new DatabaseException("Failed to deserialise " + entityClass.getSimpleName(), e);
        }
    }

    private Map<String, Object> toMap(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new DatabaseException("Failed to deserialise entity to map", e);
        }
    }

    private T fromMap(Map<String, Object> doc) {
        try {
            return mapper.readValue(mapper.writeValueAsString(doc), entityClass);
        } catch (JsonProcessingException e) {
            throw new DatabaseException(
                    "Failed to re-serialise map to " + entityClass.getSimpleName(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Search helpers
    // -------------------------------------------------------------------------

    private static boolean matchesAll(Map<String, Object> doc, SearchQuery[] queries) {
        for (SearchQuery q : queries) {
            if (!q.test(doc)) return false;
        }
        return true;
    }

    private static Comparator<Map<String, Object>> mapComparator(String sortField,
                                                                   SortOrder sortOrder) {
        Comparator<Map<String, Object>> c = (a, b) -> {
            Object av = SearchQuery.normalize(SearchQuery.resolve(a, sortField));
            Object bv = SearchQuery.normalize(SearchQuery.resolve(b, sortField));
            if (av == null && bv == null) return 0;
            if (av == null) return 1;   // nulls last
            if (bv == null) return -1;
            return SearchQuery.compareValues(av, bv);
        };
        return sortOrder == SortOrder.DESC ? c.reversed() : c;
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /**
     * Converts a {@code CamelCase} class name to a {@code snake_case} collection name.
     * Examples: {@code Product → product}, {@code ProductEntity → product_entity}.
     */
    static String collectionName(Class<?> clazz) {
        return clazz.getSimpleName()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .toLowerCase();
    }
}
