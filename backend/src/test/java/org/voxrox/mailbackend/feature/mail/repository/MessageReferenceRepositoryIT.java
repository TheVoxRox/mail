package org.voxrox.mailbackend.feature.mail.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.Sql.ExecutionPhase;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageReferenceEntity;

/**
 * Integration tests for the V2 {@code message_reference} index against a real
 * SQLite + Flyway schema — the References-only reconciliation path. They cover
 * what the mock-based {@code ThreadingServiceTest} structurally cannot: the
 * actual native SQL of
 * {@link MessageReferenceRepository#findOrphanThreadIdsReferencing} and
 * {@link MessageRepository#findMessagesNeedingReferenceIndex}, including the
 * account scoping, own-thread exclusion, and the interface projection mapping.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM message_reference", "DELETE FROM messages", "DELETE FROM account_credentials",
        "DELETE FROM accounts", "DELETE FROM mail_providers"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class MessageReferenceRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "MessageReferenceRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath()
            .normalize();

    @DynamicPropertySource
    static void configureSqliteDatasource(DynamicPropertyRegistry registry) {
        try {
            Files.createDirectories(DB_DIR);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create directory for SQLite test DB: " + DB_DIR, e);
        }
        Path dbFile = DB_DIR.resolve("test.db");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + dbFile.toAbsolutePath() + "?foreign_keys=ON&busy_timeout=5000");
    }

    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MessageReferenceRepository messageReferenceRepository;
    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("Finds the orphan that references the arriving id — scoped to the account, excluding its own thread")
    void findsReferencingOrphanScoped() {
        AccountEntity account = newAccount("ref@example.com");
        AccountEntity other = newAccount("other@example.com");

        // Orphan child references <p> via References only; it started its own thread.
        MessageEntity orphan = newMessage(account, 1L, "<c@example.com>", "T-child", "<p@example.com>");
        // A row already in the arriving message's own thread → must be excluded.
        MessageEntity ownThread = newMessage(account, 2L, "<x@example.com>", "T-arriving", "<p@example.com>");
        // A different account references <p> → out of scope.
        MessageEntity foreign = newMessage(other, 1L, "<c2@example.com>", "T-foreign", "<p@example.com>");
        indexReference(orphan, "<p@example.com>");
        indexReference(ownThread, "<p@example.com>");
        indexReference(foreign, "<p@example.com>");

        List<String> hits = messageReferenceRepository.findOrphanThreadIdsReferencing(account.getId(),
                "<p@example.com>", "T-arriving");

        assertThat(hits).containsExactly("T-child");
    }

    @Test
    @DisplayName("Backfill query returns un-indexed messages that carry a References header, and their raw header")
    void findsMessagesNeedingIndex() {
        AccountEntity account = newAccount("bf@example.com");
        MessageEntity withRefs = newMessage(account, 1L, "<a@example.com>", "T-a", "<p@example.com>");
        // No References header → not a candidate.
        newMessage(account, 2L, "<b@example.com>", "T-b", null);
        // Has References but is already indexed → excluded by NOT EXISTS.
        MessageEntity alreadyIndexed = newMessage(account, 3L, "<d@example.com>", "T-d", "<p@example.com>");
        indexReference(alreadyIndexed, "<p@example.com>");

        List<MessageReferenceBackfillRow> batch = messageRepository.findMessagesNeedingReferenceIndex(account.getId(),
                0L, 10);

        assertThat(batch).extracting(MessageReferenceBackfillRow::getId).containsExactly(withRefs.getId());
        assertThat(batch.get(0).getRefs()).isEqualTo("<p@example.com>");
    }

    @Test
    @DisplayName("deleteByMessageId clears a row's index entries (idempotent re-index)")
    void deleteByMessageIdClearsEntries() {
        AccountEntity account = newAccount("del@example.com");
        MessageEntity msg = newMessage(account, 1L, "<a@example.com>", "T-a", "<p@example.com> <q@example.com>");
        indexReference(msg, "<p@example.com>");
        indexReference(msg, "<q@example.com>");

        messageReferenceRepository.deleteByMessageId(msg.getId());

        assertThat(
                messageReferenceRepository.findOrphanThreadIdsReferencing(account.getId(), "<p@example.com>", "none"))
                .isEmpty();
    }

    private void indexReference(MessageEntity message, String referencedMessageId) {
        MessageReferenceEntity entity = new MessageReferenceEntity();
        entity.setMessageId(message.getId());
        entity.setAccountId(message.getAccount().getId());
        entity.setReferencedMessageId(referencedMessageId);
        messageReferenceRepository.saveAndFlush(entity);
    }

    private AccountEntity newAccount(String email) {
        AccountEntity account = new AccountEntity();
        account.setAccountName("Acct " + email);
        account.setEmail(email);
        account.setDisplayName("User");
        account.setActive(true);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        return accountRepository.saveAndFlush(account);
    }

    private MessageEntity newMessage(AccountEntity account, long uid, String messageId, String threadId,
            String references) {
        MessageEntity m = new MessageEntity();
        m.setStableId(UUID.randomUUID().toString().replace("-", ""));
        m.setAccount(account);
        m.setFolderName("INBOX");
        m.setUid(uid);
        m.setUidValidity(1L);
        m.setReceivedAt(LocalDateTime.now());
        m.setMessageId(messageId);
        m.setThreadId(threadId);
        m.setThreadRootMessageId(messageId);
        m.setThreadPosition(1);
        m.setReferences(references);
        return messageRepository.saveAndFlush(m);
    }
}
