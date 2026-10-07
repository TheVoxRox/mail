package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
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
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * An honest catch-up larger than the open-folder budget (IMAP/SMTP audit B1-14)
 * over a real IMAP server. The budget is an estimate, so what a value set too
 * low costs is the question, and the answer is folder opens, not the folder.
 * The refusal closes the connection, which the sync takes for a lost one: the
 * batches stored so far stay, the newest first, and the next attempt opens the
 * folder again under a budget of its own and brings down the rest as holes in
 * the mirrored window. What a pass's attempts leave goes to the next pass.
 * <p>
 * That holds while the budget holds what a pass spends on the window it already
 * mirrors, plus one batch: below that every attempt is refused at the same
 * point. GreenMail advertises no CONDSTORE, so each pass here reads the flags
 * and the UIDs of every mirrored message through the folder, the costliest
 * honest pass there is. The budget, window and batch are small so the test runs
 * in seconds; the ratios are the point. The budget was 1 MB until 1.61, when
 * the charge for an ordinary message about doubled (B1-14): at 1 MB every
 * attempt then stopped at the same point, the floor above.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The only sync runs are the explicit ones below.
        "mail.client.sync.initial-delay=PT1H", "mail.client.sync.window-size=50", "mail.client.sync.batch-size=50",
        "mail.client.imap.open-folder-budget=1664KB"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class OpenFolderBudgetGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "OpenFolderBudgetGreenMailIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "budget-it@greenmail.local";
    private static final String LOGIN = "budget-it-user";
    private static final String PASSWORD = "budget-it-password";
    private static final String INBOX = "INBOX";

    static {
        try {
            // Before the extension below opens GreenMail's TLS listener: the backend
            // connects to it over TLS only (audit B1-4).
            TestTls.install();
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
            new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)).withPerMethodLifecycle(false);

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
     * New messages in the catch-up, several times what the budget holds at once.
     */
    private static final int CAUGHT_UP = 400;

    @Test
    @DisplayName("A catch-up past the open-folder budget comes down over several passes instead of blocking the folder")
    void aCatchUpPastTheBudgetComesDownOverPasses() {
        GreenMailUser user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        MailServerSettings server = new MailServerSettings("127.0.0.1", greenMail.getImaps().getPort(), true);
        accountService.createAccount(
                new AccountCreateRequest("GreenMail Budget IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
        AccountEntity account = accountRepository.findByEmail(EMAIL).orElseThrow();
        for (int i = 0; i < 5; i++) {
            deliver(user, "Before " + i);
        }
        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();
        for (int i = 0; i < CAUGHT_UP; i++) {
            deliver(user, "Caught up " + i);
        }

        long logMark = logLength();
        List<String> passes = new ArrayList<>();
        boolean clean = false;
        while (!clean && passes.size() < 5) {
            clean = mailSyncService.performFullSyncCycle(account, INBOX);
            passes.add((clean ? "clean, " : "failed, ")
                    + messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX));
        }

        assertThat(refusalsSince(logMark)).as("folder opens the budget cut short").isGreaterThanOrEqualTo(2);
        assertThat(passes.getLast()).as("passes: %s", passes).isEqualTo("clean, " + (5 + CAUGHT_UP));
    }

    private static Path log() {
        return DATA_DIR.resolve("logs").resolve("mail.log");
    }

    private static long logLength() {
        try {
            return Files.size(log());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static long refusalsSince(long mark) {
        try {
            byte[] bytes = Files.readAllBytes(log());
            String since = new String(bytes, (int) mark, bytes.length - (int) mark,
                    java.nio.charset.StandardCharsets.UTF_8);
            return since.lines().filter(line -> line.contains("passed its budget of 1703936 bytes")).count();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void deliver(GreenMailUser user, String subject) {
        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", subject, "body of " + subject,
                greenMail.getImaps().getServerSetup()));
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
