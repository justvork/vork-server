package sh.vork.transcription;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.RepositoryFactory;

/**
 * Registers the {@link DatabaseRepository} bean for {@link TranscriptionConfig}.
 */
@Configuration
public class TranscriptionRepositoryConfig {

    @Bean
    public DatabaseRepository<TranscriptionConfig> transcriptionConfigRepository(
            RepositoryFactory factory) {
        return factory.create(TranscriptionConfig.class);
    }
}
