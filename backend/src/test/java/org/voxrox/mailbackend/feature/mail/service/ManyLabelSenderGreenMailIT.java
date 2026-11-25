package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

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
 * A received message whose sender has thousands of domain labels, over a live
 * IMAP connection (GreenMail). The sync harvests the sender as a correspondent
 * through the address rule, inside the batch's transaction, and the rule's regex
 * recursed once per label: the stack overflowed, the batch rolled back, and the
 * folder stopped syncing on every pass with nothing on the account to say why.
 * Found by the pass of API surface audit 1.34 (A8); the rule now refuses an
 * address longer than 254 characters before it matches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.client.sync.initial-delay=PT1H", "mail.test-context=ManyLabelSenderGreenMailIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class ManyLabelSenderGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "ManyLabelSenderGreenMailIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "labels-it@greenmail.local";
    private static final String LOGIN = "labels-user";
    private static final String PASSWORD = "labels-password";
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

    @Test
    @DisplayName("A sender of thousands of domain labels does not stop the folder's sync")
    void manyLabelSenderDoesNotStopTheSync() throws Exception {
        GreenMailUser user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        MailServerSettings server = new MailServerSettings("127.0.0.1", greenMail.getImaps().getPort(), true);
        accountService.createAccount(
                new AccountCreateRequest("Labels IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
        AccountEntity account = accountRepository.findByEmail(EMAIL).orElseThrow();

        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", "Before", "one",
                greenMail.getImaps().getServerSetup()));
        MimeMessage manyLabels = new MimeMessage(Session.getInstance(new Properties()));
        manyLabels.setHeader("From", "Sender <jan@" + "a.".repeat(10_000) + "cz>");
        manyLabels.setRecipients(Message.RecipientType.TO, EMAIL);
        manyLabels.setSubject("Many labels");
        manyLabels.setText("two");
        manyLabels.saveChanges();
        user.deliver(manyLabels);
        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", "After", "three",
                greenMail.getImaps().getServerSetup()));

        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();

        assertThat(messageRepository.countByAccountIdAndFolderName(account.getId(), INBOX)).isEqualTo(3);
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getLastErrorCode()).isNull();
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
