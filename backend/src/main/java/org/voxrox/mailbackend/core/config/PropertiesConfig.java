package org.voxrox.mailbackend.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.voxrox.mailbackend.core.backup.BackupProperties;
import org.voxrox.mailbackend.core.security.CryptoProperties;

@Configuration
@EnableConfigurationProperties({CryptoProperties.class, ClientConfigProperties.class, MailClientProperties.class,
        StorageProperties.class, BackupProperties.class})
public class PropertiesConfig {
}
