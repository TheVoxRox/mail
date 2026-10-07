package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Properties;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;

/**
 * Unit tests for {@link ImapAppendService}.
 *
 * <p>
 * Mocks of {@link ImapConnectionManager#executeWithLock} and
 * {@link ImapFolderService#executeInFolder} act as callback callers — in tests
 * we manually invoke the callback via {@link org.mockito.stubbing.Answer} so we
 * can verify what happens inside (markSeen, appendMessages, fetch UID).
 */
@ExtendWith(MockitoExtension.class)
class ImapAppendServiceTest {

    private static final Long ACCOUNT_ID = 11L;
    private static final String FOLDER_NAME = "[Gmail]/Sent";

    @Mock
    private ImapConnectionManager imapConnectionManager;
    @Mock
    private ImapFolderService imapFolderService;

    @InjectMocks
    private ImapAppendService service;

    @Nested
    @DisplayName("appendByRole — best-effort archive")
    class AppendByRole {

        @Mock
        private Store store;
        @Mock
        private Folder folder;
        @Mock
        private MimeMessage message;

        @BeforeEach
        void wireExecuteWithLock() throws Exception {
            // Default: the lock callback is invoked with our mock store. lenient()
            // because missingFolderRoleSkipsExecuteWithLock never reaches here
            // (findFolderNameByRoleOrThrow throws before the service takes the lock).
            lenient().when(imapConnectionManager.executeWithLock(eq(ACCOUNT_ID), eq(Lane.BACKGROUND), any()))
                    .thenAnswer(inv -> {
                        ImapConnectionManager.StoreAction<?> action = inv.getArgument(2);
                        return action.execute(store);
                    });
            lenient().when(store.getFolder(FOLDER_NAME)).thenReturn(folder);
        }

        @Test
        @DisplayName("Happy path — markSeen=true -> SEEN flag set + appendMessages with 1 message")
        void happyPathMarkSeenAppends() throws Exception {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.SENT)).thenReturn(FOLDER_NAME);
            when(folder.exists()).thenReturn(true);
            when(folder.isOpen()).thenReturn(true);

            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.SENT, message, true)).isTrue();

            verify(message).setFlag(Flags.Flag.SEEN, true);

            ArgumentCaptor<Message[]> appended = ArgumentCaptor.forClass(Message[].class);
            verify(folder).appendMessages(appended.capture());
            assertThat(appended.getValue()).hasSize(1).containsExactly(message);

            verify(folder).open(Folder.READ_WRITE);
            verify(folder).close(false);
        }

        @Test
        @DisplayName("markSeen=false (DRAFTS) — SEEN flag is NOT set, but append still happens")
        void draftsDoNotMarkSeen() throws Exception {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn(FOLDER_NAME);
            when(folder.exists()).thenReturn(true);
            when(folder.isOpen()).thenReturn(true);

            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.DRAFTS, message, false)).isTrue();

            verify(message, never()).setFlag(eq(Flags.Flag.SEEN), eq(true));
            verify(folder, times(1)).appendMessages(any());
        }

        @Test
        @DisplayName("Folder does not exist on the server — log warn, no append, no exception")
        void folderDoesNotExistIsBestEffortNoOp() throws Exception {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.SENT)).thenReturn(FOLDER_NAME);
            when(folder.exists()).thenReturn(false);

            // Best-effort: no throw, and a non-existent folder reports failure (false)
            // so a draft-replace caller will not delete the previous revision.
            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.SENT, message, true)).isFalse();

            verify(folder, never()).open(anyInt());
            verify(folder, never()).appendMessages(any());
        }

        @Test
        @DisplayName("Folder role not detected — exception is swallowed (best-effort false), no IMAP lock")
        void missingFolderRoleSkipsExecuteWithLock() {
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.SENT)).thenThrow(
                    new MailOperationException(ErrorCode.FOLDER_ROLE_NOT_FOUND, "No SENT folder for account"));

            assertThat(service.appendByRole(ACCOUNT_ID, FolderRole.SENT, message, true)).isFalse();

            verify(imapConnectionManager, never()).executeWithLock(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("fetchAndDetachMime — IMAP fetch + detach")
    class FetchAndDetachMime {

        private final Session session = Session.getInstance(new Properties());

        @Test
        @DisplayName("UID does not exist in IMAP — Optional.empty()")
        void missingUidReturnsEmpty() throws Exception {
            // executeInFolder invokes the action callback with mock folder/uidFolder.
            // The callback inside calls uidFolder.getMessageByUID(uid) -> null -> return
            // null
            // -> the service wraps it into Optional.empty().
            Folder folder = mock(Folder.class);
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getMessageByUID(123L)).thenReturn(null);

            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq(Lane.INTERACTIVE), eq(FOLDER_NAME),
                    eq(Folder.READ_ONLY), any())).thenAnswer(inv -> {
                        ImapFolderAction<?> action = inv.getArgument(4);
                        return action.apply(folder, uidFolder);
                    });

            Optional<MimeMessage> result = service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 123L, session);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Happy path — the returned MimeMessage is an independent detached copy (via writeTo + parse)")
        void happyPathReturnsDetachedMime() throws Exception {
            // Prepare a "server-side" message with its own Message-ID and content
            // that getMessageByUID will return.
            MimeMessage onServer = new MimeMessage(session);
            onServer.setSubject("Test draft");
            onServer.setText("draft body");
            onServer.saveChanges();
            onServer.setHeader("Message-ID", "<original@example.com>");

            Folder folder = mock(Folder.class);
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getMessageByUID(456L)).thenReturn(onServer);

            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq(Lane.INTERACTIVE), eq(FOLDER_NAME),
                    eq(Folder.READ_ONLY), any())).thenAnswer(inv -> {
                        ImapFolderAction<?> action = inv.getArgument(4);
                        return action.apply(folder, uidFolder);
                    });

            Optional<MimeMessage> result = service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 456L, session);

            assertThat(result).isPresent();
            MimeMessage detached = result.get();
            assertThat(detached.getSubject()).isEqualTo("Test draft");
            assertThat(detached.getHeader("Message-ID")).containsExactly("<original@example.com>");
            // Detached: re-parsed instance, not the same object as onServer.
            assertThat(detached).isNotSameAs(onServer);
        }

        /**
         * IMAP/SMTP audit B1-5. The server states no size for a fetch, it simply sends,
         * so the bound is the only thing between a hostile server and as much heap as
         * it cares to spend. The oversized message here writes its bytes straight to
         * the stream instead of holding them, so the test costs the bound and not twice
         * the draft.
         */
        @Test
        @DisplayName("A draft larger than the bound is refused instead of buffered")
        void oversizedDraftIsRefused() throws Exception {
            Folder folder = mock(Folder.class);
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getMessageByUID(789L)).thenReturn(endlessMessage(session));

            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq(Lane.INTERACTIVE), eq(FOLDER_NAME),
                    eq(Folder.READ_ONLY), any())).thenAnswer(inv -> {
                        ImapFolderAction<?> action = inv.getArgument(4);
                        return action.apply(folder, uidFolder);
                    });

            assertThatThrownBy(() -> service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 789L, session))
                    .isInstanceOf(MailOperationException.class)
                    .hasMessageContaining(String.valueOf(ImapAppendService.MAX_DRAFT_BYTES));
        }

        /**
         * IMAP/SMTP audit B1-18. {@code MimeMessage} loads every line of the header
         * block into {@code InternetHeaders}, some 26 times its size for short lines,
         * and within the 40 MiB bound on the draft that came to a gigabyte. The block
         * is charged before it is parsed; here one header past the bound.
         */
        @Test
        @DisplayName("A draft whose header block would cost more than the bound once parsed is refused")
        void headerBlockPastTheBoundIsRefused() throws Exception {
            serve(901L, rawMessage("a:b\r\n".repeat(shortHeadersWithinBound() + 1) + "\r\nbody\r\n"));

            assertThatThrownBy(() -> service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 901L, session))
                    .isInstanceOf(MailOperationException.class)
                    .hasMessageContaining(String.valueOf(ImapAppendService.MAX_DRAFT_HEADER_BYTES));
        }

        @Test
        @DisplayName("A draft whose header block costs no more than the bound is parsed")
        void headerBlockWithinTheBoundIsParsed() throws Exception {
            serve(902L, rawMessage("a:b\r\n".repeat(shortHeadersWithinBound()) + "\r\nbody\r\n"));

            Optional<MimeMessage> result = service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 902L, session);

            assertThat(result).isPresent();
            assertThat(result.get().getHeader("a")).hasSize(shortHeadersWithinBound());
        }

        /**
         * B1-18. {@code saveChanges} on a parsed {@code MimeMessage} marks it modified
         * and updates the headers of every part, which parses a multipart content into
         * its parts and each part's header block into {@code InternetHeaders}, none of
         * which the bound on the top-level block charges. The untouched draft updates
         * its top-level headers only. Here the second part carries 200,000 header
         * lines, which that parse turns into some 26 MB or more; saving and writing the
         * untouched draft allocates a small fraction of it, measured on this thread.
         */
        @Test
        @DisplayName("Saving and writing an untouched draft never parses its parts' headers")
        void savingAnUntouchedDraftParsesNoPart() throws Exception {
            String content = "--B\r\nContent-Type: text/plain\r\n\r\nhello\r\n--B\r\nContent-Type: text/plain\r\n"
                    + "a:b\r\n".repeat(200_000) + "\r\nbody\r\n--B--\r\n";
            serve(903L,
                    rawMessage("From: a@example.com\r\nTo: b@example.com\r\nSubject: s\r\n"
                            + "Message-ID: <original@example.com>\r\nMIME-Version: 1.0\r\n"
                            + "Content-Type: multipart/mixed; boundary=\"B\"\r\n\r\n" + content));
            MimeMessage detached = service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 903L, session).orElseThrow();
            var threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
            assertThat(threads.isThreadAllocatedMemoryEnabled()).as("allocation is measured on this JVM").isTrue();

            long before = threads.getCurrentThreadAllocatedBytes();
            detached.setSentDate(java.util.Date.from(java.time.Instant.now()));
            detached.saveChanges();
            detached.writeTo(java.io.OutputStream.nullOutputStream(), new String[]{"Bcc", "Content-Length"});
            long allocated = threads.getCurrentThreadAllocatedBytes() - before;

            assertThat(allocated).as("bytes allocated saving and writing the draft").isLessThan(4L * 1024 * 1024);
        }

        /**
         * What an untouched draft sends: its own top-level headers, updated as every
         * send updated them, and its content byte for byte as the server holds it,
         * which is what saving a parsed message sent as well.
         */
        @Test
        @DisplayName("An untouched draft sends its content as the server holds it")
        void untouchedDraftSendsItsContentAsHeld() throws Exception {
            String content = String.join("\r\n", "--B", "Content-Type: text/plain", "", "hello", "--B",
                    "Content-Type: application/octet-stream", "", "attachment", "--B--", "");
            serve(904L,
                    rawMessage("From: a@example.com\r\nTo: b@example.com\r\nSubject: s\r\n"
                            + "Message-ID: <original@example.com>\r\nMIME-Version: 1.0\r\n"
                            + "Content-Type: multipart/mixed; boundary=\"B\"\r\n\r\n" + content));

            MimeMessage detached = service.fetchAndDetachMime(ACCOUNT_ID, FOLDER_NAME, 904L, session).orElseThrow();
            detached.setSentDate(java.util.Date.from(java.time.Instant.now()));
            detached.saveChanges();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            detached.writeTo(out, new String[]{"Bcc", "Content-Length"});

            String sent = out.toString(java.nio.charset.StandardCharsets.US_ASCII);
            assertThat(sent.substring(sent.indexOf("\r\n\r\n") + 4)).isEqualTo(content);
            assertThat(detached.getHeader("To")).containsExactly("b@example.com");
            assertThat(detached.getHeader("MIME-Version")).containsExactly("1.0");
            assertThat(detached.getHeader("Date")).hasSize(1);
            // Saving gives the message a Message-ID of its own, as every send did.
            assertThat(detached.getHeader("Message-ID")).doesNotContain("<original@example.com>");
        }

        /**
         * How many headers {@code a:b}, five bytes with their line end, cost no more
         * than {@link ImapAppendService#MAX_DRAFT_HEADER_BYTES} once parsed.
         */
        private static int shortHeadersWithinBound() {
            return ImapAppendService.MAX_DRAFT_HEADER_BYTES / (BoundedImapProtocol.HEADER_LINE_BYTES + 2 * 5);
        }

        private void serve(long uid, MimeMessage onServer) throws Exception {
            Folder folder = mock(Folder.class);
            UIDFolder uidFolder = mock(UIDFolder.class);
            when(uidFolder.getMessageByUID(uid)).thenReturn(onServer);
            when(imapFolderService.executeInFolder(eq(ACCOUNT_ID), eq(Lane.INTERACTIVE), eq(FOLDER_NAME),
                    eq(Folder.READ_ONLY), any())).thenAnswer(inv -> {
                        ImapFolderAction<?> action = inv.getArgument(4);
                        return action.apply(folder, uidFolder);
                    });
        }

        /** A message on the server that writes exactly these bytes. */
        private MimeMessage rawMessage(String raw) {
            byte[] bytes = raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            return new MimeMessage(session) {
                @Override
                public void writeTo(java.io.OutputStream out) throws java.io.IOException {
                    out.write(bytes);
                }
            };
        }

        /** A message that writes more bytes than the bound and keeps none of them. */
        private static MimeMessage endlessMessage(Session session) {
            return new MimeMessage(session) {
                @Override
                public void writeTo(java.io.OutputStream out) throws java.io.IOException {
                    byte[] chunk = new byte[64 * 1024];
                    for (long written = 0; written <= ImapAppendService.MAX_DRAFT_BYTES; written += chunk.length) {
                        out.write(chunk, 0, chunk.length);
                    }
                }
            };
        }
    }
}
