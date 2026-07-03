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

/**
 * Integration tests for {@link MessageRepository} against a real SQLite +
 * Flyway schema. They cover what mock-based unit tests structurally cannot: the
 * actual SQL of {@link MessageRepository#findMergeableOrphanThreadIds}, which
 * drives late-arriving-parent reconciliation in {@code ThreadingService}.
 * <p>
 * Regression context: the original query matched only
 * {@code thread_root_message_id = :messageId}. A genuine orphan child (a reply
 * that arrived before its parent) is rooted at its <em>own</em> Message-ID, so
 * that predicate never matched it and reconciliation silently no-opped for the
 * canonical case. {@link #inReplyToMatchFindsLateParentOrphan()} is the guard.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Sql(statements = {"DELETE FROM messages", "DELETE FROM account_credentials", "DELETE FROM accounts",
        "DELETE FROM mail_providers"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class MessageRepositoryIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "MessageRepositoryIT", UUID.randomUUID().toString()).toAbsolutePath().normalize();

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
    private AccountRepository accountRepository;

    @Test
    @DisplayName("In-Reply-To match finds a genuine late-arriving-parent orphan (the root-only query missed it)")
    void inReplyToMatchFindsLateParentOrphan() {
        AccountEntity account = newAccount("late@example.com");
        // Orphan child: it replied to <p@example.com> before that parent arrived, so
        // startNewThread rooted it at its OWN Message-ID, not at <p@example.com>. The
        // former threadRootMessageId-only lookup could never discover it.
        newMessage(account, 1L, "<c@example.com>", "<p@example.com>", "T-child", "<c@example.com>");

        List<String> mergeable = messageRepository.findMergeableOrphanThreadIds(account.getId(), "<p@example.com>",
                "T-parent");

        assertThat(mergeable).containsExactly("T-child");
    }

    @Test
    @DisplayName("Root match still finds a cross-folder duplicate sharing the same root Message-ID")
    void rootMatchFindsCrossFolderDuplicate() {
        AccountEntity account = newAccount("dupe@example.com");
        // A copy that became a root on its own (e.g. Gmail INBOX + All Mail), rooted
        // at <p@example.com> with no In-Reply-To.
        newMessage(account, 1L, "<p@example.com>", null, "T-dup", "<p@example.com>");

        List<String> mergeable = messageRepository.findMergeableOrphanThreadIds(account.getId(), "<p@example.com>",
                "T-new");

        assertThat(mergeable).containsExactly("T-dup");
    }

    @Test
    @DisplayName("Excludes the caller's own thread and never crosses account boundaries")
    void excludesOwnThreadAndOtherAccounts() {
        AccountEntity account = newAccount("scope@example.com");
        AccountEntity other = newAccount("other@example.com");

        // Matches In-Reply-To but lives in the excluded (new arrival's own) thread.
        newMessage(account, 1L, "<c@example.com>", "<p@example.com>", "T-self", "<c@example.com>");
        // Matches In-Reply-To but belongs to a different account → out of scope.
        newMessage(other, 1L, "<c2@example.com>", "<p@example.com>", "T-other-acct", "<c2@example.com>");

        List<String> mergeable = messageRepository.findMergeableOrphanThreadIds(account.getId(), "<p@example.com>",
                "T-self");

        assertThat(mergeable).isEmpty();
    }

    /**
     * V2 migration guard: the FTS5 reindex trigger is scoped via
     * {@code AFTER UPDATE OF subject, sender, content, recipients_*}, and the
     * entity carries {@code @DynamicUpdate} so flag-only flushes never mention
     * those columns. These tests pin both directions of that contract against the
     * real SQLite schema: a flag-only UPDATE leaves the index intact and
     * searchable, while a content UPDATE re-tokenizes it.
     */
    @Test
    @DisplayName("Flag-only UPDATE keeps the FTS index intact — the message stays searchable by its content")
    void flagOnlyUpdateKeepsFtsIndexConsistent() {
        AccountEntity account = newAccount("fts-flags@example.com");
        MessageEntity message = newMessage(account, 1L, "<f@example.com>", null, "T-fts", "<f@example.com>");
        message.setContent("unikatnislovo obsah zpravy");
        messageRepository.saveAndFlush(message);

        messageRepository.updateSeenStatus(message.getStableId(), true);

        List<Number> hits = messageRepository.fullTextSearchIds("unikatnislovo*", account.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(hits).extracting(Number::longValue).containsExactly(message.getId());
    }

    @Test
    @DisplayName("Content UPDATE re-tokenizes the FTS index — old term stops matching, new term matches")
    void contentUpdateReindexesFts() {
        AccountEntity account = newAccount("fts-content@example.com");
        MessageEntity message = newMessage(account, 1L, "<g@example.com>", null, "T-fts2", "<g@example.com>");
        message.setContent("staryobsah zpravy");
        messageRepository.saveAndFlush(message);

        message.setContent("novyobsah zpravy");
        messageRepository.saveAndFlush(message);

        var pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        assertThat(messageRepository.fullTextSearchIds("staryobsah*", account.getId(), pageable).getContent())
                .isEmpty();
        assertThat(messageRepository.fullTextSearchIds("novyobsah*", account.getId(), pageable).getContent())
                .extracting(Number::longValue).containsExactly(message.getId());
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

    private MessageEntity newMessage(AccountEntity account, long uid, String messageId, String inReplyTo,
            String threadId, String threadRootMessageId) {
        MessageEntity m = new MessageEntity();
        m.setStableId(UUID.randomUUID().toString().replace("-", ""));
        m.setAccount(account);
        m.setFolderName("INBOX");
        m.setUid(uid);
        m.setUidValidity(1L);
        m.setReceivedAt(LocalDateTime.now());
        m.setMessageId(messageId);
        m.setInReplyTo(inReplyTo);
        m.setThreadId(threadId);
        m.setThreadRootMessageId(threadRootMessageId);
        m.setThreadPosition(1);
        return messageRepository.saveAndFlush(m);
    }
}
