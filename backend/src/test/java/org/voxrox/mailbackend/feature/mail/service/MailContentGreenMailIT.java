package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

import jakarta.activation.DataHandler;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.HtmlSanitizer;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * Hostile-content integration test of the body fetch path over a live
 * (in-process) IMAP server — the dynamic complement to the static Boundary 1
 * audit ({@code docs/IMAP_SMTP_AUDIT.md} §4, finding B1-1).
 *
 * <p>
 * The unit tests around {@code MimePartExtractor} / {@code MailContentService}
 * prove the bounded-read logic against mocked parts; what they cannot show is
 * the wire-level guarantee the fix actually depends on: that an oversized body
 * served by a real IMAP server ends as a placeholder + persisted
 * {@code body_oversize} flag (and is never re-fetched), and that the charset /
 * alternative-selection behavior survives real MIME serialization, transfer
 * encoding, BODYSTRUCTURE and Angus partial fetch. Each scenario delivers a
 * crafted message to GreenMail, syncs through the real service graph and opens
 * the body through {@link MailContentService}.
 *
 * <p>
 * <strong>GreenMail 2.1.9 fidelity limits</strong> (verified by a raw-protocol
 * probe, 2026-07-10): (a) {@code FETCH n (BODY[TEXT])} — the section Angus uses
 * for the top-level body of a <em>single-part</em> message — returns an empty
 * literal {@code {0}} even though BODYSTRUCTURE reports the correct size, so
 * every body fixture here is multipart-shaped (numeric sections like
 * {@code BODY[1]} serve correctly, incl. multi-MiB partial fetches); (b) a
 * partial-fetch response omits the RFC 3501 origin-octet marker ({@code BODY[2]
 * {…}} instead of {@code BODY[2]<0> {…}}), which breaks the client-side read of
 * the base64 image part — so cid-inlining stays covered at the unit level
 * ({@code MimePartExtractorTest}) and this IT asserts only the HTML selection.
 * The single-part body shape itself is likewise unit-covered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out of the test — sync runs are explicit.
        "mail.client.sync.initial-delay=PT1H"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class MailContentGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "MailContentGreenMailIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "content-it@greenmail.local";
    private static final String LOGIN = "content-user";
    private static final String PASSWORD = "content-password";
    private static final String INBOX = "INBOX";

    /** Just over the extractor's MAX_BODY_BYTES (8 MiB) so the cap trips. */
    private static final int OVERSIZE_BODY_LENGTH = 8 * 1024 * 1024 + 4096;

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
    @Autowired
    private MailContentService mailContentService;
    @Autowired
    private MessageSource messageSource;

    private GreenMailUser user;
    private AccountEntity account;

    @BeforeEach
    void setUpAccount() {
        user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            int imapPort = greenMail.getImap().getPort();
            MailServerSettings server = new MailServerSettings("127.0.0.1", imapPort, false);
            accountService.createAccount(
                    new AccountCreateRequest("Content IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
    }

    @Test
    @DisplayName("B1-1: an oversized body opens as the placeholder, persists the flag and is never re-fetched")
    void oversizedBodyServesPlaceholderAndNeverRefetches() throws Exception {
        String subject = "oversized-plain";
        MimeBodyPart body = new MimeBodyPart();
        body.setText("x".repeat(OVERSIZE_BODY_LENGTH), "utf-8");
        MimeMessage message = emptyMessage(subject);
        message.setContent(singlePartMixed(body));
        deliverAndSync(message);
        MessageEntity entity = messageBySubject(subject);

        String content = mailContentService.getOrFetchMessageContent(entity.getId());

        // The placeholder is served through the standard plain-text wrapper…
        assertThat(content).isEqualTo(expectedPlaceholder());
        // …and nothing of the hostile body is ever persisted, only the flag.
        MessageEntity reloaded = messageRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.isBodyOversize()).isTrue();
        assertThat(reloaded.getContent()).isNull();

        // Deleting the message server-side proves the short-circuit: a second
        // open must still answer from the flag alone — any IMAP fetch would
        // now fail with "no longer exists on the server".
        deleteOnServer(subject);
        assertThat(mailContentService.getOrFetchMessageContent(entity.getId())).isEqualTo(expectedPlaceholder());
    }

    @Test
    @DisplayName("B1-1: an oversized HTML alternative falls back to the plain sibling over the wire")
    void oversizedHtmlAlternativeFallsBackToPlain() throws Exception {
        String subject = "oversized-html-first";
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>" + "x".repeat(OVERSIZE_BODY_LENGTH) + "</p>", "text/html; charset=utf-8");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("plain survives", "utf-8");

        // Hostile/sloppy sender order: html first, plain second (non-RFC).
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(html);
        alternative.addBodyPart(plain);
        MimeMessage message = emptyMessage(subject);
        message.setContent(alternative);
        deliverAndSync(message);
        MessageEntity entity = messageBySubject(subject);

        String content = mailContentService.getOrFetchMessageContent(entity.getId());

        assertThat(content).contains("plain survives");
        MessageEntity reloaded = messageRepository.findById(entity.getId()).orElseThrow();
        assertThat(reloaded.isBodyOversize()).isFalse();
        assertThat(reloaded.getContent()).contains("plain survives");
    }

    @Test
    @DisplayName("A multipart/related alternative (Apple Mail layout) renders as HTML with the cid image inlined")
    void relatedAlternativeRendersHtmlWithInlineImage() throws Exception {
        String subject = "related-alternative";
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("plain body", "utf-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<p>rich body</p><img src=\"cid:logo\">", "text/html; charset=utf-8");
        MimeBodyPart imagePart = new MimeBodyPart();
        imagePart.setDataHandler(
                new DataHandler(new ByteArrayDataSource(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47}, "image/png")));
        imagePart.setHeader("Content-ID", "<logo>");
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(htmlPart);
        related.addBodyPart(imagePart);
        MimeBodyPart relatedWrapper = new MimeBodyPart();
        relatedWrapper.setContent(related);

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(relatedWrapper);
        MimeMessage message = emptyMessage(subject);
        message.setContent(alternative);
        deliverAndSync(message);

        String content = mailContentService.getOrFetchMessageContent(messageBySubject(subject).getId());

        // The rich (related) alternative wins over the plain sibling. The cid
        // image itself cannot be asserted here — GreenMail's partial-fetch
        // response bug (class javadoc) blanks the base64 part client-side;
        // inlining is covered by MimePartExtractorTest.
        assertThat(content).contains("rich body");
        assertThat(content).doesNotContain("plain body");
    }

    @Test
    @DisplayName("A declared charset survives real transfer encoding (ISO-8859-2 body decodes correctly)")
    void declaredCharsetDecodedOverTheWire() throws Exception {
        String subject = "declared-charset";
        String czech = "Příliš žluťoučký kůň";
        MimeBodyPart body = new MimeBodyPart();
        body.setText(czech, "iso-8859-2");
        MimeMessage message = emptyMessage(subject);
        message.setContent(singlePartMixed(body));
        deliverAndSync(message);

        String content = mailContentService.getOrFetchMessageContent(messageBySubject(subject).getId());

        assertThat(content).contains(czech);
    }

    @Test
    @DisplayName("An unknown declared charset degrades to a UTF-8 decode instead of failing the message")
    void unknownCharsetFallsBackToUtf8() throws Exception {
        String subject = "unknown-charset";
        String raw = "From: sender@example.com\r\n" + "To: " + EMAIL + "\r\n" + "Subject: " + subject + "\r\n"
                + "MIME-Version: 1.0\r\n" + "Content-Type: multipart/mixed; boundary=BB\r\n" + "\r\n" + "--BB\r\n"
                + "Content-Type: text/plain; charset=x-no-such-charset\r\n" + "Content-Transfer-Encoding: 8bit\r\n"
                + "\r\n" + "héllo from a strange charset\r\n" + "--BB--\r\n";
        MimeMessage message = new MimeMessage((Session) null,
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        deliverAndSync(message);

        String content = mailContentService.getOrFetchMessageContent(messageBySubject(subject).getId());

        assertThat(content).contains("héllo from a strange charset");
    }

    // ---- helpers --------------------------------------------------------

    private MimeMessage emptyMessage(String subject) throws Exception {
        MimeMessage message = new MimeMessage((Session) null);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(EMAIL));
        message.setSubject(subject);
        return message;
    }

    /**
     * Wraps a lone body part in multipart/mixed so GreenMail serves it under a
     * numeric section ({@code BODY[1]}) — see the class javadoc on the broken
     * {@code BODY[TEXT]} single-part path.
     */
    private static MimeMultipart singlePartMixed(MimeBodyPart body) throws Exception {
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(body);
        return mixed;
    }

    private void deliverAndSync(MimeMessage message) throws Exception {
        message.saveChanges();
        user.deliver(message);
        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).isTrue();
    }

    /** Single-account test DB — the unique subject identifies the row. */
    private MessageEntity messageBySubject(String subject) {
        return messageRepository.findAll().stream().filter(m -> subject.equals(m.getSubject())).findFirst()
                .orElseThrow(() -> new AssertionError("Message not synced: " + subject));
    }

    private String expectedPlaceholder() {
        return HtmlSanitizer.escapePlainText(
                messageSource.getMessage("mail.message.bodyTooLarge", new Object[0], LocaleContextHolder.getLocale()));
    }

    /**
     * Simulates another client permanently deleting the message: a separate IMAP
     * session sets DELETED and expunges on close.
     */
    private void deleteOnServer(String subject) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect("127.0.0.1", greenMail.getImap().getPort(), LOGIN, PASSWORD);
        try {
            Folder inbox = store.getFolder(INBOX);
            inbox.open(Folder.READ_WRITE);
            try {
                for (Message message : inbox.getMessages()) {
                    if (subject.equals(message.getSubject())) {
                        message.setFlag(Flags.Flag.DELETED, true);
                    }
                }
            } finally {
                inbox.close(true);
            }
        } finally {
            store.close();
        }
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
