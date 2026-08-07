package org.voxrox.mailbackend.feature.contact.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.contact.repository.CorrespondentRepository;
import org.voxrox.mailbackend.feature.contact.service.CorrespondentService.HarvestInput;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

@ExtendWith(MockitoExtension.class)
class CorrespondentServiceTest {

    private static final Long ACCOUNT_ID = 1L;
    private static final LocalDateTime SEEN_AT = LocalDateTime.of(2026, 6, 1, 10, 0);

    @Mock
    private CorrespondentRepository correspondentRepository;

    @InjectMocks
    private CorrespondentService service;

    private AccountEntity account() {
        AccountEntity a = new AccountEntity();
        a.setId(ACCOUNT_ID);
        a.setEmail("owner@example.com");
        return a;
    }

    private void harvestIncoming(String sender) {
        service.harvest(account(), new HarvestInput(FolderRole.INBOX, SEEN_AT, sender, null, null, null));
    }

    private void harvestOutgoing(String to, String cc, String bcc) {
        service.harvest(account(), new HarvestInput(FolderRole.SENT, SEEN_AT, "owner@example.com", to, cc, bcc));
    }

    /** Every address handed to the repository, in call order. */
    private java.util.List<String> harvestedEmails() {
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        verify(correspondentRepository, org.mockito.Mockito.atLeastOnce()).upsert(eq(ACCOUNT_ID), email.capture(),
                any(), anyInt(), anyInt(), any());
        return email.getAllValues();
    }

    @Nested
    @DisplayName("header parsing")
    class HeaderParsing {

        @Test
        @DisplayName("a display name containing a comma stays one address")
        void quotedDisplayNameWithComma() {
            // The case a naive split on ',' gets wrong: it would produce two
            // fragments, neither of them a valid address.
            harvestOutgoing("\"Novak, Jan\" <jan@example.com>", null, null);

            verify(correspondentRepository).upsert(ACCOUNT_ID, "jan@example.com", "Novak, Jan", 1, 0, SEEN_AT);
        }

        @Test
        @DisplayName("addresses are stored trimmed and lower-cased")
        void normalizesAddress() {
            harvestIncoming("  Jana Novak <Jana@EXAMPLE.com>  ");

            verify(correspondentRepository).upsert(ACCOUNT_ID, "jana@example.com", "Jana Novak", 0, 1, SEEN_AT);
        }

        @Test
        @DisplayName("one malformed token does not discard the rest of the field")
        void malformedTokenIsSkipped() {
            harvestOutgoing("broken-without-at, ok@example.com", null, null);

            assertThat(harvestedEmails()).containsExactly("ok@example.com");
        }

        @Test
        @DisplayName("a display name that merely repeats the address is not stored")
        void displayNameEqualToAddressIsDropped() {
            harvestIncoming("jana@example.com <jana@example.com>");

            verify(correspondentRepository).upsert(ACCOUNT_ID, "jana@example.com", null, 0, 1, SEEN_AT);
        }

        @Test
        @DisplayName("an address repeated across To and Cc counts once")
        void deduplicatesWithinOneMessage() {
            harvestOutgoing("jana@example.com", "Jana <jana@example.com>", null);

            assertThat(harvestedEmails()).containsExactly("jana@example.com");
        }

        @Test
        @DisplayName("an unparseable field harvests nothing rather than failing")
        void unparseableFieldIsSilent() {
            harvestIncoming("@@@");

            verifyNoInteractions(correspondentRepository);
        }
    }

    @Nested
    @DisplayName("direction")
    class Direction {

        @Test
        @DisplayName("in Sent the recipients are the correspondents, counted as sent")
        void sentFolderHarvestsRecipients() {
            harvestOutgoing("a@example.com", "b@example.com", "c@example.com");

            assertThat(harvestedEmails()).containsExactly("a@example.com", "b@example.com", "c@example.com");
            verify(correspondentRepository).upsert(ACCOUNT_ID, "a@example.com", null, 1, 0, SEEN_AT);
        }

        @Test
        @DisplayName("drafts count as sent — they are addressed by the user too")
        void draftsCountAsSent() {
            service.harvest(account(),
                    new HarvestInput(FolderRole.DRAFTS, SEEN_AT, null, "a@example.com", null, null));

            verify(correspondentRepository).upsert(ACCOUNT_ID, "a@example.com", null, 1, 0, SEEN_AT);
        }

        @Test
        @DisplayName("incoming mail contributes only its sender, never its To/Cc")
        void incomingIgnoresRecipients() {
            // To/Cc of received mail hold the user's own address plus, on bulk
            // mail, a crowd of strangers who are not correspondents at all.
            service.harvest(account(), new HarvestInput(FolderRole.INBOX, SEEN_AT, "sender@example.com",
                    "owner@example.com, stranger@example.com", "other@example.com", null));

            assertThat(harvestedEmails()).containsExactly("sender@example.com");
        }

        @Test
        @DisplayName("the account's own address is never harvested")
        void skipsOwnAddress() {
            harvestOutgoing("owner@example.com, jana@example.com", null, null);

            assertThat(harvestedEmails()).containsExactly("jana@example.com");
        }

        @Test
        @DisplayName("the account's own address is matched case-insensitively")
        void skipsOwnAddressRegardlessOfCase() {
            harvestOutgoing("OWNER@Example.COM", null, null);

            verify(correspondentRepository, never()).upsert(any(), any(), any(), anyInt(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("skipped folders")
    class SkippedFolders {

        @Test
        @DisplayName("trash, junk and newsletters are not harvested at all")
        void skipsFoldersThatMeanUnwanted() {
            for (FolderRole role : new FolderRole[]{FolderRole.TRASH, FolderRole.JUNK, FolderRole.NEWSLETTERS}) {
                service.harvest(account(), new HarvestInput(role, SEEN_AT, "sender@example.com", null, null, null));
            }

            verifyNoInteractions(correspondentRepository);
        }

        @Test
        @DisplayName("inbox is harvested — the roles that reach the database are not all skipped")
        void harvestsInbox() {
            service.harvest(account(), new HarvestInput(FolderRole.INBOX, SEEN_AT, "a@example.com", null, null, null));

            assertThat(harvestedEmails()).containsExactly("a@example.com");
        }

        @Test
        @DisplayName("archive and user folders would be harvested if the sync ever mirrored them")
        void wouldHarvestFiledMail() {
            // Neither role reaches the database today: MailSyncService syncs one
            // folder per role over six roles, and these are not among them. The
            // assertion pins the intent rather than a reachable path, so that
            // widening the sync does not silently need a matching edit here.
            service.harvest(account(), new HarvestInput(FolderRole.ARCHIVE, SEEN_AT, "a@example.com", null, null, null));
            service.harvest(account(), new HarvestInput(FolderRole.USER, SEEN_AT, "b@example.com", null, null, null));

            assertThat(harvestedEmails()).containsExactly("a@example.com", "b@example.com");
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("a blank query returns nothing instead of the whole table")
        void blankQueryShortCircuits() {
            assertThat(service.search(ACCOUNT_ID, "   ", 10)).isEmpty();
            assertThat(service.search(ACCOUNT_ID, "", 10)).isEmpty();
            verifyNoInteractions(correspondentRepository);
        }

        @Test
        @DisplayName("LIKE wildcards in the query are escaped")
        void escapesWildcards() {
            assertThat(CorrespondentService.escapeLikeWildcards("100%_x")).isEqualTo("100\\%\\_x");
            assertThat(CorrespondentService.escapeLikeWildcards("a\\b")).isEqualTo("a\\\\b");
        }
    }
}
