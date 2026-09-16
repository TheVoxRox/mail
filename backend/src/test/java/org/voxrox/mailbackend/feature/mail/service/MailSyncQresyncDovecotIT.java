package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.event.MailEvent;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.eclipse.angus.mail.imap.MessageVanishedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * The QRESYNC half of the sync cycle, against a server that really advertises
 * it.
 *
 * <p>
 * None of the supported presets does — Gmail advertises CONDSTORE without
 * QRESYNC and Exchange Online neither — and GreenMail, which the other sync ITs
 * run on, offers neither ENABLE, CONDSTORE nor QRESYNC. So the resynchronized
 * SELECT in {@link ImapFolderExecutor} and
 * {@link FlagSyncService#applyResyncEvents} had only mocks under them, while a
 * user with their own Dovecot runs both on every cycle. This drives them
 * through the real service graph against Dovecot in a container.
 *
 * <p>
 * The rows left in the database do not prove it on their own. When the QRESYNC
 * SELECT fails, the executor degrades to a plain open, and the CONDSTORE path
 * then finds the same deletion and the same flag change — a broken resync would
 * leave exactly the right rows behind. The spy on {@link FlagSyncService} is
 * what tells the two paths apart.
 *
 * <p>
 * Needs Docker and skips itself without it: CI runners have it, a developer
 * machine may not. The connection is plain IMAP on the container's non-TLS
 * port, so this covers the protocol exchange, not the TLS setup.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out of the test — sync runs are explicit.
        "mail.client.sync.initial-delay=PT1H"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class MailSyncQresyncDovecotIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "MailSyncQresyncDovecotIT").toAbsolutePath()
            .normalize();

    /*
     * dovecot/dovecot:2.4.5, the rootless image, pinned by digest rather than by
     * tag: a tag can be republished, and this test is evidence only for the server
     * it actually ran against. The rootless image serves IMAP without TLS on 31143.
     */
    private static final DockerImageName DOVECOT_IMAGE = DockerImageName
            .parse("dovecot/dovecot@sha256:c807be4fb5a97d9c3a90770569d3a6c4cbdcb36742ad41f90409cbd929166553");
    private static final int IMAP_PORT = 31143;

    private static final String EMAIL = "qresync-it@dovecot.test";
    private static final String LOGIN = "qresync-it";
    private static final String PASSWORD = "it-password";
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

    /*
     * The image's passdb accepts any user whose password matches USER_PASSWORD.
     * Dovecot 2.4 refuses LOGIN on a connection that is neither TLS nor local
     * (auth_allow_cleartext defaults to no), and the test reaches the container
     * through a mapped port, which is neither; the extra file lifts that for this
     * container only.
     */
    @Container
    static final GenericContainer<?> DOVECOT = new GenericContainer<>(DOVECOT_IMAGE)
            .withEnv("USER_PASSWORD", "{PLAIN}" + PASSWORD)
            .withCopyToContainer(Transferable.of("auth_allow_cleartext = yes\n"), "/etc/dovecot/conf.d/zz-it.conf")
            .withExposedPorts(IMAP_PORT).waitingFor(Wait.forLogMessage(".*starting up for.*\\n", 1));

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
    private SyncStateService syncStateService;
    @Autowired
    private MailSyncService mailSyncService;
    @MockitoSpyBean
    private FlagSyncService flagSyncService;

    @Test
    @DisplayName("The second cycle learns a deletion and a flag change from the QRESYNC SELECT alone")
    void secondCycleResynchronizesThroughTheSelect() throws Exception {
        // Otherwise everything below would quietly exercise the CONDSTORE path.
        boolean advertised = onInbox(inbox -> ImapCapabilities.probe(inbox.getStore()).hasQresync());
        assertThat(advertised).as("Dovecot advertises QRESYNC").isTrue();

        onInbox(inbox -> {
            inbox.appendMessages(new Message[]{message("First"), message("Second"), message("Third")});
            return null;
        });
        AccountEntity account = createAccount();
        Long accountId = account.getId();

        // --- Cycle 1: plain open, MODSEQ baseline stored ------------------------
        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).as(() -> "cycle 1, " + lastError(accountId))
                .isTrue();

        assertThat(messageRepository.countByAccountIdAndFolderName(accountId, INBOX)).isEqualTo(3);
        assertThat(messageRepository.countByAccountIdAndFolderNameAndSeenFalse(accountId, INBOX)).isEqualTo(3);
        verify(flagSyncService, never()).applyResyncEvents(any(), any());
        /*
         * A real MODSEQ, not merely a stored one. Dovecot reports HIGHESTMODSEQ on a
         * SELECT only once the client has enabled CONDSTORE — until then a fresh
         * mailbox does not even track modseqs — and Angus reads a missing report as -1.
         * A baseline of -1 would be sent back in the next QRESYNC SELECT.
         */
        long baseline = storedModseq(accountId);
        // PROBE (temporary): baseline assertion removed to watch cycle 2 unfixed.

        // --- Another client reads the first message and expunges the second -----
        long[] changed = onInbox(inbox -> {
            UIDFolder uids = (UIDFolder) inbox;
            Message read = inbox.getMessage(1);
            Message gone = inbox.getMessage(2);
            long readUid = uids.getUID(read);
            long goneUid = uids.getUID(gone);
            read.setFlag(Flags.Flag.SEEN, true);
            gone.setFlag(Flags.Flag.DELETED, true);
            return new long[]{readUid, goneUid};
        });
        long readUid = changed[0];
        long goneUid = changed[1];

        // --- Cycle 2: resynchronized SELECT --------------------------------------
        clearInvocations(flagSyncService);
        assertThat(mailSyncService.performFullSyncCycle(account, INBOX)).as(() -> "cycle 2, " + lastError(accountId))
                .isTrue();
        // PROBE (temporary): what the unfixed cycle 2 leaves behind, before the spy
        // checks.
        long modseqAfter = storedModseq(accountId);
        org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(messageRepository.findUidsByAccountAndFolder(accountId, INBOX))
                    .as("PROBE rows after cycle 2").doesNotContain(goneUid);
            soft.assertThat(messageRepository.countByAccountIdAndFolderNameAndSeenFalse(accountId, INBOX))
                    .as("PROBE unseen after cycle 2").isEqualTo(1);
            soft.assertThat(modseqAfter).as("PROBE modseq after cycle 2, baseline " + baseline).isPositive();
            soft.assertThat(true)
                    .as("PROBE applyResyncEvents calls: " + org.mockito.Mockito.mockingDetails(flagSyncService)
                            .getInvocations().stream().map(i -> i.getMethod().getName()).toList())
                    .isFalse();
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MailEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(flagSyncService).applyResyncEvents(any(), events.capture());
        List<Long> vanished = events.getValue().stream().filter(MessageVanishedEvent.class::isInstance)
                .map(MessageVanishedEvent.class::cast).flatMap(event -> Arrays.stream(event.getUIDs()).boxed())
                .toList();
        assertThat(vanished).containsExactly(goneUid);
        /*
         * Neither per-cycle command of the CONDSTORE path ran, so the rows below can
         * only have come from the SELECT. The enumeration is skipped because cycle 1
         * ran it and it is hourly from then on.
         */
        verify(flagSyncService, never()).syncMessageFlagsCondstore(any());
        verify(flagSyncService, never()).cleanupDeletedViaUidEnumeration(any());

        assertThat(messageRepository.findUidsByAccountAndFolder(accountId, INBOX)).hasSize(2).doesNotContain(goneUid)
                .contains(readUid);
        assertThat(messageRepository.countByAccountIdAndFolderNameAndSeenFalse(accountId, INBOX)).isEqualTo(1);
        assertThat(storedModseq(accountId)).isGreaterThan(baseline);
    }

    private AccountEntity createAccount() {
        MailServerSettings server = new MailServerSettings(DOVECOT.getHost(), DOVECOT.getMappedPort(IMAP_PORT), false);
        accountService.createAccount(
                new AccountCreateRequest("Dovecot IT", null, EMAIL, null, server, server, LOGIN, PASSWORD));
        return accountRepository.findByEmail(EMAIL).orElseThrow();
    }

    private String lastError(Long accountId) {
        return "last_error: " + accountRepository.findById(accountId).orElseThrow().getLastError();
    }

    private long storedModseq(Long accountId) {
        Long modseq = syncStateService.findState(accountId, INBOX).orElseThrow().getLastKnownModseq();
        assertThat(modseq).as("MODSEQ stored for %s", INBOX).isNotNull();
        return modseq;
    }

    private static MimeMessage message(String subject) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(EMAIL));
        message.setSubject(subject);
        message.setText("Body of " + subject);
        message.setHeader("Date", DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));
        message.saveChanges();
        return message;
    }

    @FunctionalInterface
    private interface InboxAction<T> {
        T apply(Folder inbox) throws Exception;
    }

    /**
     * A second mail client: its own IMAP session, outside the backend's pooled
     * connection. Closing with {@code expunge=true} makes a DELETED flag take
     * effect at once, as a real client would.
     */
    private static <T> T onInbox(InboxAction<T> action) throws Exception {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        Session session = Session.getInstance(props);
        Store store = session.getStore("imap");
        store.connect(DOVECOT.getHost(), DOVECOT.getMappedPort(IMAP_PORT), LOGIN, PASSWORD);
        try {
            Folder inbox = store.getFolder(INBOX);
            inbox.open(Folder.READ_WRITE);
            try {
                return action.apply(inbox);
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
