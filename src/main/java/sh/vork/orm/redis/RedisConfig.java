package sh.vork.orm.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Loads Redis connection settings from {@code conf.d/database.properties}
 * (relative to the working directory at startup) and exposes the necessary
 * Spring beans.
 *
 * <p>If the file is absent the defaults defined in the {@code @Value} annotations
 * are used, targeting a local unauthenticated Redis on the default port (6379).
 *
 * <p>Active only when {@code db.backend=redis} is set in
 * {@code conf.d/database.properties}.
 */
@Configuration
@ConditionalOnProperty(name = "db.backend", havingValue = "redis")
public class RedisConfig {

    @Value("${redis.host:localhost}")
    private String host;

    @Value("${redis.port:6379}")
    private int port;

    @Value("${redis.password:}")
    private String password;

    @Bean(destroyMethod = "close")
    public JedisPool jedisPool() {
        JedisPoolConfig config = new JedisPoolConfig();
        if (!password.isBlank()) {
            return new JedisPool(config, host, port, 2000, password);
        }
        return new JedisPool(config, host, port);
    }

    /**
     * Shared {@link ObjectMapper} configured with all available Jackson modules
     * (including native Java record support via the parameter-names module).
     *
     * <p>Only declared here when a MongoDB {@code MongoConfig} bean is not present;
     * if both configs are loaded the consuming application should declare its own
     * shared {@code ObjectMapper} bean.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
