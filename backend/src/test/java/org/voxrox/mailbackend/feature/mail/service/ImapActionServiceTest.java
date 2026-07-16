package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.UIDFolder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.feature.mail.dto.MessageFlag;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@ExtendWith(MockitoExtension.class)
class ImapActionServiceTest {

    private static final long ACCOUNT_ID = 7L;
    private static final String DRAFTS = "[Gmail]/Koncepty";
    private static final long DRAFT_UID = 176L;

    @Mock
    private ImapFolderExecutor folderExecutor;

    @Mock
    private ImapConnectionManager connectionManager;

    @Mock
    private MailMetrics metrics;

    @Test
    void moveOnServerAsyncShouldRecordFailureWhenExecutorThrowsBeforeAction() {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics,
                new FolderListCache());
        doThrow(new RuntimeException("connection down")).when(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"),
                any());

        service.moveOnServerAsync(7L, "INBOX", "Archive", 42L);

        verify(metrics).recordMove(MailMetrics.OUTCOME_FAILURE);
    }

    @Test
    void updateFlagsOnServerAsyncShouldSwallowExecutorFailure() {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics,
                new FolderListCache());
        doThrow(new RuntimeException("connection down")).when(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"),
                any());

        service.updateFlagsOnServerAsync(7L, "INBOX", 42L, MessageFlag.SEEN, true);

        verify(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"), any());
    }

    @Test
    void moveOnServerAsyncShouldUseReadWriteSourceFolder() {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics,
                new FolderListCache());

        service.moveOnServerAsync(7L, "INBOX", "Archive", 42L);

        verify(folderExecutor).executeReadWrite(eq(7L), eq("INBOX"), any());
    }

    /**
     * Idempotency contract: when the draft UID is already gone (a concurrent
     * cleanup path — e.g. the send handler's move-to-trash racing the autosave
     * {@code replaces} delete — removed it first) the postcondition "absent from
     * folder" already holds. So hardDelete must NOT expunge and must NOT warn — the
     * missing UID is a no-op success, not the anomaly the old WARN implied.
     */
    @Test
    void hardDeleteIsIdempotentAndDoesNotWarnWhenUidAlreadyGone() throws Exception {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics,
                new FolderListCache());
        Folder folder = mock(Folder.class);
        UIDFolder uidFolder = mock(UIDFolder.class);
        when(uidFolder.getMessageByUID(DRAFT_UID)).thenReturn(null);
        stubExecutorRunsAction(folder, uidFolder);

        ListAppender<ILoggingEvent> appender = attachAppender();
        boolean result;
        try {
            result = service.hardDelete(ACCOUNT_ID, DRAFTS, DRAFT_UID);
        } finally {
            detachAppender(appender);
        }

        // The postcondition "absent from folder" already holds — a no-op success.
        assertThat(result).isTrue();
        verify(folder, never()).expunge();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    void hardDeleteExpungesAndLogsWhenMessageIsPresent() throws Exception {
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics,
                new FolderListCache());
        Folder folder = mock(Folder.class);
        UIDFolder uidFolder = mock(UIDFolder.class);
        Message msg = mock(Message.class);
        when(uidFolder.getMessageByUID(DRAFT_UID)).thenReturn(msg);
        stubExecutorRunsAction(folder, uidFolder);

        ListAppender<ILoggingEvent> appender = attachAppender();
        boolean result;
        try {
            result = service.hardDelete(ACCOUNT_ID, DRAFTS, DRAFT_UID);
        } finally {
            detachAppender(appender);
        }

        assertThat(result).isTrue();
        verify(msg).setFlag(Flags.Flag.DELETED, true);
        verify(folder).expunge();
        assertThat(appender.list).anyMatch(
                e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("Hard delete of UID " + DRAFT_UID));
    }

    @Test
    void hardDeleteAsyncRecordsSuccessAndInvalidatesFolderListOnPurge() throws Exception {
        FolderListCache folderListCache = mock(FolderListCache.class);
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics, folderListCache);
        Folder folder = mock(Folder.class);
        UIDFolder uidFolder = mock(UIDFolder.class);
        Message msg = mock(Message.class);
        when(uidFolder.getMessageByUID(DRAFT_UID)).thenReturn(msg);
        stubExecutorRunsAction(folder, uidFolder);

        service.hardDeleteAsync(ACCOUNT_ID, DRAFTS, DRAFT_UID);

        verify(folder).expunge();
        verify(metrics).recordPurge(MailMetrics.OUTCOME_SUCCESS);
        verify(folderListCache).invalidate(ACCOUNT_ID);
    }

    @Test
    void hardDeleteAsyncRecordsFailureWhenExpungeFails() throws Exception {
        FolderListCache folderListCache = mock(FolderListCache.class);
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics, folderListCache);
        Folder folder = mock(Folder.class);
        UIDFolder uidFolder = mock(UIDFolder.class);
        Message msg = mock(Message.class);
        when(uidFolder.getMessageByUID(DRAFT_UID)).thenReturn(msg);
        doThrow(new jakarta.mail.MessagingException("EXPUNGE failed")).when(folder).expunge();
        stubExecutorRunsAction(folder, uidFolder);

        service.hardDeleteAsync(ACCOUNT_ID, DRAFTS, DRAFT_UID);

        verify(metrics).recordPurge(MailMetrics.OUTCOME_FAILURE);
        verify(folderListCache, never()).invalidate(ACCOUNT_ID);
    }

    @Test
    void hardDeleteAsyncRecordsFailureWhenExecutorThrows() {
        FolderListCache folderListCache = mock(FolderListCache.class);
        ImapActionService service = new ImapActionService(folderExecutor, connectionManager, metrics, folderListCache);
        doThrow(new RuntimeException("connection down")).when(folderExecutor).executeReadWrite(eq(ACCOUNT_ID),
                eq(DRAFTS), any());

        service.hardDeleteAsync(ACCOUNT_ID, DRAFTS, DRAFT_UID);

        verify(metrics).recordPurge(MailMetrics.OUTCOME_FAILURE);
        verify(folderListCache, never()).invalidate(ACCOUNT_ID);
    }

    /**
     * Makes the mocked executor actually run the supplied folder action against the
     * given mocks.
     */
    private void stubExecutorRunsAction(Folder folder, UIDFolder uidFolder) {
        when(folderExecutor.executeReadWrite(eq(ACCOUNT_ID), eq(DRAFTS), any())).thenAnswer(invocation -> {
            ImapFolderAction<?> action = invocation.getArgument(2);
            return action.apply(folder, uidFolder);
        });
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(ImapActionService.class);
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(ImapActionService.class);
        logger.detachAppender(appender);
        logger.setLevel(null);
    }
}
