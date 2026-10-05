package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;

import jakarta.activation.DataHandler;
import jakarta.mail.Folder;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimePartDataSource;
import jakarta.mail.util.ByteArrayDataSource;

import org.eclipse.angus.mail.imap.IMAPMessage;
import org.eclipse.angus.mail.imap.IMAPMultipartDataSource;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;

/**
 * Unit tests for {@link AttachmentService}.
 *
 * Strategy: - A real temp directory under {@code target/test-tmp}, made in
 * {@code setUp} and deleted in {@code tearDown} — the service constructor calls
 * {@code Files.list(privateTempDir)} during cleanup, so we need a real existing
 * directory. - {@link ImapFolderExecutor} is mocked — it returns a prepared
 * temp file so we do not actually download from IMAP (the lambda inside
 * {@code executeReadOnly} is not invoked in the test).
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

            when(folderExecutor.executeReadOnly(eq(ACCOUNT_ID), eq(Lane.INTERACTIVE), eq(FOLDER_NAME), any()))
                    .thenReturn(fakeDownload);

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

            verify(folderExecutor, never()).executeReadOnly(anyLong(), any(), anyString(), any());
        }
    }

    /**
     * The download lambda itself, run against real MIME messages: the executor mock
     * calls it with a folder whose message is built here, written out and parsed
     * back, so its structure is what a server's bytes produce.
     */
    @Nested
    @DisplayName("Finding the part")
    class FindingThePart {

        private final Session session = Session.getInstance(new Properties());

        private MimeMessage parsed(MimeMessage message) throws Exception {
            message.saveChanges();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            return new MimeMessage(session, new ByteArrayInputStream(bytes.toByteArray()));
        }

        private MimeBodyPart pdf(byte[] data) throws Exception {
            MimeBodyPart part = new MimeBodyPart();
            part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, "application/pdf")));
            part.setFileName("scan.pdf");
            part.setDisposition(Part.ATTACHMENT);
            return part;
        }

        private MimeMessage multipart(byte[] attachment) throws Exception {
            MimeBodyPart text = new MimeBodyPart();
            text.setText("See the attachment.", StandardCharsets.UTF_8.name());
            MimeMultipart multipart = new MimeMultipart();
            multipart.addBodyPart(text);
            multipart.addBodyPart(pdf(attachment));
            MimeMessage message = new MimeMessage(session);
            message.setContent(multipart);
            return parsed(message);
        }

        private void serverHas(MimeMessage message) throws Exception {
            when(messageRepository.findByStableId(STABLE_ID)).thenReturn(Optional.of(createEntity()));
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getMessageByUID(UID)).thenReturn(message);
            when(folderExecutor.executeReadOnly(eq(ACCOUNT_ID), eq(Lane.INTERACTIVE), eq(FOLDER_NAME), any()))
                    .thenAnswer(invocation -> {
                        ImapFolderAction<?> action = invocation.getArgument(3);
                        return action.apply(mock(Folder.class), uidFolder);
                    });
        }

        private byte[] download(String partPath) throws Exception {
            try (InputStream in = createService().getAttachmentStreamByStableId(STABLE_ID, partPath)) {
                return in.readAllBytes();
            }
        }

        @Test
        @DisplayName("A part of a multipart message is found by its number")
        void findsAPartOfAMultipart() throws Exception {
            byte[] pdf = "%PDF-1.7 multipart".getBytes(StandardCharsets.US_ASCII);
            serverHas(multipart(pdf));

            assertThat(download("2")).isEqualTo(pdf);
        }

        @Test
        @DisplayName("A message whose whole body is the attachment gives it as part 1")
        void findsTheBodyOfASinglePartMessage() throws Exception {
            // MimePartExtractor gives such an attachment the path "1"; RFC 3501
            // numbers a single-part body 1 as well.
            byte[] pdf = "%PDF-1.7 single".getBytes(StandardCharsets.US_ASCII);
            MimeMessage message = new MimeMessage(session);
            message.setDataHandler(new DataHandler(new ByteArrayDataSource(pdf, "application/pdf")));
            message.setFileName("scan.pdf");
            message.setDisposition(Part.ATTACHMENT);
            serverHas(parsed(message));

            assertThat(download("1")).isEqualTo(pdf);
        }

        @Test
        @DisplayName("A text body is found without being read whole into memory")
        void findsATextBodyWithoutReadingIt() throws Exception {
            // B1-12: the content of a text part is its body decoded into one String,
            // as large as the server makes it; the download streams the part anyway.
            MimeMessage message = new MimeMessage(session);
            message.setText("Meeting notes", StandardCharsets.UTF_8.name());
            message.setFileName("notes.txt");
            message.setDisposition(Part.ATTACHMENT);
            MimeMessage onServer = spy(parsed(message));
            serverHas(onServer);

            assertThat(download("1")).isEqualTo("Meeting notes".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> download("1.1")).isInstanceOf(ResourceNotFoundException.class);
            verify(onServer, never()).getContent();
        }

        /**
         * B1-12, reopened at 1.24. A server can name {@code multipart/mixed} in a
         * structure of single-part form; Angus 2.0.5 then builds no parts from it and
         * leaves the message to {@code MimeMessage}'s own handler, whose content is the
         * whole body fetched and parsed in memory. The handler here is that one.
         */
        @Test
        @DisplayName("A multipart the server describes in single-part form is not read whole")
        void aMultipartWithoutAStructureIsALeaf() throws Exception {
            byte[] body = "--b\r\n\r\nraw\r\n--b--\r\n".getBytes(StandardCharsets.US_ASCII);
            IMAPMessage onServer = mock(IMAPMessage.class);
            when(onServer.isMimeType("multipart/*")).thenReturn(true);
            when(onServer.getDataHandler()).thenReturn(new DataHandler(new MimePartDataSource(onServer)));
            when(onServer.getInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(body));
            serverHas(onServer);

            assertThat(download("1")).isEqualTo(body);
            assertThatThrownBy(() -> download("1.1")).isInstanceOf(ResourceNotFoundException.class);
            verify(onServer, never()).getContent();
        }

        /**
         * The other side of B1-12: a multipart Angus built from the structure it parsed
         * is descended into. Its handler is Angus's own
         * {@code IMAPMultipartDataSource}, which is itself a
         * {@code MimePartDataSource}; the 1.34 lookup tested for that superclass alone
         * and made every multipart from the server a leaf, so no attachment in one
         * could be downloaded. A test that modelled the handler as an object holding
         * the parts passed against it.
         */
        @Test
        @DisplayName("A multipart built from the server's structure is descended into")
        void aMultipartFromTheStructureIsDescended() throws Exception {
            byte[] pdf = "%PDF-1.7 structure".getBytes(StandardCharsets.US_ASCII);
            Object parts = multipart(pdf).getContent();
            IMAPMessage onServer = mock(IMAPMessage.class);
            when(onServer.isMimeType("multipart/*")).thenReturn(true);
            when(onServer.getDataHandler()).thenReturn(
                    new DataHandler(new IMAPMultipartDataSource(onServer, new BODYSTRUCTURE[0], null, onServer) {
                    }));
            when(onServer.getContent()).thenReturn(parts);
            serverHas(onServer);

            assertThat(download("2")).isEqualTo(pdf);
        }

        /**
         * A nested message with an envelope: Angus 2.0.5 hands an
         * {@code IMAPNestedMessage} to a {@code DataHandler} as an object, so its
         * content is that message and reading it fetches no body.
         */
        @Test
        @DisplayName("A nested message built from the server's structure is descended into")
        void aNestedMessageFromTheStructureIsDescended() throws Exception {
            byte[] pdf = "%PDF-1.7 nested".getBytes(StandardCharsets.US_ASCII);
            MimeMessage inner = multipart(pdf);
            IMAPMessage onServer = mock(IMAPMessage.class);
            when(onServer.isMimeType("multipart/*")).thenReturn(false);
            when(onServer.isMimeType("message/rfc822")).thenReturn(true);
            when(onServer.getDataHandler()).thenReturn(new DataHandler(inner, "message/rfc822"));
            when(onServer.getContent()).thenReturn(inner);
            serverHas(onServer);

            assertThat(download("2")).isEqualTo(pdf);
        }

        @Test
        @DisplayName("A number past the last part is a not-found naming the attachment, not the folder")
        void reportsAMissingPartAsTheAttachment() throws Exception {
            serverHas(multipart(new byte[]{1, 2, 3}));

            assertThatThrownBy(() -> download("3")).isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("MIME part 3").hasMessageNotContaining("Folder")
                    .extracting(e -> ((ResourceNotFoundException) e).getMessageKey())
                    .isEqualTo("error.attachment.partNotFound");
            assertThatThrownBy(() -> download("1.1")).isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("A malformed path is a validation error before any IMAP work")
        void rejectsAMalformedPathBeforeImap() {
            AttachmentService service = createService();

            for (String path : new String[]{"x", "0", "1.0", "01", "1..2", "-1"}) {
                assertThatThrownBy(() -> service.getAttachmentStreamByStableId(STABLE_ID, path)).as(path)
                        .isInstanceOf(ValidationException.class);
            }
            verify(folderExecutor, never()).executeReadOnly(anyLong(), any(), anyString(), any());
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
