package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.DraftRecipientsRepository;

/**
 * Unit tests for {@link DraftPersistenceService} — the draft-message lifecycle
 * split out of {@link SmtpMessageService}. Covers the draft-save replace guard
 * (data-loss prevention), the deterministic identity contract, and the
 * send-triggered draft touches ({@code deleteSupersededDraft},
 * {@code saveRecoveryDraft}) the send path delegates here.
 *
 * <p>
 * As with the send tests, no real MIME/IMAP I/O runs:
 * {@code mimeMessageBuilder} and {@code appendService} are mocked, so the
 * assertions are about the orchestration (which append/delete/upsert calls
 * fire, and the ownership/folder guards), not wire behaviour — that is covered
 * end-to-end by {@code DraftLifecycleGreenMailIT}.
 */
@ExtendWith(MockitoExtension.class)
class DraftPersistenceServiceTest {

    private static final Long ACCOUNT_ID = 11L;
    private static final Long OTHER_ACCOUNT_ID = 22L;
    private static final String STABLE_ID = "draft-stable-id";
    private static final String MESSAGE_ID = "<draft@voxrox.org>";
    private static final DraftPersistenceService.DraftIdentity IDENTITY = new DraftPersistenceService.DraftIdentity(
            "<test-draft@voxrox.org>", "Drafts", "stable-new-revision");

    @Mock
    private AccountService accountService;
    @Mock
    private ImapFolderService imapFolderService;
    @Mock
    private MessageService messageService;
    @Mock
    private ImapActionService imapActionService;
    @Mock
    private ImapAppendService appendService;
    @Mock
    private MimeMessageBuilder mimeMessageBuilder;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private DraftRecipientsRepository draftRecipientsRepository;
    @Spy
    private WaitingDraftSaves waitingSaves = new WaitingDraftSaves();

    @InjectMocks
    private DraftPersistenceService service;

    /**
     * A save as the controller dispatches it: the content waits where
     * {@code acceptDraftSave} leaves it, for the task to take under the lane.
     */
    private void save(DraftRequest request, String replaces) {
        waitingSaves.hold(IDENTITY.stableId(), request);
        service.saveDraftAsync(ACCOUNT_ID, replaces, IDENTITY);
    }

    /**
     * The append as {@link ImapAppendService#appendDraft} runs it: the draft is
     * asked for under the lane, and a source with nothing to append is not
     * appended.
     */
    private static Answer<ImapAppendService.DraftAppendOutcome> appending(
            ImapAppendService.DraftAppendOutcome outcome) {
        return invocation -> invocation.getArgument(2, ImapAppendService.DraftSource.class).message() == null
                ? ImapAppendService.DraftAppendOutcome.failed()
                : outcome;
    }

    @Nested
    @DisplayName("saveDraftAsync — replace guard (data-loss prevention)")
    class SaveDraftReplaceGuard {

        private MessageEntity oldDraft() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            MessageEntity old = new MessageEntity();
            old.setStableId(STABLE_ID);
            old.setMessageId(MESSAGE_ID);
            old.setAccount(account);
            old.setFolderName("Drafts");
            old.setUid(100L);
            return old;
        }

        private DraftRequest draftRequest() {
            return new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null);
        }

        /**
         * The revisions a save replaces are looked up once it is stored (B1-25): a
         * lookup that fails then leaves their copies for the next save, and must not
         * report the stored save as failed.
         */
        @Test
        @DisplayName("Exception while loading the draft being replaced, after the new revision is stored -> no failure, nothing deleted")
        void replaceLookupFailureAfterTheAppendRecordsNoFailure() throws Exception {
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(oldDraft().getAccount());
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));
            when(messageService.getByStableId(STABLE_ID)).thenThrow(
                    new DataAccessResourceFailureException("could not execute statement [select * from messages]"));

            save(draftRequest(), STABLE_ID);

            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
            verify(accountRepository).clearLastErrorIfCodeIn(eq(ACCOUNT_ID), any());
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Append of the new revision fails -> old draft kept, DRAFT_SAVE_FAILED, lastError not cleared")
        void appendFailureKeepsOldDraft() throws Exception {
            MessageEntity old = oldDraft();
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(old.getAccount());
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(ImapAppendService.DraftAppendOutcome.failed()));

            save(draftRequest(), STABLE_ID);

            // The only surviving copy must NOT be deleted.
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());
            // Failure is surfaced; success is not falsely signalled.
            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.DRAFT_SAVE_FAILED);
            verify(accountRepository, never()).clearLastErrorIfCodeIn(anyLong(), any());
            // The old revision is still the current one, so its entry stays current, and
            // the new revision the server did not store takes no current place (B1-5).
            verify(draftRecipientsRepository, never()).markStored(anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("Append of the new revision succeeds -> old draft deleted, lastError cleared")
        void appendSuccessDeletesOldDraft() throws Exception {
            MessageEntity old = oldDraft();
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(old));
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(old.getAccount());
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));

            save(draftRequest(), STABLE_ID);

            verify(imapActionService).hardDelete(ACCOUNT_ID, "Drafts", 100L);
            verify(messageService).deleteByStableId(STABLE_ID);
            // The replaced revision's typed recipients stay, whatever the server answered
            // to the delete: the save only looks up the chain to make its own entry
            // current (B1-5, reopened at 1.57), and its earlier revisions to delete the
            // copies the server still holds.
            verify(draftRecipientsRepository).findLaterRevision(ACCOUNT_ID, IDENTITY.stableId());
            verify(draftRecipientsRepository).findChainId(ACCOUNT_ID, IDENTITY.stableId());
            verify(draftRecipientsRepository).findEarlierRevisions(ACCOUNT_ID, IDENTITY.stableId());
            verifyNoMoreInteractions(draftRecipientsRepository);
            // Conditional clear scoped to send-pipeline codes — a successful draft
            // save must not wipe a standing sync error (shared last_error slot).
            verify(accountRepository).clearLastErrorIfCodeIn(eq(ACCOUNT_ID), any());
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
        }

        @Test
        @DisplayName("replaces points at a message NOT in Drafts -> new revision saved, target NOT expunged")
        void replaceTargetInOtherFolderIsNotDeleted() throws Exception {
            MessageEntity notADraft = oldDraft();
            notADraft.setFolderName("INBOX");
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(notADraft));
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(notADraft.getAccount());
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));

            save(draftRequest(), STABLE_ID);

            // A wrong replaces id must never expunge received mail.
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());
            verify(accountRepository).clearLastErrorIfCodeIn(eq(ACCOUNT_ID), any());
        }

        @Test
        @DisplayName("replaces points at another account's message -> new revision saved, target NOT expunged")
        void replaceTargetOtherAccountIsNotDeleted() throws Exception {
            AccountEntity otherAccount = new AccountEntity();
            otherAccount.setId(OTHER_ACCOUNT_ID);
            MessageEntity foreign = oldDraft();
            foreign.setAccount(otherAccount);
            AccountEntity ownAccount = new AccountEntity();
            ownAccount.setId(ACCOUNT_ID);

            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(foreign));
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(ownAccount);
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));

            save(draftRequest(), STABLE_ID);

            // The ownership check fails first, so the Drafts folder is never resolved
            // and the other account's message is left untouched.
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());
        }
    }

    @Nested
    @DisplayName("Draft identity — deterministic stableId minted before the async append")
    class DraftIdentityContract {

        @Test
        @DisplayName("prepareDraftIdentity: stableId matches what MessageMapper derives from the same Message-ID")
        void identityMatchesMapperDerivation() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");

            DraftPersistenceService.DraftIdentity identity = service.prepareDraftIdentity(ACCOUNT_ID);

            assertThat(identity.draftsFolder()).isEqualTo("Drafts");
            assertThat(identity.messageId()).startsWith("<").endsWith("@voxrox.org>");
            // The load-bearing invariant: the id returned in the 202 is the id the
            // sync/upsert derivation produces for the same Message-ID.
            assertThat(identity.stableId()).isEqualTo(org.voxrox.mailbackend.feature.mail.mapper.MessageStableId
                    .compute(ACCOUNT_ID, "Drafts", identity.messageId(), null, null));
        }

        @Test
        @DisplayName("Append with APPENDUID -> local row inserted under the identity's stableId")
        void appendWithUidUpsertsLocalRow() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, 42L, 7L)));
            MessageEntity mapped = new MessageEntity();
            mapped.setStableId(IDENTITY.stableId());
            when(messageMapper.toEntity(any(), eq(account), eq(IDENTITY.draftsFolder()), eq(7L))).thenReturn(mapped);

            save(new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            verify(messageService).insertIfAbsent(mapped);
            // The typed recipients stay: the sync rewrites the row from the server's copy
            // whenever the server presents the draft anew (B1-5).
            verify(draftRecipientsRepository, never()).deleteById(any());
        }

        @Test
        @DisplayName("Mapper derives a DIFFERENT stableId -> row NOT inserted (contract-drift guard)")
        void mismatchedIdentitySkipsUpsert() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, 42L, 7L)));
            MessageEntity mapped = new MessageEntity();
            mapped.setStableId("some-other-id");
            when(messageMapper.toEntity(any(), eq(account), eq(IDENTITY.draftsFolder()), eq(7L))).thenReturn(mapped);

            save(new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            verify(messageService, never()).insertIfAbsent(any());
        }

        @Test
        @DisplayName("Accepting a save keeps what the user typed under the minted identity (B1-5)")
        void acceptingASaveKeepsTheTypedRecipients() {
            acceptsSaves();

            DraftPersistenceService.DraftIdentity identity = service.acceptDraftSave(ACCOUNT_ID, new DraftRequest(
                    "to@example.com", "cc@example.com", "bcc@example.com", "subj", "body", null, null, null), null);

            // The stableId the next save will name as the revision it replaces, and the
            // Message-ID the send looks the entry up by.
            verify(draftRecipientsRepository).insertEntry(eq(ACCOUNT_ID), eq(identity.messageId()),
                    eq(identity.stableId()), eq(identity.stableId()), eq("to@example.com"), eq("cc@example.com"),
                    eq("bcc@example.com"), any(LocalDateTime.class));
            verifyNoInteractions(appendService);
        }

        /**
         * B1-5, reopened at 1.41: the entry was written in the async task, after the
         * 202 had handed its stableId to the client, so the next save could look the
         * chain up before the entry existed. It is written before the save is accepted,
         * and the async save writes none.
         */
        @Test
        @DisplayName("The entry is written when the save is accepted, not by the async save (B1-5)")
        void theAsyncSaveWritesNoEntry() throws Exception {
            savesWithoutAppendUid();

            save(new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            verify(appendService).appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any());
            verify(draftRecipientsRepository, never()).insertEntry(any(), any(), any(), any(), any(), any(), any(),
                    any());
            verify(draftRecipientsRepository, never()).save(any());
        }

        @Test
        @DisplayName("Keeping recipients drops the account's drafts beyond its newest, by count and not by age")
        void keepingRecipientsKeepsTheNewestEntries() {
            acceptsSaves();

            DraftPersistenceService.DraftIdentity identity = service.acceptDraftSave(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            // The saved draft, a new chain here, kept besides one draft fewer than the
            // bound, and of its followed revisions one fewer, the place the revision this
            // save follows takes (B1-5).
            InOrder order = inOrder(draftRecipientsRepository);
            order.verify(draftRecipientsRepository).deleteDraftsButNewest(ACCOUNT_ID, identity.stableId(),
                    DraftPersistenceService.KEPT_DRAFTS - 1);
            order.verify(draftRecipientsRepository).deleteRevisionsButNewest(ACCOUNT_ID, identity.stableId(),
                    DraftPersistenceService.KEPT_REVISIONS_PER_DRAFT - 1);
            order.verify(draftRecipientsRepository).insertEntry(eq(ACCOUNT_ID), any(), any(), eq(identity.stableId()),
                    any(), any(), any(), any());
        }

        @Test
        @DisplayName("A save naming a revision joins that revision's chain when it is accepted (B1-5)")
        void anAcceptedSaveJoinsTheNamedRevisionsChain() {
            acceptsSaves();
            when(draftRecipientsRepository.findChainId(ACCOUNT_ID, "stable-previous-revision"))
                    .thenReturn(Optional.of("stable-first-revision"));

            DraftPersistenceService.DraftIdentity identity = service.acceptDraftSave(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null),
                    "stable-previous-revision");

            verify(draftRecipientsRepository).insertEntry(eq(ACCOUNT_ID), eq(identity.messageId()),
                    eq(identity.stableId()), eq("stable-first-revision"), any(), any(), any(),
                    any(LocalDateTime.class));
            // It adds no draft, so it drops none; only its own revisions are bounded.
            verify(draftRecipientsRepository, never()).deleteDraftsButNewest(any(), any(), anyInt());
            verify(draftRecipientsRepository).deleteRevisionsButNewest(ACCOUNT_ID, "stable-first-revision",
                    DraftPersistenceService.KEPT_REVISIONS_PER_DRAFT - 1);
        }

        @Test
        @DisplayName("A save that replaces nothing starts a chain of its own")
        void aFirstSaveStartsItsOwnChain() {
            acceptsSaves();

            DraftPersistenceService.DraftIdentity identity = service.acceptDraftSave(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            verify(draftRecipientsRepository).insertEntry(eq(ACCOUNT_ID), eq(identity.messageId()),
                    eq(identity.stableId()), eq(identity.stableId()), any(), any(), any(), any(LocalDateTime.class));
            verify(draftRecipientsRepository, never()).findChainId(anyLong(), anyString());
        }

        /**
         * B1-5, reopened at 1.31: a save used to retire the replaced revision's entry
         * only when it found that revision's row and the server deleted it, so a server
         * without APPENDUID made the entries grow by saves. Since the pass over 1.39 a
         * stored save sets aside every earlier revision of the draft, by the chain its
         * own entry carries, whatever the server answers, and since 1.47 that is also
         * what makes its own entry current.
         */
        @Test
        @DisplayName("A stored save becomes current and sets aside the earlier revisions of its draft even when the server gave it no row (B1-5)")
        void aSaveSetsAsideTheEarlierRevisionsWithoutTheirRow() throws Exception {
            savesWithoutAppendUid();
            when(messageService.getByStableId("stable-previous-revision")).thenReturn(Optional.empty());
            when(draftRecipientsRepository.findChainId(ACCOUNT_ID, IDENTITY.stableId()))
                    .thenReturn(Optional.of("stable-first-revision"));

            save(new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null),
                    "stable-previous-revision");

            verify(draftRecipientsRepository).markStored(eq(ACCOUNT_ID), eq("stable-first-revision"),
                    eq(IDENTITY.messageId()), any(LocalDateTime.class));
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
        }

        /**
         * B1-5, found by the verification pass over 1.37: the entry is kept before the
         * append, so a server rejecting every APPEND left one more current entry per
         * autosave, the previous revision staying current too, and pushed out a hidden
         * draft's entry that way.
         */
        @Test
        @DisplayName("An APPEND that throws leaves the new revision's entry out of the current ones (B1-5)")
        void aFailingAppendDoesNotMakeTheNewEntryCurrent() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(invocation -> {
                        invocation.getArgument(2, ImapAppendService.DraftSource.class).message();
                        throw new IllegalStateException("connection lost during APPEND");
                    });

            save(new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            verify(draftRecipientsRepository, never()).markStored(anyLong(), anyString(), anyString(), any());
        }

        /**
         * B1-5, reopened at 1.43: since 1.42 the entry is kept when the save is
         * accepted, before the task runs, and only a failed APPEND set it aside, so a
         * task that stopped before the APPEND left it current. A reply carrying a
         * folded {@code References} header failed the message build every time, so each
         * of its autosaves added a current entry.
         */
        @Test
        @DisplayName("A save whose message cannot be built leaves its entry out of the current ones (B1-5)")
        void aSaveThatCannotBeBuiltDoesNotMakeItsEntryCurrent() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any()))
                    .thenThrow(new MessagingException("Illegal line break in References"));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));

            save(new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            verify(draftRecipientsRepository, never()).markStored(anyLong(), anyString(), anyString(), any());
            // The build's own failure is recorded, not a failed APPEND: the build runs
            // under the lane since B1-25, and is rethrown after it.
            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().args()).doesNotContainEntry(AccountLastErrorCode.CAUSE, "SERVER_REJECTED");
        }

        /**
         * B1-5, reopened at 1.43: the same for a task that stops before the build.
         * Until B1-25 the revision it replaces was looked up there; it is looked up
         * after the APPEND now, and the account is the stop before the lane.
         */
        @Test
        @DisplayName("A save that stops before the lane leaves its entry out of the current ones and its content behind (B1-5)")
        void aSaveThatStopsBeforeTheLaneDoesNotMakeItsEntryCurrent() {
            when(accountService.getAccountOrThrow(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("database is locked"));

            save(new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null),
                    "stable-previous-revision");

            verify(draftRecipientsRepository, never()).markStored(anyLong(), anyString(), anyString(), any());
            verifyNoInteractions(appendService);
            assertThat(waitingSaves.take(IDENTITY.stableId())).as("the content the task never took").isNull();
        }

        /**
         * B1-5, reopened at 1.41: an entry that could not be written cost the draft its
         * check against what was typed, and the revision was appended anyway, to be
         * checked against the server's own copy. A save without its entry is now not
         * accepted, so nothing is appended and the client reports the failed save.
         */
        @Test
        @DisplayName("A save whose typed recipients cannot be kept is not accepted, and nothing is appended (B1-5)")
        void aSaveWithoutItsEntryIsNotAccepted() {
            acceptsSaves();
            when(draftRecipientsRepository.insertEntry(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("database is locked"));

            assertThatThrownBy(() -> service.acceptDraftSave(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null))
                    .hasMessageContaining("database is locked");

            verifyNoInteractions(appendService);
        }

        private void acceptsSaves() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
        }

        private void savesWithoutAppendUid() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));
        }
    }

    @Nested
    @DisplayName("Send-triggered draft touches — supersede delete and recovery park")
    class SupersedeAndRecovery {

        private MessageEntity ownDraft() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            MessageEntity draft = new MessageEntity();
            draft.setStableId(STABLE_ID);
            draft.setMessageId(MESSAGE_ID);
            draft.setAccount(account);
            draft.setFolderName("Drafts");
            draft.setUid(100L);
            return draft;
        }

        @Test
        @DisplayName("deleteSupersededDraft: own Drafts message -> hard-deleted, its typed recipients set aside")
        void deleteSupersededHardDeletesOwnDraft() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(ownDraft()));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");

            service.deleteSupersededDraft(ACCOUNT_ID, STABLE_ID);

            verify(imapActionService).hardDelete(ACCOUNT_ID, "Drafts", 100L);
            verify(messageService).deleteByStableId(STABLE_ID);
            // Set aside, not dropped: the server can claim the delete and keep the draft
            // (B1-5, reopened at 1.57).
            verify(draftRecipientsRepository).setAside(eq(ACCOUNT_ID), eq(MESSAGE_ID), any(LocalDateTime.class));
            verify(draftRecipientsRepository).findEarlierRevisions(ACCOUNT_ID, STABLE_ID);
            verifyNoMoreInteractions(draftRecipientsRepository);
        }

        @Test
        @DisplayName("deleteSupersededDraft: target outside Drafts -> kept (delivered mail untouched)")
        void deleteSupersededKeepsNonDraftTarget() {
            MessageEntity notADraft = ownDraft();
            notADraft.setFolderName("INBOX");
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(notADraft));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");

            service.deleteSupersededDraft(ACCOUNT_ID, STABLE_ID);

            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());
        }

        @Test
        @DisplayName("saveRecoveryDraft: appends the composed content and returns the recovery stableId")
        void saveRecoveryDraftParksContent() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));
            when(draftRecipientsRepository.findChainId(eq(ACCOUNT_ID), anyString()))
                    .thenAnswer(invocation -> Optional.of(invocation.getArgument(1, String.class)));

            String recoveryId = service.saveRecoveryDraft(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null));

            assertThat(recoveryId).isNotBlank();
            verify(appendService).appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any());
            // The stored recovery draft is a draft of its own, its entry the current one
            // (B1-5; the pass over 1.44 found no test that would see it set aside).
            verify(draftRecipientsRepository).markStored(eq(ACCOUNT_ID), eq(recoveryId), anyString(),
                    any(LocalDateTime.class));
        }

        @Test
        @DisplayName("saveRecoveryDraft: append fails -> null (the failure notification carries no pointer)")
        void saveRecoveryDraftReturnsNullWhenAppendFails() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any()))
                    .thenAnswer(appending(ImapAppendService.DraftAppendOutcome.failed()));

            String recoveryId = service.saveRecoveryDraft(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null));

            assertThat(recoveryId).isNull();
            // Its entry takes no current place (B1-5; until 1.47 no test covered it).
            verify(draftRecipientsRepository, never()).markStored(anyLong(), anyString(), anyString(), any());
        }

        /**
         * The one draft appended without its entry: the recovery draft is the only copy
         * of what the user wrote, and losing it is worse than its untouched send being
         * checked against the server's copy (B1-5, 1.42).
         */
        @Test
        @DisplayName("saveRecoveryDraft: recipients that cannot be kept do not stop the content being parked")
        void saveRecoveryDraftParksContentWithoutItsEntry() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));
            when(draftRecipientsRepository.insertEntry(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("database is locked"));

            String recoveryId = service.saveRecoveryDraft(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null));

            assertThat(recoveryId).isNotBlank();
            verify(appendService).appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any());
        }

        /**
         * B1-5, reopened at 1.43: the recovery draft keeps its entry before the build,
         * and a build that failed was caught without setting the entry aside.
         */
        @Test
        @DisplayName("saveRecoveryDraft: a message that cannot be built leaves its kept entry out of the current ones")
        void saveRecoveryDraftDoesNotMakeItsEntryCurrentWhenTheBuildFails() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any()))
                    .thenThrow(new MessagingException("Illegal line break in References"));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));

            String recoveryId = service.saveRecoveryDraft(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null));

            assertThat(recoveryId).isNull();
            verify(draftRecipientsRepository).insertEntry(eq(ACCOUNT_ID), any(), any(), any(), any(), any(), any(),
                    any());
            verify(draftRecipientsRepository, never()).markStored(anyLong(), anyString(), anyString(), any());
        }
    }

    /**
     * IMAP/SMTP audit B1-25: the 202 returns before the APPEND, which waits for the
     * account's background lane, and every task waiting there held its content, so
     * autosaves queued behind a sync or a slow upload filled the heap, and each
     * appended a copy of the draft for the next save to delete.
     */
    @Nested
    @DisplayName("Waiting saves — content held until the lane, appended only for the draft's newest save (B1-25)")
    class WaitingSaves {

        private DraftRequest draftRequest() {
            return new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null);
        }

        private void hasAccount() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
        }

        private void appendsStored() {
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(appending(new ImapAppendService.DraftAppendOutcome(true, null, null)));
        }

        @Test
        @DisplayName("A save whose draft has a later save accepted builds and appends nothing, and records no failure")
        void aFollowedSaveAppendsNothing() {
            hasAccount();
            appendsStored();
            when(draftRecipientsRepository.findLaterRevision(ACCOUNT_ID, IDENTITY.stableId()))
                    .thenReturn(Optional.of("stable-later-revision"));

            save(draftRequest(), STABLE_ID);

            verifyNoInteractions(mimeMessageBuilder, messageService, imapActionService);
            verify(draftRecipientsRepository, never()).markStored(anyLong(), anyString(), anyString(), any());
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
        }

        @Test
        @DisplayName("A save whose content a later save dropped builds and appends nothing")
        void aDroppedSaveAppendsNothing() {
            hasAccount();
            appendsStored();

            service.saveDraftAsync(ACCOUNT_ID, STABLE_ID, IDENTITY);

            verifyNoInteractions(mimeMessageBuilder, messageService, imapActionService);
            verify(accountRepository, never()).updateLastError(anyLong(), any(AccountLastError.class),
                    any(LocalDateTime.class));
        }

        @Test
        @DisplayName("Accepting a later save of a draft drops the waiting content of its earlier saves")
        void acceptingALaterSaveDropsTheEarlierContent() {
            hasAccount();
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(draftRecipientsRepository.findChainId(ACCOUNT_ID, STABLE_ID)).thenReturn(Optional.of(STABLE_ID));
            when(draftRecipientsRepository.findEarlierRevisions(eq(ACCOUNT_ID), anyString()))
                    .thenReturn(List.of(STABLE_ID));
            waitingSaves.hold(STABLE_ID, draftRequest());

            DraftPersistenceService.DraftIdentity later = service.acceptDraftSave(ACCOUNT_ID, draftRequest(),
                    STABLE_ID);

            assertThat(waitingSaves.take(STABLE_ID)).as("the earlier save's content").isNull();
            assertThat(waitingSaves.take(later.stableId())).as("the later save's content").isNotNull();
        }

        @Test
        @DisplayName("An append retried after an authentication failure builds the draft once")
        void aRetriedAppendBuildsTheDraftOnce() throws Exception {
            hasAccount();
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenAnswer(invocation -> {
                        ImapAppendService.DraftSource source = invocation.getArgument(2,
                                ImapAppendService.DraftSource.class);
                        MimeMessage first = source.message();
                        assertThat(source.message()).as("the message the retry appends").isSameAs(first).isNotNull();
                        return new ImapAppendService.DraftAppendOutcome(true, null, null);
                    });

            save(draftRequest(), null);

            verify(mimeMessageBuilder, times(1)).build(any(), any(), any(), any(), any());
            verify(accountRepository).clearLastErrorIfCodeIn(eq(ACCOUNT_ID), any());
        }

        @Test
        @DisplayName("The revisions a stored save replaces are looked up after its APPEND")
        void theReplacedRevisionsAreLookedUpAfterTheAppend() throws Exception {
            hasAccount();
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            appendsStored();

            save(draftRequest(), STABLE_ID);

            InOrder order = inOrder(appendService, messageService, draftRecipientsRepository);
            order.verify(appendService).appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any());
            order.verify(messageService).getByStableId(STABLE_ID);
            order.verify(draftRecipientsRepository).findEarlierRevisions(ACCOUNT_ID, IDENTITY.stableId());
        }

        @Test
        @DisplayName("A send drops the waiting save of the draft it sent")
        void aSendDropsTheSentDraftsWaitingSave() {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            MessageEntity sent = new MessageEntity();
            sent.setStableId(STABLE_ID);
            sent.setMessageId(MESSAGE_ID);
            sent.setAccount(account);
            sent.setFolderName("Drafts");
            sent.setUid(100L);
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(sent));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            waitingSaves.hold(STABLE_ID, draftRequest());

            service.deleteSupersededDraft(ACCOUNT_ID, STABLE_ID);

            assertThat(waitingSaves.take(STABLE_ID)).as("the sent draft's waiting content").isNull();
        }
    }
}
