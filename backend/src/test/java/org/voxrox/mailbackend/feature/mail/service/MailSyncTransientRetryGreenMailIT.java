package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import jakarta.mail.MessagingException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * Integration test for the transient-blip retry (todo.md bug D) against a real
 * IMAP server (GreenMail) and the full service graph.
 *
 * <p>
 * The Mockito unit tests in {@code MailSyncServiceTest} stub
 * {@code executeInFolder} away, so they prove the retry-loop bookkeeping but not
 * that a transient failure actually propagates as a {@link TransientImapException}
 * through the real {@link ImapFolderExecutor} pass-through and is recovered by
 * dropping and rebuilding the pooled connection in {@link ImapConnectionManager}.
 * This IT closes exactly that gap — the deterministic stand-in for the live
 * {@code tauri:dev} smoke that could never summon a real blip on demand.
 *
 * <p>
 * Fault is injected at the sync seam: {@link MessageDownloader#syncNewMessages}
 * is spied so its first invocation throws the Angus "failed to create new store
 * connection" message, then delegates to the real method. Everything else —
 * connection pool, per-account lock, SQLite persistence, the live IMAP wire — is
 * real, so a clean recovery here proves the whole mechanism end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out of the test — the only sync run is the
        // explicit one below, so the syncNewMessages invocation count is exact.
        "mail.client.sync.initial-delay=PT1H"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class MailSyncTransientRetryGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "MailSyncTransientRetryGreenMailIT")
            .toAbsolutePath().normalize();

    private static final String EMAIL = "retry-it@greenmail.local";
    private static final String LOGIN = "retry-it-user";
    private static final String PASSWORD = "retry-it-password";
    private static final String INBOX = "INBOX";

    static {
        try {
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
            System.setProperty("spring.security.oauth2.client.registration.google.client-id", "dummy-client-id");
            System.setProperty("spring.security.oauth2.client.registration.google.client-secret",
                    "dummy-client-secret");
            System.setProperty("spring.security.oauth2.client.registration.microsoft.client-id", "dummy-client-id");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Plain IMAP on an ephemeral loopback port; started once for the class. */
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAP)).withPerMethodLifecycle(false);

    @AfterAll
    static void clearSystemProperties() {
        System.clearProperty("app.data-dir");
        System.clearProperty("logging.file.name");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-id");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-secret");
        System.clearProperty("spring.security.oauth2.client.registration.microsoft.client-id");
    }

    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MailSyncService mailSyncService;

    /**
     * Real {@link MessageDownloader}, spied so a single download pass can be made to
     * fail with a transient blip while the next pass runs the real logic.
     */
    @MockitoSpyBean
    private MessageDownloader messageDownloader;

    private GreenMailUser user;
    private AccountEntity account;

    @BeforeEach
    void setUpAccount() {
        user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            int imapPort = greenMail.getImap().getPort();
            MailServerSettings server = new MailServerSettings("127.0.0.1", imapPort, false);
            accountService.createAccount(
                    new AccountCreateRequest("GreenMail Retry IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
    }

    @Test
    @DisplayName("A transient blip mid-sync is retried over the live IMAP connection and recovers (WARN, no last_error)")
    void transientBlipRecoversOverLiveImap() throws Exception {
        deliver("First message", "body one");
        deliver("Second message", "body two");

        // First download pass hits the Angus "failed to create new store connection"
        // blip (todo.md bug D); the retried pass — after performFullSyncCycle drops the
        // pooled connection and reconnects to the live server — delegates to the real
        // method and downloads both messages.
        doThrow(new MessagingException("failed to create new store connection")).doCallRealMethod()
                .when(messageDownloader).syncNewMessages(any());

        boolean succeeded = mailSyncService.performFullSyncCycle(account, INBOX);

        // Recovered: the cycle reports success and actually persisted both messages
        // over a freshly reconnected store — proving the transient escaped through the
        // real ImapFolderExecutor pass-through and the real connection pool, not a mock.
        assertThat(succeeded).isTrue();
        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(2);
        // A transient blip that recovered must not leave a user-visible last_error.
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getLastError()).isNull();
        // Exactly one failed attempt followed by one successful retry.
        verify(messageDownloader, times(2)).syncNewMessages(any());
    }

    private void deliver(String subject, String body) {
        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", subject, body,
                greenMail.getImap().getServerSetup()));
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to delete test path " + item, e);
                }
            });
        }
    }
}
