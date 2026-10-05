package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;

import jakarta.mail.Folder;

import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;

/**
 * A sync against a server that answers the folder open with a size meant to
 * exhaust the heap — IMAP/SMTP audit B1-3. Angus sizes an array from such a
 * number while it is still parsing the SELECT, before any of our code sees it,
 * so the only place to refuse it is the protocol layer
 * ({@link BoundedImapProtocol}); without that the pass ends in an
 * {@link OutOfMemoryError} thrown straight out of the sync.
 * <p>
 * The numbers are chosen so that allocation can never succeed, whatever heap
 * the test JVM has: they ask for an array at the VM's length limit, which fails
 * before any memory is taken. The test therefore costs nothing when the guard
 * is missing, beyond the failure it reports.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The only sync runs are the explicit ones below.
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.read-timeout=3s",
        "mail.client.imap.connection-timeout=3s", "mail.client.retry.initial-delay=100ms",
        "mail.client.retry.max-delay=300ms",
        // A context of its own, so the data dir above is the one in use.
        "mail.test-context=HostileImapResponseIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class HostileImapResponseIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "HostileImapResponseIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "hostile-it@example.test";

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
    private MailSyncService mailSyncService;
    @Autowired
    private ImapFolderService imapFolderService;

    private AccountEntity account;

    @BeforeEach
    void setUp() {
        SERVER.answerOpenWith();
        SERVER.listUids(0);
        SERVER.padUidListingWith();
        SERVER.authenticateWith();
        account = accountRepository.findByEmail(EMAIL).orElseGet(() -> {
            MailServerSettings server = new MailServerSettings("127.0.0.1", SERVER.port(), true);
            accountService.createAccount(
                    new AccountCreateRequest("Hostile IT", null, EMAIL, null, server, server, "user", "password"));
            return accountRepository.findByEmail(EMAIL).orElseThrow();
        });
        accountRepository.clearLastError(account.getId(), LocalDateTime.now());
    }

    @Test
    @DisplayName("The test server is one the backend can sync from, so a failure below is the response's doing")
    void anOrdinaryAnswerSyncs() {
        assertThat(pass().getLastErrorCode()).isNull();
    }

    @Test
    @DisplayName("A folder open that claims more messages than an array can hold fails the pass instead of the heap")
    void anImplausibleMessageCountFailsThePass() {
        SERVER.answerOpenWith("* 2147483583 EXISTS");

        AccountEntity after = pass();

        assertRefused(after, "EXISTS 2147483583 is outside");
    }

    @Test
    @DisplayName("A folder open that reports vanished UIDs nobody asked about fails the pass instead of the heap")
    void anUnaskedForVanishedRangeFailsThePass() {
        // The UIDNEXT lifts the ceiling Angus would otherwise clamp the range to.
        SERVER.answerOpenWith("* OK [UIDNEXT 4294967295] predicted", "* VANISHED (EARLIER) 1:2147483647");

        AccountEntity after = pass();

        assertRefused(after, "VANISHED");
    }

    /**
     * B1-7. Angus grows its buffer to the size the literal declares before the
     * literal's first byte arrives, so the size is the server's and the allocation
     * happens below the response check above. The declared size here is just past
     * the bound rather than the 2 GB the audit measured: what the bound refuses is
     * the point, and against the unfixed code this still fails — there it is the
     * read timeout that ends the pass, three seconds later and with a socket error,
     * not a refusal.
     */
    @Test
    @DisplayName("A folder open that declares a literal larger than any response fails the pass instead of the heap")
    void anOversizedLiteralFailsThePass() {
        SERVER.answerOpenWith("* OK [ALERT] {" + (BoundedImapProtocol.MAX_RESPONSE_BYTES + 1) + "}");

        AccountEntity after = pass();

        assertRefused(after, "could not be read");
    }

    /**
     * The shape of B1-7 the bound cannot see. Angus decides whether to grow by
     * comparing {@code count + 16} against the room left, and for a size this close
     * to {@link Integer#MAX_VALUE} that sum overflows to a negative number, so it
     * grows nothing and reads past the buffer instead. Nothing is allocated and
     * nothing reaches {@code ByteArray.grow}; what makes it matter is that the
     * unchecked exception would otherwise leave a connection mid-response in the
     * pool, where the next command would read the rest of this one as its own
     * reply.
     */
    @Test
    @DisplayName("A literal declared at the top of the int range ends the connection, not the pass in a stuck state")
    void aLiteralAtTheTopOfTheRangeFailsThePass() {
        SERVER.answerOpenWith("* OK [ALERT] {" + Integer.MAX_VALUE + "}");

        AccountEntity after = pass();

        assertRefused(after, "could not be read");
    }

    /**
     * B1-10. Angus parses a {@code BODYSTRUCTURE} by calling itself once per level,
     * while it reads the FETCH response, and the {@link StackOverflowError} that
     * enough levels raise is an {@code Error}: no handler between the wire and the
     * scheduler catches one, so the pass ended with nothing recorded. 200,000
     * levels is about 2 MB, under the response bound, and overflows any thread
     * stack a JVM starts with. The response is dropped unparsed instead, and the
     * connection, which has read it in full, goes on; what that leaves of the
     * message it describes is {@code MailSyncGreenMailIT}'s question.
     */
    @Test
    @DisplayName("A message structure nested past any stack is dropped, and the pass goes on")
    void aStructureNestedPastTheStackIsDropped() {
        SERVER.answerOpenWith(nestedBodyStructureFetch(200_000));

        AccountEntity after = pass();

        assertThat(after.getLastErrorCode()).isNull();
    }

    /**
     * B1-10, reopened at 1.24. Angus reads a {@code BODY[...]} section raw up to
     * its {@code ]}, so a quote inside one opens no string for Angus; read as a
     * token, it hid the structure after it from the check, and Angus parsed it.
     */
    @Test
    @DisplayName("A quote inside a BODY section does not hide the structure after it from the check")
    void aQuoteInABodySectionDoesNotHideTheStructure() {
        SERVER.answerOpenWith(nestedBodyStructureFetch(200_000).replace("(UID 5 ", "(UID 5 BODY[x\"] NIL "));

        AccountEntity after = pass();

        assertThat(after.getLastErrorCode()).isNull();
    }

    /**
     * B1-10, reopened at 1.31. Angus reads a FLAGS list raw to its first {@code )},
     * so a quote in it opens no string for Angus; a scan that read it as one hid
     * the structure after it. The depth now comes from Angus's own parse, which
     * meets the structure where it is.
     */
    @Test
    @DisplayName("A quote inside a FLAGS list does not hide the structure after it")
    void aQuoteInAFlagsListDoesNotHideTheStructure() {
        SERVER.answerOpenWith(nestedBodyStructureFetch(200_000).replace("(UID 5 ", "(UID 5 FLAGS (\") "));

        AccountEntity after = pass();

        assertThat(after.getLastErrorCode()).isNull();
    }

    /**
     * B1-10, found at 1.32. Angus parses a group's members inside the group's own
     * parse, so a group starting inside a group recurses, though each address
     * closes its parentheses before the next begins; 100,000 of them are 1.7 MB.
     */
    @Test
    @DisplayName("Address groups nested past any stack are dropped, and the pass goes on")
    void addressGroupsNestedPastTheStackAreDropped() {
        SERVER.answerOpenWith("* 1 FETCH (UID 5 ENVELOPE (NIL \"s\" (" + "(NIL NIL \"g\" NIL)".repeat(100_000)
                + "(NIL NIL \"a\" \"example.com\")) NIL NIL NIL NIL NIL NIL NIL))");

        AccountEntity after = pass();

        assertThat(after.getLastErrorCode()).isNull();
    }

    /**
     * B1-8, from the 1.24 verification pass. {@code Protocol.command} collects a
     * tagged response whose tag is not its own and reads on; a budget that started
     * over at any tagged response let a server reset it every thousand lines and
     * send the 1.16 flood regardless.
     */
    @Test
    @DisplayName("Tagged lines with a foreign tag do not reset one command's budget")
    void foreignTaggedLinesDoNotResetTheBudget() {
        String[] lines = new String[250_000];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = i % 1000 == 999 ? "zz" + i + " OK not your command" : "* OK still here";
        }
        SERVER.answerOpenWith(lines);

        AccountEntity after = pass();

        assertRefused(after, "passed");
    }

    /**
     * B1-8. Each line is a response the per-response checks pass; 250,000 of them
     * are three megabytes on the wire and, with the objects Angus parses each into,
     * past one command's budget. Against the unfixed protocol nothing refuses them
     * and the pass completes, holding them all until the SELECT ends.
     */
    @Test
    @DisplayName("A folder open whose responses add up past one command's budget fails the pass instead of the heap")
    void responsesAddingUpPastTheBudgetFailThePass() {
        String[] filler = new String[250_000];
        Arrays.fill(filler, "* OK still here");
        SERVER.answerOpenWith(filler);

        AccountEntity after = pass();

        assertRefused(after, "passed");
    }

    /**
     * B1-8, the honest half. The UID listing answers once per message, so a large
     * folder's listing is as long as the hostile open above; it is read one
     * response at a time and not charged, so it lists the folder whole.
     */
    @Test
    @DisplayName("A UID listing longer than one command's budget is read whole, one response at a time")
    void aLongUidListingIsReadWhole() {
        SERVER.answerOpenWith("* 250000 EXISTS");
        SERVER.listUids(250_000);

        Set<Long> uids = imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX", Folder.READ_ONLY,
                (folder, uidFolder) -> ImapCondstoreCommands.fetchAllServerUids((IMAPFolder) folder));

        assertThat(uids).hasSize(250_000).contains(1L, 250_000L);
    }

    /**
     * B1-8, from the 1.24 verification pass. {@code IMAPFolder}'s response handler
     * records a UID-table entry for each UID a FETCH gives a message and keeps it
     * while the folder is open, so a listing handed to it grew the table by an
     * entry a line — past what the listing itself keeps, and past the folder's
     * message count, since one message can be renamed without end.
     */
    @Test
    @DisplayName("The UID listing leaves nothing behind in the folder's UID table")
    void theUidListingLeavesTheFolderTableEmpty() {
        SERVER.answerOpenWith("* 1 EXISTS");
        SERVER.listUidsOfOneMessage(100_000);

        int recorded = imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX", Folder.READ_ONLY,
                (folder, uidFolder) -> {
                    ImapCondstoreCommands.fetchAllServerUids((IMAPFolder) folder);
                    return uidTableSize((IMAPFolder) folder);
                });

        assertThat(recorded).isZero();
    }

    /**
     * B1-13. Angus spends a pass over the folder's message cache on each EXPUNGE,
     * so a short line repeated costs the sync thread time rather than memory: 0.45
     * ms each at 2,000,000 messages, measured at 1.26, and the command budget alone
     * admitted some 229,000 a command. The EXPUNGEs ride on the UID listing because
     * the folder's handler sees responses only once the folder is open.
     */
    @Test
    @DisplayName("EXPUNGEs past the selected folder's budget fail the command instead of occupying the sync")
    void anExpungeFloodIsRefused() {
        SERVER.answerOpenWith("* 2000000 EXISTS");
        String[] expunges = new String[2_000];
        Arrays.fill(expunges, "* 1 EXPUNGE");
        SERVER.padUidListingWith(expunges);
        logMark = logLength();

        assertThatThrownBy(() -> imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX",
                Folder.READ_ONLY, (folder, uidFolder) -> ImapCondstoreCommands.fetchAllServerUids((IMAPFolder) folder)))
                .isInstanceOf(RuntimeException.class);
        assertThat(logSinceMark()).contains("EXPUNGE responses since the folder was selected");
    }

    /**
     * B1-13. The budget is per selected folder, not per connection: a pooled
     * connection that selects the folder again starts over.
     */
    @Test
    @DisplayName("Each folder open starts its own EXPUNGE budget")
    void eachOpenStartsItsOwnExpungeBudget() {
        SERVER.answerOpenWith("* 2000000 EXISTS");
        String[] expunges = new String[600];
        Arrays.fill(expunges, "* 1 EXPUNGE");
        SERVER.padUidListingWith(expunges);

        for (int open = 0; open < 2; open++) {
            Set<Long> uids = imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX",
                    Folder.READ_ONLY,
                    (folder, uidFolder) -> ImapCondstoreCommands.fetchAllServerUids((IMAPFolder) folder));
            assertThat(uids).as("open %d", open).isEmpty();
        }
    }

    /**
     * B1-8, from the 1.24 verification pass. Only the listing's FETCH responses go
     * uncharged, to a caller that bounds what it keeps; anything else a server puts
     * among them reaches the folder's handlers and is charged as in any command.
     */
    @Test
    @DisplayName("Responses other than FETCH among the UID listing's are charged to its budget")
    void aListingPaddedPastTheBudgetIsRefused() {
        String[] filler = new String[250_000];
        Arrays.fill(filler, "* OK still here");
        SERVER.padUidListingWith(filler);
        logMark = logLength();

        assertThatThrownBy(() -> imapFolderService.executeInFolder(account.getId(), Lane.BACKGROUND, "INBOX",
                Folder.READ_ONLY, (folder, uidFolder) -> ImapCondstoreCommands.fetchAllServerUids((IMAPFolder) folder)))
                .isInstanceOf(RuntimeException.class);
        assertThat(logSinceMark()).contains("passed");
    }

    /**
     * B1-8. Angus collects the responses to AUTHENTICATE in a loop of its own, not
     * in {@code Protocol.command}, so a budget charged only there let a server
     * flood the sign-in. A fresh connection is forced so the pass signs in again.
     */
    @Test
    @DisplayName("A sign-in answered with responses past one command's budget fails the pass instead of the heap")
    void anAuthenticateFloodFailsThePass() {
        String[] filler = new String[250_000];
        Arrays.fill(filler, "* OK still here");
        SERVER.authenticateWith(filler);
        imapFolderService.invalidateConnection(account.getId());

        AccountEntity after = pass();

        assertRefused(after, "passed");
    }

    @Test
    @DisplayName("A refused response costs one pass: the next ordinary answer syncs again")
    void theNextOrdinaryAnswerSyncsAgain() {
        SERVER.answerOpenWith("* 2147483583 EXISTS");
        assertThat(pass().getLastErrorCode()).isNotNull();

        SERVER.answerOpenWith();

        assertThat(pass().getLastErrorCode()).isNull();
    }

    /**
     * One whole-account pass, the one the scheduler runs, called on the service
     * itself rather than through its {@code @Async} proxy so it finishes — or
     * throws — before this returns.
     */
    private AccountEntity pass() {
        logMark = logLength();
        MailSyncService direct = AopTestUtils.getUltimateTargetObject(mailSyncService);
        direct.syncAllFolders(accountRepository.findById(account.getId()).orElseThrow(), SyncTrigger.SCHEDULED);
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    /**
     * A pass a refusal ended. The account records the refused-response cause, which
     * the user is told in their language; which response was refused, and why, is
     * in the log the pass wrote, where the text of a failure goes (API surface
     * audit §3) — so the reason is looked for in what this pass added to it.
     */
    private void assertRefused(AccountEntity after, String reason) {
        assertThat(after.getLastErrorCode()).isNotNull();
        assertThat(after.getLastErrorArgs()).contains("REFUSED_RESPONSE");
        assertThat(after.getLastError()).doesNotContain(reason);
        assertThat(logSinceMark()).contains(reason);
    }

    private long logMark;

    private static Path log() {
        return DATA_DIR.resolve("logs").resolve("mail.log");
    }

    private static long logLength() {
        try {
            return Files.exists(log()) ? Files.size(log()) : 0;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private String logSinceMark() {
        try {
            byte[] bytes = Files.readAllBytes(log());
            return new String(bytes, (int) logMark, bytes.length - (int) logMark,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * The entries in the folder's UID table, a protected field Angus fills from the
     * FETCH responses its handler is given.
     */
    private static int uidTableSize(IMAPFolder folder) {
        try {
            java.lang.reflect.Field field = IMAPFolder.class.getDeclaredField("uidTable");
            field.setAccessible(true);
            java.util.Map<?, ?> table = (java.util.Map<?, ?>) field.get(folder);
            return table == null ? 0 : table.size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Angus no longer has IMAPFolder.uidTable", e);
        }
    }

    /**
     * An unsolicited FETCH whose structure is {@code levels} multiparts deep around
     * one text part — each level one pair of parentheses and a subtype.
     */
    static String nestedBodyStructureFetch(int levels) {
        StringBuilder line = new StringBuilder("* 1 FETCH (UID 5 BODYSTRUCTURE ");
        line.repeat("(", levels);
        line.append("\"text\" \"plain\" NIL NIL NIL \"7bit\" 1 1)");
        line.repeat(" \"mixed\")", levels - 1);
        return line.append(')').toString();
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
