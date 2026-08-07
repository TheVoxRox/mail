package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * Proves the read path does not queue behind a background sync holding the
 * account's IMAP connection.
 *
 * <p>
 * The regression this guards is invisible to a unit test and to an exception
 * assertion alike: waiting on a
 * {@link java.util.concurrent.locks.ReentrantLock} raises nothing and logs
 * nothing. A read request that resolves a folder role through IMAP would simply
 * sit there for as long as the sync's folder cycle runs — tens of seconds on a
 * large mailbox — and the symptom reaching the user is a message list that
 * appears to hang with no error anywhere.
 *
 * <p>
 * The latch pins the connection lock the way a running sync does, so the
 * assertion is about the real lock, not a simulated one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.role-lookup-timeout=200ms"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class ImapReadPathLockGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "ImapReadPathLockGreenMailIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "lock-it@greenmail.local";
    private static final String LOGIN = "lock-it-user";
    private static final String PASSWORD = "lock-it-password";

    /** Comfortably longer than the 200 ms lookup timeout configured above. */
    private static final Duration LOCK_HELD_FOR = Duration.ofSeconds(3);

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
    private ImapConnectionManager imapConnectionManager;
    @Autowired
    private ImapFolderService imapFolderService;
    @Autowired
    private FolderListCache folderListCache;

    private AccountEntity account;

    @BeforeEach
    void setUpAccount() {
        greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            MailServerSettings server = new MailServerSettings("127.0.0.1", greenMail.getImap().getPort(), false);
            accountService.createAccount(
                    new AccountCreateRequest("Lock IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
        // The role lookup must actually reach IMAP, not be served from the cache.
        folderListCache.invalidate(account.getId());
    }

    @Test
    @DisplayName("A role lookup gives up instead of waiting while a sync holds the connection")
    void roleLookupSkipsWhileTheConnectionIsBusy() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        Thread holder = new Thread(() -> imapConnectionManager.executeWithLock(account.getId(), store -> {
            lockHeld.countDown();
            try {
                // Stands in for a folder cycle: the sync holds the lock across download,
                // flag sweep and cleanup, not just for a single command.
                releaseLock.await(LOCK_HELD_FOR.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }), "lock-holder");
        holder.start();

        try {
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).as("the holder thread never acquired the lock").isTrue();

            long startedAt = System.nanoTime();
            Optional<List<String>> trash = imapFolderService.findFolderNamesByRoleWithoutWaiting(account.getId(),
                    FolderRole.TRASH);
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            // Empty means "could not resolve" — distinct from "the account has no
            // trash", and the signal MailFacade degrades on.
            assertThat(trash).as("the lookup must give up, not wait for the sync").isEmpty();
            assertThat(waited).as("returned only after the lock holder finished — it waited instead of skipping")
                    .isLessThan(LOCK_HELD_FOR);
        } finally {
            releaseLock.countDown();
            holder.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    @Test
    @DisplayName("With the connection free the same lookup resolves normally over IMAP")
    void roleLookupResolvesWhenTheConnectionIsFree() {
        // Guards the obvious failure mode of the fix: a timeout so tight (or a lock
        // never released) that the read path degrades permanently and the cross-folder
        // conversation scope silently disappears.
        Optional<List<FolderResponse>> folders = imapFolderService.getFoldersWithinTimeout(account.getId(),
                Duration.ofSeconds(10));

        assertThat(folders).isPresent();
        assertThat(folders.orElseThrow()).extracting(FolderResponse::folderRef).contains("INBOX");
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
