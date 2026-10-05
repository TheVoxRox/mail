package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;
import org.voxrox.mailbackend.feature.mail.entity.DraftRecipientsEntity;
import org.voxrox.mailbackend.feature.mail.mapper.MessageMapper;
import org.voxrox.mailbackend.feature.mail.repository.DraftRecipientsRepository;

/**
 * {@link DraftPersistenceService} keeping typed recipients in a real SQLite
 * database, with the IMAP side stubbed so the test decides which APPENDs the
 * server stores (IMAP/SMTP audit B1-5). No test transaction: each repository
 * call commits on its own, as it does in the running app, so what a later query
 * sees is what an earlier one wrote.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Sql(statements = {"DELETE FROM draft_recipients", "DELETE FROM messages", "DELETE FROM account_credentials",
        "DELETE FROM accounts"}, executionPhase = ExecutionPhase.BEFORE_TEST_METHOD)
class DraftRecipientsChainIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "DraftRecipientsChainIT", UUID.randomUUID().toString()).toAbsolutePath()
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
    private DraftRecipientsRepository repository;
    @Autowired
    private AccountRepository accountRepository;

    private final ImapAppendService appendService = mock(ImapAppendService.class);
    /** Whether the server stores the next APPEND; it answers without APPENDUID. */
    private final AtomicBoolean storeNext = new AtomicBoolean(true);
    private AccountEntity account;
    private DraftPersistenceService service;

    @BeforeEach
    void setUp() throws Exception {
        account = newAccount("user@example.com");
        AccountService accountService = mock(AccountService.class);
        when(accountService.getAccountOrThrow(account.getId())).thenReturn(account);
        MimeMessageBuilder builder = mock(MimeMessageBuilder.class);
        when(builder.build(any(), any(), any(), any(), any())).thenAnswer(invocation -> mock(MimeMessage.class));
        when(appendService.appendDraft(anyLong(), anyString(), any()))
                .thenAnswer(invocation -> new ImapAppendService.DraftAppendOutcome(storeNext.get(), null, null));
        // No message rows: without APPENDUID a save writes none, so a replaced
        // revision is never found and never deleted from the server.
        service = new DraftPersistenceService(accountService, mock(ImapFolderService.class), mock(MessageService.class),
                mock(ImapActionService.class), appendService, builder, mock(MessageMapper.class),
                mock(AccountRepository.class), repository);
    }

    /**
     * Found by the verification pass over 1.39. The client names as the revision a
     * save replaces the stableId the previous save returned, and the 202 returns it
     * before the APPEND's outcome, so after a rejected save the next one names the
     * rejected revision and the last stored one is named by no save at all. A
     * server rejecting every other APPEND left that stored revision current each
     * time — one more current entry per two autosaves — and could push out a hidden
     * draft's entry that way, the attack of 1.31 by a third route.
     */
    @Test
    @DisplayName("Saves after a rejected one still leave the draft a single current entry (B1-5)")
    void aRejectedSaveDoesNotLeaveTheStoredRevisionCurrent() {
        save("hidden", null, true);
        String previous = null;
        for (int revision = 1; revision <= 10; revision++) {
            save("rev" + revision, previous, revision % 2 == 1);
            previous = "stable-rev" + revision;
        }

        assertThat(repository.findAll()).filteredOn(entry -> entry.getSupersededAt() == null)
                .extracting(DraftRecipientsEntity::getMessageId)
                .containsExactlyInAnyOrder("<hidden@voxrox.org>", "<rev9@voxrox.org>");
    }

    /** One save, naming {@code replaces} the way the client does. */
    private void save(String name, String replaces, boolean stored) {
        storeNext.set(stored);
        service.saveDraftAsync(account.getId(),
                new DraftRequest("to@example.com", null, null, "subject", "body", null, null, null), replaces,
                new DraftPersistenceService.DraftIdentity("<" + name + "@voxrox.org>", "Drafts", "stable-" + name));
    }

    private AccountEntity newAccount(String email) {
        AccountEntity created = new AccountEntity();
        created.setAccountName("Acct " + email);
        created.setEmail(email);
        created.setDisplayName("User");
        created.setActive(true);
        created.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        created.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        return accountRepository.saveAndFlush(created);
    }
}
