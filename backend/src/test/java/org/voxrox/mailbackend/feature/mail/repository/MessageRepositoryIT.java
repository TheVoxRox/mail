package org.voxrox.mailbackend.feature.mail.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * FTS trigger guard: the V1 {@code messages_au} trigger is scoped via
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

    @Test
    @DisplayName("Conversation grouping: newest representative per thread, folder-scoped counts, newest-first order")
    void conversationGroupingReturnsRepresentativesAndCounts() {
        AccountEntity account = newAccount("conv@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        // Thread A (INBOX): two messages, the newer one unread.
        newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), true);
        MessageEntity aLatest = newConversationMessage(account, 2L, "INBOX", "TA", base.plusMinutes(5), false);
        // Thread B (INBOX): one read message.
        MessageEntity bOnly = newConversationMessage(account, 3L, "INBOX", "TB", base.plusMinutes(3), true);
        // Unthreaded singleton (thread_id NULL) — must group on its own stable id.
        MessageEntity singleton = newConversationMessage(account, 4L, "INBOX", null, base.plusMinutes(2), false);
        // Same thread A but a different folder — must not leak into the INBOX view.
        newConversationMessage(account, 5L, "Archive", "TA", base.plusMinutes(9), false);

        List<Object[]> rows = messageRepository.findConversationRepresentatives(account.getId(), "INBOX", 10, 0L);
        long total = messageRepository.countConversationsByAccountAndFolder(account.getId(), "INBOX");

        assertThat(total).isEqualTo(3L);
        assertThat(rows).hasSize(3);
        // Ordered by representative received_at DESC: A (+5), B (+3), singleton (+2).
        assertConversationRow(rows.get(0), aLatest.getId(), 2, 1);
        assertConversationRow(rows.get(1), bOnly.getId(), 1, 0);
        assertConversationRow(rows.get(2), singleton.getId(), 1, 1);
    }

    @Test
    @DisplayName("Conversation grouping: LIMIT/OFFSET paginate conversations, not raw rows")
    void conversationGroupingPaginates() {
        AccountEntity account = newAccount("conv-page@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), false);
        newConversationMessage(account, 2L, "INBOX", "TA", base.plusMinutes(5), false); // A newest
        newConversationMessage(account, 3L, "INBOX", "TB", base.plusMinutes(3), false); // B
        newConversationMessage(account, 4L, "INBOX", "TC", base.plusMinutes(2), false); // C

        // Order by newest representative: A(+5), B(+3), C(+2).
        List<Object[]> page0 = messageRepository.findConversationRepresentatives(account.getId(), "INBOX", 2, 0L);
        List<Object[]> page1 = messageRepository.findConversationRepresentatives(account.getId(), "INBOX", 2, 2L);

        assertThat(page0).hasSize(2);
        assertThat(page1).hasSize(1);
        assertThat(messageRepository.countConversationsByAccountAndFolder(account.getId(), "INBOX")).isEqualTo(3L);
    }

    @Test
    @DisplayName("Conversation grouping: two unthreaded rows stay separate (COALESCE key never merges NULLs)")
    void unthreadedMessagesDoNotMerge() {
        AccountEntity account = newAccount("conv-null@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        newConversationMessage(account, 1L, "INBOX", null, base.plusMinutes(1), false);
        newConversationMessage(account, 2L, "INBOX", null, base.plusMinutes(2), false);

        List<Object[]> rows = messageRepository.findConversationRepresentatives(account.getId(), "INBOX", 10, 0L);

        assertThat(messageRepository.countConversationsByAccountAndFolder(account.getId(), "INBOX")).isEqualTo(2L);
        assertThat(rows).hasSize(2);
        // Each unthreaded row is its own singleton conversation of exactly one message.
        assertThat(rows).allSatisfy(r -> assertThat(((Number) r[1]).intValue()).isEqualTo(1));
    }

    @Test
    @DisplayName("Cross-folder listing: the page query stays folder-scoped, the size query spans folders minus trash")
    void crossFolderListingPairsFolderPageWithCrossFolderSize() {
        AccountEntity account = newAccount("xf@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        // Thread A: two INBOX messages, a newer sent reply, and a trashed member.
        newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), true);
        MessageEntity aInboxNewest = newConversationMessage(account, 2L, "INBOX", "TA", base.plusMinutes(5), false);
        newConversationMessage(account, 3L, "sent", "TA", base.plusMinutes(9), true);
        newConversationMessage(account, 4L, "trash", "TA", base.plusMinutes(11), false);
        // Thread B lives only in sent — must not appear in the INBOX view.
        newConversationMessage(account, 5L, "sent", "TB", base.plusMinutes(3), false);

        // The page is the plain folder-scoped query in cross-folder mode too: the
        // representative is the newest INBOX member (not the newer sent reply), the
        // sent-only thread stays out, and unread counts only this folder.
        List<Object[]> rows = messageRepository.findConversationRepresentatives(account.getId(), "INBOX", 10, 0L);
        assertThat(rows).hasSize(1);
        assertConversationRow(rows.get(0), aInboxNewest.getId(), 2, 1);

        // Only messageCount is recomputed across folders: 2 INBOX + 1 sent, trash out.
        assertThat(crossFolderSizes(account, List.of("TA", "TB"), List.of("", "trash"))).containsEntry("TA", 3);
    }

    @Test
    @DisplayName("Cross-folder sizes: only the requested threads come back, and only from allowed folders")
    void crossFolderSizesAreScopedToRequestedThreads() {
        AccountEntity account = newAccount("xf-scope@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), true);
        newConversationMessage(account, 2L, "sent", "TB", base.plusMinutes(2), true);
        // Thread C exists only in the trash — excluded, so it cannot answer at all.
        newConversationMessage(account, 3L, "trash", "TC", base.plusMinutes(3), true);

        Map<String, Integer> sizes = crossFolderSizes(account, List.of("TA", "TC"), List.of("", "trash"));

        assertThat(sizes).containsOnlyKeys("TA");
        assertThat(sizes).containsEntry("TA", 1);
    }

    @Test
    @DisplayName("Page order follows the in-folder representative, not a newer member in another folder")
    void conversationPageOrdersByFolderRepresentative() {
        AccountEntity account = newAccount("xf-order@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        // Thread A: INBOX member old, sent member newest overall.
        MessageEntity aInbox = newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), true);
        newConversationMessage(account, 2L, "sent", "TA", base.plusMinutes(10), true);
        // Thread B: only INBOX, newer than A's INBOX member.
        MessageEntity bInbox = newConversationMessage(account, 3L, "INBOX", "TB", base.plusMinutes(5), true);

        List<Object[]> rows = messageRepository.findConversationRepresentatives(account.getId(), "INBOX", 10, 0L);

        // This invariant is what lets the cross-folder mode reuse the folder-scoped
        // page: A's newer sent reply must not float A above B.
        assertThat(rows).hasSize(2);
        assertConversationRow(rows.get(0), bInbox.getId(), 1, 0);
        assertConversationRow(rows.get(1), aInbox.getId(), 1, 0);
    }

    @Test
    @DisplayName("Subject-norm lookup: newest same-norm threaded message inside the window, self excluded")
    void subjectNormLookupFindsNewestInWindow() {
        AccountEntity account = newAccount("norm@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 3, 1, 10, 0);
        MessageEntity older = newConversationMessage(account, 1L, "INBOX", "TA", base.minusDays(5), true);
        older.setSubjectNorm("vylet na hory");
        MessageEntity newer = newConversationMessage(account, 2L, "sent", "TA", base.minusDays(1), true);
        newer.setSubjectNorm("vylet na hory");
        // Same norm but outside the window — must not match.
        MessageEntity ancient = newConversationMessage(account, 3L, "INBOX", "TB", base.minusDays(90), true);
        ancient.setSubjectNorm("vylet na hory");
        messageRepository.saveAllAndFlush(List.of(older, newer, ancient));

        List<MessageEntity> hits = messageRepository.findNewestThreadedBySubjectNorm(account.getId(), "vylet na hory",
                -1L, base.minusDays(30), base.plusDays(30), org.springframework.data.domain.PageRequest.of(0, 1));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getId()).isEqualTo(newer.getId());
    }

    @Test
    @DisplayName("Cross-folder sizes: one mail stored in two folders (Gmail INBOX + All Mail) counts once")
    void crossFolderSizesCountCopiesOnce() {
        AccountEntity account = newAccount("gmail-dupe@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        // Same Message-ID, same thread, two folders — exactly what Gmail mirrors
        // once All Mail has been visited.
        withMessageId(newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), true), "<a@example.com>");
        withMessageId(newConversationMessage(account, 2L, "All Mail", "TA", base.plusMinutes(1), true),
                "<a@example.com>");
        withMessageId(newConversationMessage(account, 3L, "INBOX", "TA", base.plusMinutes(5), false),
                "<b@example.com>");
        withMessageId(newConversationMessage(account, 4L, "All Mail", "TA", base.plusMinutes(5), true),
                "<b@example.com>");

        assertThat(crossFolderSizes(account, List.of("TA"), List.of(""))).containsEntry("TA", 2);
    }

    @Test
    @DisplayName("Cross-folder sizes: a row without a Message-ID never merges with another")
    void crossFolderSizesKeepMessageIdLessRowsApart() {
        AccountEntity account = newAccount("no-mid@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        // The dedup key falls back to stable_id, which is unique per row.
        withMessageId(newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), true), null);
        withMessageId(newConversationMessage(account, 2L, "INBOX", "TA", base.plusMinutes(2), true), null);

        assertThat(crossFolderSizes(account, List.of("TA"), List.of(""))).containsEntry("TA", 2);
    }

    @Test
    @DisplayName("unreadCount stays folder-scoped while messageCount spans folders")
    void conversationUnreadStaysFolderScoped() {
        AccountEntity account = newAccount("xf-unread@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 10, 0);
        MessageEntity inboxSeen = newConversationMessage(account, 1L, "INBOX", "TA", base.plusMinutes(1), true);
        newConversationMessage(account, 2L, "Archive", "TA", base.plusMinutes(5), false);

        List<Object[]> rows = messageRepository.findConversationRepresentatives(account.getId(), "INBOX", 10, 0L);

        // Two messages across folders, but the only unread one sits in Archive.
        // Marking read from the inbox listing cannot reach it, so the row must not
        // claim it — the conversation would stay bold with nothing to clear.
        assertThat(rows).hasSize(1);
        assertConversationRow(rows.get(0), inboxSeen.getId(), 1, 0);
        assertThat(crossFolderSizes(account, List.of("TA"), List.of(""))).containsEntry("TA", 2);
    }

    private Map<String, Integer> crossFolderSizes(AccountEntity account, List<String> threadIds,
            List<String> excludedFolders) {
        Map<String, Integer> sizes = new HashMap<>();
        for (Object[] row : messageRepository.countCrossFolderConversationSizes(account.getId(), threadIds,
                excludedFolders)) {
            sizes.put((String) row[0], ((Number) row[1]).intValue());
        }
        return sizes;
    }

    @Test
    @DisplayName("Subject-fallback orphan lookup: every member of a candidate thread, own thread and stale window out")
    void subjectFallbackOrphanCandidatesReturnAllMembers() {
        AccountEntity account = newAccount("orphan-norm@example.com");
        LocalDateTime base = LocalDateTime.of(2026, 3, 1, 10, 0);
        // T1 — a pure parentless reply cluster, the absorbable case.
        subjectMessage(account, 1L, "T1", base.plusDays(1), "Re: Vylet na hory", "vylet na hory", null);
        subjectMessage(account, 2L, "T1", base.plusDays(2), "Re: Vylet na hory", "vylet na hory", null);
        // T2 — matches by subject, but one member carries In-Reply-To. The query
        // must still hand that member back, or the caller cannot veto the thread.
        subjectMessage(account, 3L, "T2", base.plusDays(1), "Re: Vylet na hory", "vylet na hory", null);
        subjectMessage(account, 4L, "T2", base.plusDays(3), "Re: Vylet na hory", "vylet na hory", "<x@example.com>");
        // T3 — right subject, outside the window.
        subjectMessage(account, 5L, "T3", base.minusDays(90), "Re: Vylet na hory", "vylet na hory", null);
        // The arriving message's own thread is never a candidate for itself.
        subjectMessage(account, 6L, "TSELF", base, "Vylet na hory", "vylet na hory", null);

        List<Object[]> rows = messageRepository.findSubjectFallbackOrphanCandidates(account.getId(), "vylet na hory",
                "TSELF", base.minusDays(30), base.plusDays(30));

        assertThat(rows).hasSize(4);
        assertThat(rows).extracting(r -> (String) r[0]).containsOnly("T1", "T2");
        assertThat(rows).anySatisfy(row -> {
            assertThat((String) row[0]).isEqualTo("T2");
            assertThat((String) row[1]).isEqualTo("Re: Vylet na hory");
            assertThat((String) row[2]).isEqualTo("<x@example.com>");
        });
    }

    private MessageEntity withMessageId(MessageEntity message, String messageId) {
        message.setMessageId(messageId);
        return messageRepository.saveAndFlush(message);
    }

    private MessageEntity subjectMessage(AccountEntity account, long uid, String threadId, LocalDateTime receivedAt,
            String subject, String subjectNorm, String inReplyTo) {
        MessageEntity message = newConversationMessage(account, uid, "INBOX", threadId, receivedAt, true);
        message.setSubject(subject);
        message.setSubjectNorm(subjectNorm);
        message.setInReplyTo(inReplyTo);
        return messageRepository.saveAndFlush(message);
    }

    private static void assertConversationRow(Object[] row, Long representativeId, int messageCount, int unreadCount) {
        assertThat(((Number) row[0]).longValue()).isEqualTo(representativeId);
        assertThat(((Number) row[1]).intValue()).isEqualTo(messageCount);
        assertThat(((Number) row[2]).intValue()).isEqualTo(unreadCount);
    }

    private MessageEntity newConversationMessage(AccountEntity account, long uid, String folder, String threadId,
            LocalDateTime receivedAt, boolean seen) {
        MessageEntity m = new MessageEntity();
        m.setStableId(UUID.randomUUID().toString().replace("-", ""));
        m.setAccount(account);
        m.setFolderName(folder);
        m.setUid(uid);
        m.setUidValidity(1L);
        m.setReceivedAt(receivedAt);
        m.setMessageId("<" + uid + "@example.com>");
        m.setThreadId(threadId);
        m.setThreadRootMessageId(threadId);
        m.setThreadPosition(1);
        m.setSeen(seen);
        return messageRepository.saveAndFlush(m);
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
