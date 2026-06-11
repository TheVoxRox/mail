package org.voxrox.mailbackend.core.init;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.core.service.FileSystemService;
import org.voxrox.mailbackend.util.LogCategory;

/**
 * Applies permissions to the directory structure after the Spring context
 * starts.
 *
 * The directories are created earlier in StorageContextInitializer (before
 * Hibernate/Hikari). This class only enforces the correct access permissions
 * and logs the state.
 */
@Component
public class StartupInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupInitializer.class);

    private final StorageProperties storageProperties;
    private final FileSystemService fileSystemService;
    private final StartupTimingService startupTimingService;

    public StartupInitializer(StorageProperties storageProperties, FileSystemService fileSystemService,
            StartupTimingService startupTimingService) {
        this.storageProperties = storageProperties;
        this.fileSystemService = fileSystemService;
        this.startupTimingService = startupTimingService;
    }

    @Override
    public void run(ApplicationArguments args) {
        long started = startupTimingService.start();
        log.info("{} Applying permissions to the directory structure...", LogCategory.BOOT);

        List<Path> dirs = List.of(storageProperties.getDataPath(), storageProperties.getDbPath(),
                storageProperties.getLogsPath(), storageProperties.getAttachmentsPath(),
                storageProperties.getTmpPath());

        for (Path path : dirs) {
            fileSystemService.applyPrivatePermissions(path);
        }

        log.info("{} Backend startup sequence completed.", LogCategory.BOOT);
        startupTimingService.record("storage.permissions", started);
    }
}
