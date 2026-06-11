package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link AttachmentService}.
 *
 * Strategy: - A real temp directory via {@link TempDir} — the service
 * constructor calls {@code Files.list(privateTempDir)} during cleanup, so we
 * need a real existing directory. - {@link ImapFolderExecutor} is mocked — it
 * returns a prepared temp file so we do not actually download from IMAP (the
 * lambda inside {@code executeReadOnly} is not invoked in the test).
 *
 * Covers: - Happy path: returns a stream over the content, which is deleted on
 * close(). - Message not found in DB -> ResourceNotFoundException. - Cleanup of
 * stale {@code attach_*.tmp} files at startup.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    private static final String STABLE_ID = "abc123";
    private static final String PART_PATH = "1.2";
    private static final Long ACCOUNT_ID = 7L;
    private static final String FOLDER_NAME = "INBOX";
    private static final long UID = 999L;

    Path dataDir;

    private Path tmpDir;

    private ImapFolderExecutor folderExecutor;
    private MessageRepository messageRepository;

    @BeforeEach
    void setUp() throws IOException {
        Path testRoot = Path.of("target", "test-tmp", "AttachmentServiceTest");
        Files.createDirectories(testRoot);
        dataDir = Files.createTempDirectory(testRoot, "case-");

        // StorageProperties.getTmpPath() = dataDir/tmp — directory must exist
        // (in production it is created by StorageContextInitializer).
        tmpDir = dataDir.resolve("tmp");
        Files.createDirectories(tmpDir);

        folderExecutor = org.mockito.Mockito.mock(ImapFolderExecutor.class);
        messageRepository = org.mockito.Mockito.mock(MessageRepository.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dataDir == null || !Files.exists(dataDir)) {
            return;
        }
        try (var paths = Files.walk(dataDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private AttachmentService createService() {
        StorageProperties props = new StorageProperties(dataDir.toString());
        AttachmentService service = new AttachmentService(folderExecutor, messageRepository, props);
        // Simulate Spring's @PostConstruct call — production code runs this once
        // after the bean is fully constructed (see
        // AttachmentService.initStaleTempCleanup).
        service.initStaleTempCleanup();
        return service;
    }

    private MessageEntity createEntity() {
        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);

        MessageEntity entity = new MessageEntity();
        entity.setStableId(STABLE_ID);
        entity.setAccount(account);
        entity.setFolderName(FOLDER_NAME);
        entity.setUid(UID);
        return entity;
    }

    @Nested
    @DisplayName("getAttachmentStreamByStableId")
    class GetAttachmentStreamByStableId {

        @Test
        @DisplayName("Happy path: returns a stream over the content that is deleted from disk on close")
        void shouldReturnStreamAndDeleteFileOnClose() throws Exception {
            // Arrange: message exists in DB
            MessageEntity entity = createEntity();
            when(messageRepository.findByStableId(STABLE_ID)).thenReturn(Optional.of(entity));

            AttachmentService service = createService();

            // Prepare a real temp file that the service would otherwise download
            // from IMAP. The lambda inside executeReadOnly is not invoked in this
            // unit test — we mock the whole call to return the path to the file
            // we already have on disk.
            Path fakeDownload = Files.createTempFile(tmpDir, "attach_" + STABLE_ID + "_", ".tmp");
            byte[] payload = "hello world".getBytes(StandardCharsets.UTF_8);
            Files.write(fakeDownload, payload);

            when(folderExecutor.executeReadOnly(eq(ACCOUNT_ID), eq(FOLDER_NAME), any())).thenReturn(fakeDownload);

            // Act
            byte[] read;
            try (InputStream is = service.getAttachmentStreamByStableId(STABLE_ID, PART_PATH)) {
                read = is.readAllBytes();
            }

            // Assert: the content flows through the stream
            assertThat(read).isEqualTo(payload);
            // After close() the file must be deleted (DeleteOnCloseFileInputStream)
            assertThat(Files.exists(fakeDownload)).isFalse();
        }

        @Test
        @DisplayName("Non-existing stableId -> ResourceNotFoundException, IMAP is not called")
        void shouldThrowWhenStableIdNotFound() {
            // Arrange
            when(messageRepository.findByStableId(STABLE_ID)).thenReturn(Optional.empty());

            AttachmentService service = createService();

            // Act & Assert
            assertThatThrownBy(() -> service.getAttachmentStreamByStableId(STABLE_ID, PART_PATH))
                    .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining(STABLE_ID);

            verify(folderExecutor, never()).executeReadOnly(anyLong(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("Cleanup of stale temp files at startup")
    class StartupCleanup {

        @Test
        @DisplayName("Deletes stale attach_* files (>1h) and keeps fresh ones and unrelated files")
        void shouldDeleteOnlyOldAttachFiles() throws Exception {
            // Arrange: prepare 3 files in the tmp directory
            Path oldAttach = Files.createFile(tmpDir.resolve("attach_old_xyz.tmp"));
            Path freshAttach = Files.createFile(tmpDir.resolve("attach_fresh_abc.tmp"));
            Path unrelated = Files.createFile(tmpDir.resolve("other_file.tmp"));

            // Set "old" 2h in the past -> must be picked up by the cleanup
            long twoHoursAgo = System.currentTimeMillis() - 2 * 3600_000L;
            Files.setLastModifiedTime(oldAttach, java.nio.file.attribute.FileTime.fromMillis(twoHoursAgo));

            // Act: the constructor runs cleanupStaleTempFiles()
            createService();

            // Assert
            assertThat(Files.exists(oldAttach)).isFalse();
            assertThat(Files.exists(freshAttach)).isTrue();
            // files without the "attach_" prefix are ignored by cleanup
            assertThat(Files.exists(unrelated)).isTrue();
        }
    }
}
