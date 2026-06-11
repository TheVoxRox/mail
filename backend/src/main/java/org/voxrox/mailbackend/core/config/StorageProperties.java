package org.voxrox.mailbackend.core.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app")
@Validated
public record StorageProperties(@NotBlank String dataDir) {
    public Path getDataPath() {
        return Paths.get(dataDir).toAbsolutePath().normalize();
    }

    public Path getDbPath() {
        return getDataPath().resolve("db");
    }

    public Path getLogsPath() {
        return getDataPath().resolve("logs");
    }

    public Path getAttachmentsPath() {
        return getDataPath().resolve("attachments");
    }

    public Path getTmpPath() {
        return getDataPath().resolve("tmp");
    }
}
