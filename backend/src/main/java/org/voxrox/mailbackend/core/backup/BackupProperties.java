package org.voxrox.mailbackend.core.backup;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "mail.backup")
@Validated
public record BackupProperties(@Min(1) int retentionCount) {

    public BackupProperties {
        if (retentionCount < 1) {
            throw new IllegalArgumentException("mail.backup.retention-count must be >= 1");
        }
    }
}
