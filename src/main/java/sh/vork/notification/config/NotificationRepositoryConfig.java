package sh.vork.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;

import sh.vork.notification.GlobalAddress;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.user.UserNotificationMedia;

@Configuration
public class NotificationRepositoryConfig {

    @Bean
    public DatabaseRepository<NotificationProviderConfig> notificationProviderConfigRepository(
            RepositoryFactory factory) {
        return factory.create(NotificationProviderConfig.class);
    }

    @Bean
    public DatabaseRepository<UserNotificationMedia> userNotificationMediaRepository(
            RepositoryFactory factory) {
        return factory.create(UserNotificationMedia.class);
    }

    @Bean
    public DatabaseRepository<GlobalAddress> globalAddressRepository(RepositoryFactory factory) {
        return factory.create(GlobalAddress.class);
    }
}
