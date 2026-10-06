package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.dto.MailRequest;
import org.voxrox.mailbackend.feature.mail.entity.DraftRecipientsEntity;
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

    @InjectMocks
    private DraftPersistenceService service;

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

        @Test
        @DisplayName("Exception while loading the draft being replaced -> updateLastError")
        void replaceLookupFailureRecordsLastError() {
            when(messageService.getByStableId(STABLE_ID)).thenThrow(
                    new DataAccessResourceFailureException("could not execute statement [select * from messages]"));

            service.saveDraftAsync(ACCOUNT_ID, draftRequest(), STABLE_ID, IDENTITY);

            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.DRAFT_SAVE_FAILED);
            // The cause, not the database's text (API surface audit, §3).
            assertThat(err.getValue().args()).containsExactly(java.util.Map.entry("cause", "STORAGE"));
            assertThat(err.getValue().fallbackMessage()).startsWith("Draft save failed:").doesNotContain("select");
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
        }

        @Test
        @DisplayName("Append of the new revision fails -> old draft kept, DRAFT_SAVE_FAILED, lastError not cleared")
        void appendFailureKeepsOldDraft() throws Exception {
            MessageEntity old = oldDraft();
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(old));
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(old.getAccount());
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenReturn(ImapAppendService.DraftAppendOutcome.failed());

            service.saveDraftAsync(ACCOUNT_ID, draftRequest(), STABLE_ID, IDENTITY);

            // The only surviving copy must NOT be deleted.
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
            verify(messageService, never()).deleteByStableId(anyString());
            // Failure is surfaced; success is not falsely signalled.
            ArgumentCaptor<AccountLastError> err = ArgumentCaptor.forClass(AccountLastError.class);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID), err.capture(), any(LocalDateTime.class));
            assertThat(err.getValue().code()).isEqualTo(AccountLastErrorCode.DRAFT_SAVE_FAILED);
            verify(accountRepository, never()).clearLastErrorIfCodeIn(anyLong(), any());
            // The old revision is still the current one, so its entry stays current, and
            // the new revision the server did not store is set aside instead (B1-5).
            verify(draftRecipientsRepository, never()).markSuperseded(eq(ACCOUNT_ID), eq(STABLE_ID), any());
            verify(draftRecipientsRepository, never()).supersedeEarlierInChain(anyLong(), anyString(), anyString(),
                    any());
            verify(draftRecipientsRepository).markSuperseded(eq(ACCOUNT_ID), eq(IDENTITY.stableId()),
                    any(LocalDateTime.class));
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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, null, null));

            service.saveDraftAsync(ACCOUNT_ID, draftRequest(), STABLE_ID, IDENTITY);

            verify(imapActionService).hardDelete(ACCOUNT_ID, "Drafts", 100L);
            verify(messageService).deleteByStableId(STABLE_ID);
            // The replaced revision's typed recipients go with it (B1-5).
            verify(draftRecipientsRepository).deleteById(new DraftRecipientsEntity.Key(ACCOUNT_ID, MESSAGE_ID));
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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, null, null));

            service.saveDraftAsync(ACCOUNT_ID, draftRequest(), STABLE_ID, IDENTITY);

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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, null, null));

            service.saveDraftAsync(ACCOUNT_ID, draftRequest(), STABLE_ID, IDENTITY);

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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, 42L, 7L));
            MessageEntity mapped = new MessageEntity();
            mapped.setStableId(IDENTITY.stableId());
            when(messageMapper.toEntity(any(), eq(account), eq(IDENTITY.draftsFolder()), eq(7L))).thenReturn(mapped);

            service.saveDraftAsync(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null, IDENTITY);

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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, 42L, 7L));
            MessageEntity mapped = new MessageEntity();
            mapped.setStableId("some-other-id");
            when(messageMapper.toEntity(any(), eq(account), eq(IDENTITY.draftsFolder()), eq(7L))).thenReturn(mapped);

            service.saveDraftAsync(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null, IDENTITY);

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

            service.saveDraftAsync(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null, IDENTITY);

            verify(appendService).appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any());
            verify(draftRecipientsRepository, never()).insertEntry(any(), any(), any(), any(), any(), any(), any(),
                    any());
            verify(draftRecipientsRepository, never()).save(any());
        }

        @Test
        @DisplayName("Keeping recipients drops the account's entries beyond its newest, by count and not by age")
        void keepingRecipientsKeepsTheNewestEntries() {
            acceptsSaves();

            service.acceptDraftSave(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null);

            // One fewer current entry than the bound, so the entry this save adds makes it
            // whole; the superseded ones have a bound of their own (B1-5).
            InOrder order = inOrder(draftRecipientsRepository);
            order.verify(draftRecipientsRepository).deleteCurrentButNewest(ACCOUNT_ID,
                    DraftPersistenceService.KEPT_DRAFT_RECIPIENTS - 1);
            order.verify(draftRecipientsRepository).deleteSupersededButNewest(ACCOUNT_ID,
                    DraftPersistenceService.KEPT_DRAFT_RECIPIENTS);
            order.verify(draftRecipientsRepository).insertEntry(eq(ACCOUNT_ID), any(), any(), any(), any(), any(),
                    any(), any());
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
         * own entry carries, whatever the server answers.
         */
        @Test
        @DisplayName("A stored save sets aside the earlier revisions of its draft even when the server gave it no row (B1-5)")
        void aSaveSetsAsideTheEarlierRevisionsWithoutTheirRow() throws Exception {
            savesWithoutAppendUid();
            when(messageService.getByStableId("stable-previous-revision")).thenReturn(Optional.empty());
            when(draftRecipientsRepository.findChainId(ACCOUNT_ID, IDENTITY.stableId()))
                    .thenReturn(Optional.of("stable-first-revision"));

            service.saveDraftAsync(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null),
                    "stable-previous-revision", IDENTITY);

            verify(draftRecipientsRepository).supersedeEarlierInChain(eq(ACCOUNT_ID), eq("stable-first-revision"),
                    eq(IDENTITY.messageId()), any(LocalDateTime.class));
            verify(draftRecipientsRepository, never()).markSuperseded(anyLong(), anyString(), any());
            verify(imapActionService, never()).hardDelete(anyLong(), anyString(), anyLong());
        }

        /**
         * B1-5, found by the verification pass over 1.37: the entry is kept before the
         * append, so a server rejecting every APPEND left one more current entry per
         * autosave, the previous revision staying current too, and pushed out a hidden
         * draft's entry that way.
         */
        @Test
        @DisplayName("An APPEND that throws sets the new revision's entry aside too (B1-5)")
        void aFailingAppendSetsTheNewEntryAside() throws Exception {
            AccountEntity account = new AccountEntity();
            account.setId(ACCOUNT_ID);
            when(accountService.getAccountOrThrow(ACCOUNT_ID)).thenReturn(account);
            when(mimeMessageBuilder.build(any(), any(), any(), any(), any())).thenReturn(mock(MimeMessage.class));
            when(appendService.appendDraft(eq(ACCOUNT_ID), eq(IDENTITY.draftsFolder()), any()))
                    .thenThrow(new IllegalStateException("connection lost during APPEND"));

            service.saveDraftAsync(ACCOUNT_ID,
                    new DraftRequest("to@example.com", null, null, "subj", "body", null, null, null), null, IDENTITY);

            verify(draftRecipientsRepository).markSuperseded(eq(ACCOUNT_ID), eq(IDENTITY.stableId()),
                    any(LocalDateTime.class));
            verify(draftRecipientsRepository, never()).supersedeEarlierInChain(anyLong(), anyString(), anyString(),
                    any());
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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, null, null));
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
        @DisplayName("deleteSupersededDraft: own Drafts message -> hard-deleted")
        void deleteSupersededHardDeletesOwnDraft() {
            when(messageService.getByStableId(STABLE_ID)).thenReturn(Optional.of(ownDraft()));
            when(imapFolderService.findFolderNameByRoleOrThrow(ACCOUNT_ID, FolderRole.DRAFTS)).thenReturn("Drafts");

            service.deleteSupersededDraft(ACCOUNT_ID, STABLE_ID);

            verify(imapActionService).hardDelete(ACCOUNT_ID, "Drafts", 100L);
            verify(messageService).deleteByStableId(STABLE_ID);
            verify(draftRecipientsRepository).deleteById(new DraftRecipientsEntity.Key(ACCOUNT_ID, MESSAGE_ID));
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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, null, null));

            String recoveryId = service.saveRecoveryDraft(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null));

            assertThat(recoveryId).isNotBlank();
            verify(appendService).appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any());
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
                    .thenReturn(ImapAppendService.DraftAppendOutcome.failed());

            String recoveryId = service.saveRecoveryDraft(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null));

            assertThat(recoveryId).isNull();
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
                    .thenReturn(new ImapAppendService.DraftAppendOutcome(true, null, null));
            when(draftRecipientsRepository.insertEntry(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("database is locked"));

            String recoveryId = service.saveRecoveryDraft(ACCOUNT_ID,
                    new MailRequest("to@example.com", null, null, "subj", "body", null, null, null));

            assertThat(recoveryId).isNotBlank();
            verify(appendService).appendDraft(eq(ACCOUNT_ID), eq("Drafts"), any());
        }
    }
}
