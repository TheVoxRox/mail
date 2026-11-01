package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Opening a message whose structure the server writes with an empty parameter
 * list, the way Seznam's does, over the wire and through the services opening a
 * message goes through. Angus rejects such a response and skips it, so the read
 * of the body failed with "Unable to load BODYSTRUCTURE" and the message could
 * not be opened at all (seen 2026-10-31); {@link BoundedImapProtocol} now reads
 * the response again with NIL in the list's place. {@link HostileImapServer}
 * answers the structure and the body section as that server did, which
 * GreenMail, writing every structure itself, cannot be made to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // No sync runs; the test opens one message.
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.read-timeout=3s",
        "mail.client.imap.connection-timeout=3s", "mail.client.retry.initial-delay=100ms",
        "mail.client.retry.max-delay=300ms",
        // A context of its own, so the data dir above is the one in use.
        "mail.test-context=EmptyParameterListIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class EmptyParameterListIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "EmptyParameterListIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "empty-list-it@example.test";

    private static final String HTML = "<html><body><p>Invoice attached</p></body></html>";

    /**
     * The structure seen from Seznam: a text/html part whose disposition has an
     * empty parameter list, and an attachment beside it.
     */
    private static final String STRUCTURE = "* 1 FETCH (BODYSTRUCTURE ((\"text\" \"html\" (\"charset\" \"utf-8\")"
            + " NIL NIL \"7bit\" " + HTML.length() + " 1 NIL (\"inline\" ()) NIL NIL)(\"application\" \"pdf\""
            + " (\"name\" \"invoice.pdf\") NIL \"Invoice\" \"base64\" 444200 NIL (\"attachment\" (\"filename\""
            + " \"invoice.pdf\")) NIL NIL) \"mixed\" (\"boundary\" \"b1\") NIL NIL NIL))";

    private static final HostileImapServer SERVER;

    static {
        try {
            // Before the server starts: it presents the test certificate, and the
            // backend trusts only that one.
            TestTls.install();
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            System.setProperty("app.data-dir", DATA_DIR.toString());
            System.setProperty("logging.file.name", DATA_DIR.resolve("logs").resolve("mail.log").toString());
            System.setProperty("spring.security.oauth2.client.registration.google.client-id", "dummy-client-id");
            System.setProperty("spring.security.oauth2.client.registration.google.client-secret",
                    "dummy-client-secret");
            System.setProperty("spring.security.oauth2.client.registration.microsoft.client-id", "dummy-client-id");
            SERVER = new HostileImapServer();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void tearDown() throws Exception {
        SERVER.close();
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
    private MailContentService mailContentService;

    @Test
    @DisplayName("A message whose structure has an empty parameter list opens with its body")
    void aMessageWithAnEmptyParameterListOpens() {
        SERVER.answerOpenWith("* 1 EXISTS");
        SERVER.listUids(1);
        SERVER.answerFetchOf("BODYSTRUCTURE", STRUCTURE);
        SERVER.answerFetchOf("[1]", "* 1 FETCH (BODY[1]<0> {" + HTML.length() + "}\r\n" + HTML + ")");

        String content = mailContentService.getOrFetchMessageContent(storedMessage().getId());

        assertThat(content).contains("Invoice attached");
    }

    /** The row the sync would have stored for UID 1 in INBOX. */
    private MessageEntity storedMessage() {
        AccountEntity account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            MailServerSettings server = new MailServerSettings("127.0.0.1", SERVER.port(), true);
            accountService.createAccount(
                    new AccountCreateRequest("Empty list IT", null, EMAIL, null, server, server, "user", "password"));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
        MessageEntity message = new MessageEntity();
        message.setStableId(UUID.randomUUID().toString().replace("-", ""));
        message.setAccount(account);
        message.setFolderName("INBOX");
        message.setUid(1L);
        message.setUidValidity(1L);
        message.setReceivedAt(LocalDateTime.now());
        return messageRepository.saveAndFlush(message);
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
