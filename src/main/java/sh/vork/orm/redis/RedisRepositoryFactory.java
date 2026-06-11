package sh.vork.orm.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Spring-managed factory for creating Redis-backed {@link DatabaseRepository} instances.
 *
 * <p>Declare a {@code @Bean} for each entity type in a {@code @Configuration} class:
 *
 * <pre>{@code
 * @Configuration
 * public class RepositoryConfig {
 *
 *     @Bean
 *     public DatabaseRepository<Product> productRepository(RepositoryFactory factory) {
 *         return factory.create(Product.class);
 *     }
 * }
 * }</pre>
 *
 * Spring will then satisfy {@code @Autowired DatabaseRepository<Product>} injections
 * automatically by matching the generic type parameter.
 */
@Component
@ConditionalOnProperty(name = "db.backend", havingValue = "redis")
public class RedisRepositoryFactory implements RepositoryFactory {

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;

    public RedisRepositoryFactory(JedisPool jedisPool, ObjectMapper objectMapper) {
        this.jedisPool = jedisPool;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a new {@link RedisRepository} for the given entity class.
     * The Redis key prefix is derived from the class simple name
     * via {@code CamelCase → snake_case} conversion.
     */
    @Override
    public <T extends DatabaseEntity> DatabaseRepository<T> create(Class<T> entityClass) {
        return new RedisRepository<>(entityClass, jedisPool, objectMapper);
    }
}
