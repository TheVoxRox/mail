package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.core.security.CryptoProperties;
import org.voxrox.mailbackend.core.security.CryptoService;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.AccountResponse;
import org.voxrox.mailbackend.feature.account.dto.AccountUpdateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.mapper.AccountMapper;
import org.voxrox.mailbackend.feature.account.repository.AccountCredentialRepository;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.repository.MailProviderRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.service.FolderListCache;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;

/**
 * Editing an account's username over a real SQLite, the real credential and
 * crypto services and the real mapper — the seam where the username lives (the
 * credentials row, not the account row) and where the edit page reads it back
 * from.
 *
 * <p>
 * The edit page sends a username together with an empty password when the user
 * only corrects the login name. The update used to write the credentials row
 * only alongside a new password, so that edit answered 200, the page said
 * "saved", and the old username came back. The connection probe the page runs
 * before saving could not catch it: it logs in with the new username and the
 * stored secret, which is exactly the combination that was never stored.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("it")
class AccountCredentialsUpdateIT {

    private static final Path DB_DIR = Path
            .of("target", "test-tmp", "AccountCredentialsUpdateIT", UUID.randomUUID().toString()).toAbsolutePath()
            .normalize();

    private static final String EMAIL = "jan.novak@example.test";
    private static final String PASSWORD = "app-password-1";
    private static final String REFRESH_TOKEN = "refresh-token-1";
    private static final MailServerSettings IMAP = new MailServerSettings("imap.example.test", 993, true);
    private static final MailServerSettings SMTP = new MailServerSettings("smtp.example.test", 465, true);

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
    private AccountRepository accountRepository;

    @Autowired
    private AccountCredentialRepository credentialRepository;

    @Autowired
    private MailProviderRepository providerRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    private AccountCredentialService credentialService;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        CryptoService crypto = new CryptoService(
                new CryptoProperties("it-only-master-key-at-least-32-chars", "it-only-salt", 10),
                new StartupTimingService());
        crypto.init();
        credentialService = new AccountCredentialService(credentialRepository, crypto);
        accountService = new AccountService(accountRepository, new AccountProviderService(providerRepository),
                credentialService, mock(ImapConnectionManager.class), new AccountMapper(new StaticMessageSource()),
                mock(OAuth2TokenServiceRegistry.class), transactionTemplate, new FolderListCache());
    }

    @Test
    @DisplayName("A username edited without a new password is stored, and the stored password still decrypts")
    void usernameEditedWithoutPasswordIsStored() {
        Long id = passwordAccount();

        AccountResponse response = accountService.updateAccount(id, edit("jan.novak"));
        assertThat(response.username()).isEqualTo("jan.novak");

        startNewPersistenceContext();
        assertThat(accountService.getAccountById(id).username()).isEqualTo("jan.novak");
        assertThat(credentialService.getCredentials(id).getAuthType()).isEqualTo(AuthType.PASSWORD);
        assertThat(credentialService.getDecryptedSecret(id)).isEqualTo(PASSWORD);
    }

    @Test
    @DisplayName("An OAuth2 account refuses a username edit and keeps the username it signed in with")
    void oauth2AccountRefusesUsernameEdit() {
        Long id = oauth2Account();

        assertThatThrownBy(() -> accountService.updateAccount(id, edit("someone.else@example.test")))
                .isInstanceOfSatisfying(ValidationException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("validation.account.oauth2CredentialsReadOnly"));

        startNewPersistenceContext();
        assertThat(accountService.getAccountById(id).username()).isEqualTo(EMAIL);
        assertThat(credentialService.getDecryptedSecret(id)).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    @DisplayName("An OAuth2 account that keeps its username still saves a metadata edit")
    void oauth2AccountSavesMetadataEditWithUnchangedUsername() {
        Long id = oauth2Account();

        accountService.updateAccount(id, edit(EMAIL));

        startNewPersistenceContext();
        AccountResponse stored = accountService.getAccountById(id);
        assertThat(stored.accountName()).isEqualTo("Renamed");
        assertThat(stored.username()).isEqualTo(EMAIL);
        assertThat(stored.authType()).isEqualTo(AuthType.OAUTH2);
        assertThat(credentialService.getDecryptedSecret(id)).isEqualTo(REFRESH_TOKEN);
    }

    private Long passwordAccount() {
        Long id = accountService
                .createAccount(new AccountCreateRequest("Work", null, EMAIL, null, IMAP, SMTP, EMAIL, PASSWORD)).id();
        startNewPersistenceContext();
        return id;
    }

    /**
     * Stored the way the OAuth sign-in stores one: the address as the username and
     * the refresh token as the secret (ExternalProviderLoginService).
     */
    private Long oauth2Account() {
        Long id = passwordAccount();
        credentialService.saveCredentials(accountRepository.findById(id).orElseThrow(), EMAIL, REFRESH_TOKEN,
                AuthType.OAUTH2);
        startNewPersistenceContext();
        return id;
    }

    /**
     * What the edit page sends for a change that leaves the password field empty.
     */
    private static AccountUpdateRequest edit(String username) {
        return new AccountUpdateRequest("Renamed", EMAIL, null, null, true, null, IMAP, SMTP, username, null, true);
    }

    /**
     * Writes what the services changed and forgets every loaded entity, so the next
     * read comes from the database rather than from the objects the update just
     * modified in memory.
     */
    private void startNewPersistenceContext() {
        em.flush();
        em.clear();
    }
}
