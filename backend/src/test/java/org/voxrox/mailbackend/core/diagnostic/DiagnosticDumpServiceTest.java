package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.core.init.HandshakeResponse;
import org.voxrox.mailbackend.core.init.HandshakeService;
import org.voxrox.mailbackend.core.init.StartupTimingService;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.AccountLastErrorJson;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.feature.mail.service.FolderListCache;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;

import module java.base;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DiagnosticDumpServiceTest {

    private static final String API_KEY = "canary-api-key-4417-abcdefghijklmnopqrstuvwxyz";

    @Mock
    AccountRepository accountRepository;

    @Mock
    FolderSyncStateRepository folderSyncStateRepository;

    @Mock
    MessageRepository messageRepository;

    @Mock
    ImapConnectionManager imapConnectionManager;

    @Mock
    OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;

    @Mock
    HandshakeService handshakeService;

    @Mock
    PlatformTransactionManager transactionManager;

    @TempDir
    Path dataDir;

    private final FolderListCache folderListCache = new FolderListCache();

    @BeforeEach
    void handshake() {
        lenient().when(handshakeService.getHandshake())
                .thenReturn(new HandshakeResponse("mail", "9.8.7-test", "1.0.0", "0.0.1", "1"));
        lenient().when(handshakeService.getOrCreateApiKey()).thenReturn(API_KEY);
        lenient().when(imapConnectionManager.getPoolStats()).thenReturn(new ImapConnectionManager.PoolStats(0, 0));
    }

    @Test
    @DisplayName("Diagnostic dump returns a ZIP with sanitized support data")
    void createDumpReturnsSanitizedZip() throws Exception {
        AccountEntity account = account();
        FolderSyncStateEntity folderState = new FolderSyncStateEntity(account, "INBOX", FolderRole.INBOX);
        folderState.setLastKnownUid(42L);
        folderState.setUidValidity(77L);

        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of(folderState));
        when(messageRepository.count()).thenReturn(12L);
        when(messageRepository.countByAccountIdAndFolderName(1L, "INBOX")).thenReturn(12L);
        when(imapConnectionManager.getPoolStats()).thenReturn(new ImapConnectionManager.PoolStats(1, 2));
        when(oauth2TokenServiceRegistry.totalCachedTokens()).thenReturn(1);
        ClientBootDiagnosticsService clientBootDiagnosticsService = new ClientBootDiagnosticsService();
        clientBootDiagnosticsService.update(new ClientBootDiagnosticsRequest("2026-05-07T18:00:00Z", "ready", "fast",
                Map.of("appReady", 1234L, "apiKey", 999L), "Playwright", "cs", "/settings/about?secret=1", "0.1.0"));
        StartupTimingService startupTimingService = new StartupTimingService();
        long startupPhaseStarted = startupTimingService.start();
        startupTimingService.record("test.startup-phase", startupPhaseStarted);

        DiagnosticDumpService service = service(new StorageProperties("target/test-data"),
                new MockEnvironment().withProperty("server.port", "0").withProperty("local.server.port", "61234"),
                clientBootDiagnosticsService, startupTimingService);

        Map<String, String> entries = unzip(service.createDump());

        assertThat(entries.keySet()).contains("summary.json", "accounts.json", "folder-sync-states.json",
                "message-counts.json", "runtime.json", "client-boot.json", "startup-timings.json", "threads.txt",
                "manifest.json");
        assertThat(entries.get("summary.json")).contains("\"appName\" : \"mail\"")
                .contains("\"appVersion\" : \"9.8.7-test\"").contains("\"dbSchemaVersion\" : \"1\"")
                .contains("\"messages\" : 12").contains("\"activeConnections\" : 1")
                .contains("\"oauthCachedTokens\" : 1");
        assertThat(entries.get("accounts.json")).contains("\"maskedEmail\" : \"t***r@example.com\"")
                .contains("\"authType\" : \"PASSWORD\"").contains("\"host\" : \"imap.example.com\"")
                .doesNotContain("tester@example.com").doesNotContain("encrypted-secret").doesNotContain("plain-secret");
        assertThat(entries.get("message-counts.json")).contains("\"messages\" : 12");
        assertThat(entries.get("client-boot.json")).contains("\"phase\" : \"ready\"").contains("\"appReady\" : 1234")
                .contains("\"route\" : \"/settings/about\"").contains("\"clientVersion\" : \"0.1.0\"")
                .doesNotContain("secret").doesNotContain("apiKey");
        assertThat(entries.get("startup-timings.json")).contains("\"phase\" : \"test.startup-phase\"")
                .contains("\"durationMs\"");
        assertThat(entries.get("threads.txt")).contains("createDump");
        assertThat(entries.get("runtime.json")).contains("\"usableDiskBytes\"").contains("\"dbFileBytes\"");
    }

    // The data directories below sit under the real home, where the redaction
    // applies, and do not exist: the dump reads them and writes nothing there.
    @Test
    @DisplayName("Only INBOX keeps its name in the dump, and the home directory stays out")
    void createDumpKeepsUserNamedFoldersAndHomeOut() throws Exception {
        AccountEntity account = account();
        // Stored without its role, as a sync that was not told the role stores it.
        FolderSyncStateEntity inbox = new FolderSyncStateEntity(account, "INBOX", FolderRole.USER);
        FolderSyncStateEntity sent = new FolderSyncStateEntity(account, "[Gmail]/Sent Mail", FolderRole.SENT);
        FolderSyncStateEntity invoices = new FolderSyncStateEntity(account, "Invoices Jane Doe", FolderRole.USER);
        FolderSyncStateEntity divorce = new FolderSyncStateEntity(account, "Lawyer/Divorce", FolderRole.USER);
        // Named by the user, but role detection reads "Newsletter" in the name as a
        // role.
        FolderSyncStateEntity novak = new FolderSyncStateEntity(account, "Newsletter Dr Novak", FolderRole.NEWSLETTERS);

        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of(invoices, inbox, divorce, novak, sent));
        when(messageRepository.countByAccountIdAndFolderName(1L, "Invoices Jane Doe")).thenReturn(3L);

        String home = System.getProperty("user.home");
        DiagnosticDumpService service = service(
                new StorageProperties(Path.of(home, "AppData", "Local", "VoxRox", "Mail.dump-test").toString()),
                new MockEnvironment(), new ClientBootDiagnosticsService(), new StartupTimingService());

        Map<String, String> entries = unzip(service.createDump());

        // Numbered in account-and-name order, the same in both files that list folders.
        for (String file : List.of("folder-sync-states.json", "message-counts.json")) {
            assertThat(entries.get(file)).contains("\"folderName\" : \"INBOX\"")
                    .contains("\"folderName\" : \"folder-1\"").contains("\"folderName\" : \"folder-2\"")
                    .contains("\"folderName\" : \"folder-3\"").contains("\"folderName\" : \"folder-4\"")
                    .doesNotContain("Invoices").doesNotContain("Jane").doesNotContain("Divorce").doesNotContain("Novak")
                    .doesNotContain("Sent Mail");
        }
        // Which system folder a pseudonym stands for is in its role.
        assertThat(entries.get("folder-sync-states.json"))
                .containsPattern("\"folderName\" : \"folder-3\",\\s*\"role\" : \"NEWSLETTERS\"")
                .containsPattern("\"folderName\" : \"folder-4\",\\s*\"role\" : \"SENT\"");
        assertThat(entries.get("message-counts.json"))
                .containsPattern("\"folderName\" : \"folder-1\",\\s*\"messages\" : 3");
        assertThat(entries.get("runtime.json"))
                .contains("\"dataDir\" : \"~" + File.separator.replace("\\", "\\\\") + "AppData")
                .doesNotContain(home.replace("\\", "\\\\"));
    }

    @Test
    @DisplayName("A home directory that already ends with a separator is still redacted")
    void createDumpRedactsAHomeThatEndsWithASeparator() throws Exception {
        AccountEntity account = account();
        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of());

        String home = System.getProperty("user.home");
        String dataPath = Path.of(home, "AppData", "Local", "VoxRox", "Mail.dump-test").toString();
        // -Duser.home, or an environment that sets HOME that way. The prefix check
        // wants a separator after the home, and one that is already there left the
        // rest starting with a directory name -- so the path came back whole, with
        // the account name in it and nothing saying the redaction had been skipped.
        System.setProperty("user.home", home + File.separator);
        try {
            DiagnosticDumpService service = service(new StorageProperties(dataPath), new MockEnvironment(),
                    new ClientBootDiagnosticsService(), new StartupTimingService());

            Map<String, String> entries = unzip(service.createDump());

            assertThat(entries.get("runtime.json")).contains("\"dataDir\" : \"~")
                    .doesNotContain(home.replace("\\", "\\\\"));
        } finally {
            System.setProperty("user.home", home);
        }
    }

    @Test
    @DisplayName("The account's last error goes in by code and arguments, its folder under the folder's pseudonym")
    void lastErrorGoesInByCodeWithTheFolderPseudonymised() throws Exception {
        AccountEntity account = account();
        account.setLastErrorCode(AccountLastErrorCode.MAIL_SYNC_FOLDER_FAILED.name());
        account.setLastErrorArgs(AccountLastErrorJson
                .write(Map.of("folder", "Lawyer/Divorce", "cause", "CONNECTION_FAILED", "unnamed", "Jane Doe")));
        FolderSyncStateEntity divorce = new FolderSyncStateEntity(account, "Lawyer/Divorce", FolderRole.USER);
        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of(divorce));

        Map<String, String> entries = unzip(service(new StorageProperties(dataDir.toString()), new MockEnvironment(),
                new ClientBootDiagnosticsService(), new StartupTimingService()).createDump());

        JsonNode lastError = new ObjectMapper().readTree(entries.get("accounts.json")).get(0).get("lastError");
        assertThat(lastError.get("code").asString()).isEqualTo("MAIL_SYNC_FOLDER_FAILED");
        assertThat(lastError.get("args").get("folder").asString()).isEqualTo("folder-1");
        assertThat(lastError.get("args").get("cause").asString()).isEqualTo("CONNECTION_FAILED");
        // An argument the code does not name stays out, and so does the stored text.
        assertThat(entries.get("accounts.json")).doesNotContain("Jane Doe").doesNotContain("Divorce")
                .doesNotContain("plain-secret");
    }

    @Test
    @DisplayName("The logs go in under logs/, newest first, with names, addresses, keys and the home replaced")
    void logsGoInRedacted() throws Exception {
        AccountEntity account = account();
        account.setAccountName("Work Mailbox");
        account.setDisplayName("Jane Canary");
        FolderSyncStateEntity divorce = new FolderSyncStateEntity(account, "Lawyer/Divorce", FolderRole.USER);
        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of(divorce));
        // A folder only ever listed, never synchronised: a move target, say.
        folderListCache.put(1L, List.of(new FolderResponse("Taxes 2025", "Taxes 2025", 0, FolderRole.USER)));
        folderListCache.invalidate(1L);

        String home = System.getProperty("user.home");
        Path logs = Files.createDirectories(dataDir.resolve("logs"));
        Files.writeString(logs.resolve("mail.log"), String.join("\n",
                "2026-10-12T10:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.MailSyncService : [SYNC] Initial sync of folder Lawyer/Divorce: done",
                "2026-10-12T10:00:01.000+02:00  WARN 1 --- [mail] [main] o.v.m.GlobalExceptionHandler : [ERROR] URI: /api/v1/accounts/1/folders/Lawyer%2FDivorce/messages",
                "2026-10-12T10:00:02.000+02:00  INFO 1 --- [mail] [main] o.v.m.ImapActionService : [IMAP] Moving UID 7 from INBOX to Taxes 2025",
                "2026-10-12T10:00:03.000+02:00 ERROR 1 --- [mail] [main] o.v.m.SmtpMessageService : [SMTP] 550 <jane.novak@example.org> unknown, sender Jane Canary of Work Mailbox",
                "2026-10-12T10:00:04.000+02:00  INFO 1 --- [mail] [main] o.v.m.HandshakeService : [SECURITY] Session data written to "
                        + home + File.separator + "AppData" + File.separator + "session.json",
                "2026-10-12T10:00:05.000+02:00  INFO 1 --- [mail] [main] o.v.m.Leak : key " + API_KEY
                        + " token ya29.abcdefghijklmnopqrstuvwxyz0123",
                "\tat org.voxrox.Foo.bar(Foo.java:12)"));
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(logs.resolve("mail.log.2026-10-11.0.gz")))) {
            out.write("2026-10-11T09:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.X : [SYNC] older line\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(logs.resolve("mail.log.2026-10-10.0.gz.tmp"), "not a log the bundle takes");
        Files.writeString(logs.resolve("audit.log"),
                "2026-10-12 10:00:00.000 [main] INFO  SUCCESS action=app_started actor=system detail=appVersion=0.1.0\n");
        Files.writeString(logs.resolve("mail-frontend.log"),
                "[2026-10-12][08:00:00][mail_lib][INFO] Data root: " + home + File.separator + "AppData\n");

        Map<String, String> entries = unzip(service(new StorageProperties(dataDir.toString()), new MockEnvironment(),
                new ClientBootDiagnosticsService(), new StartupTimingService()).createDump());

        assertThat(entries.keySet())
                .contains("logs/mail.log", "logs/mail.log.2026-10-11.0", "logs/audit.log", "logs/mail-frontend.log")
                .doesNotContain("logs/mail.log.2026-10-10.0.gz.tmp");
        String mailLog = entries.get("logs/mail.log");
        assertThat(mailLog).contains("Initial sync of folder folder-1: done").contains("/folders/folder-1/messages")
                .contains("from INBOX to folder-2")
                .contains("550 <j***k@example.org> unknown, sender account-1-name of account-1")
                .contains("Session data written to ~" + File.separator + "AppData").contains("key [api-key]")
                .contains("token [token]").contains("\tat org.voxrox.Foo.bar(Foo.java:12)")
                // The line's own head stays as written, so the copy still parses.
                .contains("2026-10-12T10:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.MailSyncService : ");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey()).doesNotContain("Divorce", "Taxes", "jane.novak", "Jane",
                    "Work Mailbox", API_KEY, "ya29.abcdefghij", home);
        }
        assertThat(entries.get("logs/mail.log.2026-10-11.0")).contains("older line");
        assertThat(entries.get("logs/mail-frontend.log")).contains("Data root: ~");

        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertThat(manifest.get("logs").valueStream().map(log -> log.get("file").asString()).toList())
                .containsExactly("mail.log", "mail-frontend.log", "audit.log", "mail.log.2026-10-11.0.gz");
        assertThat(manifest.get("logs").get(0).get("status").asString()).isEqualTo("complete");
        assertThat(manifest.get("logsLeftOutBecause").isNull()).isTrue();
        assertThat(manifest.get("redactions").get("knownValues").asLong()).isPositive();
    }

    @Test
    @DisplayName("A folder name two accounts share carries both labels, and a default account name stays")
    void sharedFolderNamesAndDefaultAccountNames() throws Exception {
        AccountEntity first = account();
        // The name the app gives an account: its address's domain label.
        first.setAccountName("Example");
        AccountEntity second = account();
        second.setId(2L);
        second.setEmail("other@example.org");
        second.setAccountName("Private");
        when(accountRepository.findAllWithDetails()).thenReturn(List.of(first, second));
        when(folderSyncStateRepository.findAll())
                .thenReturn(List.of(new FolderSyncStateEntity(first, "Sent", FolderRole.SENT),
                        new FolderSyncStateEntity(second, "Sent", FolderRole.SENT)));
        Path logs = Files.createDirectories(dataDir.resolve("logs"));
        Files.writeString(logs.resolve("mail.log"),
                "2026-10-12T10:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.X : [SYNC] Example account 2 Private Sent\n");

        Map<String, String> entries = unzip(service(new StorageProperties(dataDir.toString()), new MockEnvironment(),
                new ClientBootDiagnosticsService(), new StartupTimingService()).createDump());

        assertThat(entries.get("logs/mail.log")).contains("[SYNC] Example account 2 account-2 folder-1|folder-2");
    }

    @Test
    @DisplayName("A database that cannot be read still leaves a bundle, without the logs it cannot redact")
    void unreadableDatabaseLeavesTheLogsOut() throws Exception {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new CannotCreateTransactionException("Connection is not available, request timed out"));
        Path logs = Files.createDirectories(dataDir.resolve("logs"));
        Files.writeString(logs.resolve("mail.log"), "a line naming the folder Lawyer/Divorce\n");

        Map<String, String> entries = unzip(service(new StorageProperties(dataDir.toString()), new MockEnvironment(),
                new ClientBootDiagnosticsService(), new StartupTimingService()).createDump());

        assertThat(entries.keySet()).contains("runtime.json", "threads.txt", "manifest.json")
                .doesNotContain("accounts.json", "logs/mail.log");
        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertThat(manifest.get("sections").get(0).get("status").asString())
                .isEqualTo("unreadable: CannotCreateTransactionException");
        assertThat(manifest.get("logsLeftOutBecause").asString()).contains("folder names");
        assertThat(entries.get("manifest.json")).doesNotContain("request timed out");
    }

    private DiagnosticDumpService service(StorageProperties storage, MockEnvironment environment,
            ClientBootDiagnosticsService clientBoot, StartupTimingService startupTimings) {
        return new DiagnosticDumpService(accountRepository, folderSyncStateRepository, messageRepository,
                imapConnectionManager, oauth2TokenServiceRegistry, folderListCache, handshakeService, storage,
                environment, new ObjectMapper(), new ApplicationVersion("9.8.7-test"), clientBoot, startupTimings,
                transactionManager);
    }

    private AccountEntity account() {
        AccountEntity account = new AccountEntity();
        account.setId(1L);
        account.setEmail("tester@example.com");
        account.setActive(true);
        account.setRequiresReauth(false);
        account.setImapConfig(new MailServerConfig("imap.example.com", 993, true));
        account.setSmtpConfig(new MailServerConfig("smtp.example.com", 465, true));
        account.setLastError("plain-secret should stay out of diagnostic account dump");

        MailProviderEntity provider = new MailProviderEntity();
        provider.setName("Example");
        account.setProvider(provider);

        AccountCredentialEntity credentials = new AccountCredentialEntity(account, AuthType.PASSWORD,
                "tester@example.com", "encrypted-secret");
        account.setCredentials(credentials);
        return account;
    }

    private Map<String, String> unzip(byte[] zipBytes) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }
}
