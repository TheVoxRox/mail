package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
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

    @Test
    @DisplayName("Folders go in under their own names, the same in both files that list them, next to their role")
    void foldersGoInUnderTheirNames() throws Exception {
        AccountEntity account = account();
        FolderSyncStateEntity inbox = new FolderSyncStateEntity(account, "INBOX", FolderRole.INBOX);
        FolderSyncStateEntity sent = new FolderSyncStateEntity(account, "[Gmail]/Sent Mail", FolderRole.SENT);
        FolderSyncStateEntity divorce = new FolderSyncStateEntity(account, "Lawyer/Divorce", FolderRole.USER);
        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of(inbox, sent, divorce));
        when(messageRepository.countByAccountIdAndFolderName(eq(1L), anyString()))
                .thenAnswer(call -> "Lawyer/Divorce".equals(call.getArgument(1)) ? 3L : 0L);

        Map<String, String> entries = unzip(service(new StorageProperties(dataDir.toString()), new MockEnvironment(),
                new ClientBootDiagnosticsService(), new StartupTimingService()).createDump());

        for (String file : List.of("folder-sync-states.json", "message-counts.json")) {
            assertThat(entries.get(file)).contains("\"folderName\" : \"INBOX\"")
                    .contains("\"folderName\" : \"[Gmail]/Sent Mail\"").contains("\"folderName\" : \"Lawyer/Divorce\"");
        }
        assertThat(entries.get("folder-sync-states.json"))
                .containsPattern("\"folderName\" : \"\\[Gmail]/Sent Mail\",\\s*\"role\" : \"SENT\"");
        assertThat(entries.get("message-counts.json"))
                .containsPattern("\"folderName\" : \"Lawyer/Divorce\",\\s*\"messages\" : 3");
    }

    // The data directories below sit under the real home, where the redaction
    // applies, and do not exist: the dump reads them and writes nothing there.
    @Test
    @DisplayName("A data path under the home directory goes into runtime.json with ~ for the home")
    void runtimePathsStartWithTheHome() throws Exception {
        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account()));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of());

        String home = System.getProperty("user.home");
        DiagnosticDumpService service = service(
                new StorageProperties(Path.of(home, "AppData", "Local", "VoxRox", "Mail.dump-test").toString()),
                new MockEnvironment(), new ClientBootDiagnosticsService(), new StartupTimingService());

        Map<String, String> entries = unzip(service.createDump());

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
    @DisplayName("The account's last error goes in by code and the arguments the code names")
    void lastErrorGoesInByCodeAndArguments() throws Exception {
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
        assertThat(lastError.get("args").get("folder").asString()).isEqualTo("Lawyer/Divorce");
        assertThat(lastError.get("args").get("cause").asString()).isEqualTo("CONNECTION_FAILED");
        // An argument the code does not name stays out, and so does the stored text.
        assertThat(entries.get("accounts.json")).doesNotContain("Jane Doe").doesNotContain("plain-secret");
    }

    @Test
    @DisplayName("The logs go in under logs/ as on disk, newest first, with only addresses, tokens and the key replaced")
    void logsGoInAsOnDisk() throws Exception {
        when(accountRepository.findAllWithDetails()).thenReturn(List.of(account()));
        when(folderSyncStateRepository.findAll()).thenReturn(List.of());

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
        // What the log on disk holds goes in as it is: folder names, account names,
        // request paths and the home directory.
        assertThat(mailLog).contains("Initial sync of folder Lawyer/Divorce: done")
                .contains("/folders/Lawyer%2FDivorce/messages").contains("from INBOX to Taxes 2025")
                .contains("sender Jane Canary of Work Mailbox")
                .contains("Session data written to " + home + File.separator + "AppData")
                .contains("\tat org.voxrox.Foo.bar(Foo.java:12)")
                .contains("2026-10-12T10:00:00.000+02:00  INFO 1 --- [mail] [main] o.v.m.MailSyncService : ");
        // What no log may hold does not: the server's address, the key, the token.
        assertThat(mailLog).contains("550 <j***k@example.org> unknown").contains("key [api-key]")
                .contains("token [token]");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey()).doesNotContain("jane.novak", API_KEY, "ya29.abcdefghij");
        }
        assertThat(entries.get("logs/mail.log.2026-10-11.0")).contains("older line");
        assertThat(entries.get("logs/mail-frontend.log")).contains("Data root: " + home);

        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertThat(manifest.get("logs").valueStream().map(log -> log.get("file").asString()).toList())
                .containsExactly("mail.log", "mail-frontend.log", "audit.log", "mail.log.2026-10-11.0.gz");
        assertThat(manifest.get("logs").get(0).get("status").asString()).isEqualTo("complete");
        assertThat(manifest.get("redactions").get("addresses").asLong()).isEqualTo(1);
        assertThat(manifest.get("redactions").get("apiKeys").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("A database that cannot be read still leaves a bundle with the runtime, the threads and the logs")
    void unreadableDatabaseStillLeavesTheLogs() throws Exception {
        when(transactionManager.getTransaction(any()))
                .thenThrow(new CannotCreateTransactionException("Connection is not available, request timed out"));
        Path logs = Files.createDirectories(dataDir.resolve("logs"));
        Files.writeString(logs.resolve("mail.log"), "a line naming the folder Lawyer/Divorce\n");

        Map<String, String> entries = unzip(service(new StorageProperties(dataDir.toString()), new MockEnvironment(),
                new ClientBootDiagnosticsService(), new StartupTimingService()).createDump());

        assertThat(entries.keySet()).contains("runtime.json", "threads.txt", "manifest.json", "logs/mail.log")
                .doesNotContain("accounts.json");
        assertThat(entries.get("logs/mail.log")).contains("a line naming the folder Lawyer/Divorce");
        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertThat(manifest.get("sections").get(0).get("status").asString())
                .isEqualTo("unreadable: CannotCreateTransactionException");
    }

    private DiagnosticDumpService service(StorageProperties storage, MockEnvironment environment,
            ClientBootDiagnosticsService clientBoot, StartupTimingService startupTimings) {
        return new DiagnosticDumpService(accountRepository, folderSyncStateRepository, messageRepository,
                imapConnectionManager, oauth2TokenServiceRegistry, handshakeService, storage, environment,
                new ObjectMapper(), new ApplicationVersion("9.8.7-test"), clientBoot, startupTimings,
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
