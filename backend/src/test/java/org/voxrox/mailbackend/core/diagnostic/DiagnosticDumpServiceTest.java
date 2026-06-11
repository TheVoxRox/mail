package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.voxrox.mailbackend.core.config.ApplicationVersion;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.core.init.StartupTimingService;
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
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class DiagnosticDumpServiceTest {

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
                Map.of("appReady", 1234L, "apiKey", 999L), "Playwright", "cs", "/settings/about?secret=1"));
        StartupTimingService startupTimingService = new StartupTimingService();
        long startupPhaseStarted = startupTimingService.start();
        startupTimingService.record("test.startup-phase", startupPhaseStarted);

        DiagnosticDumpService service = new DiagnosticDumpService(accountRepository, folderSyncStateRepository,
                messageRepository, imapConnectionManager, oauth2TokenServiceRegistry,
                new StorageProperties("target/test-data"),
                new MockEnvironment().withProperty("server.port", "0").withProperty("local.server.port", "61234"),
                new ObjectMapper(), new ApplicationVersion("9.8.7-test"), clientBootDiagnosticsService,
                startupTimingService);

        Map<String, String> entries = unzip(service.createDump());

        assertThat(entries.keySet()).containsExactlyInAnyOrder("summary.json", "accounts.json",
                "folder-sync-states.json", "message-counts.json", "runtime.json", "client-boot.json",
                "startup-timings.json");
        assertThat(entries.get("summary.json")).contains("\"appName\" : \"mail\"")
                .contains("\"appVersion\" : \"9.8.7-test\"").contains("\"messages\" : 12")
                .contains("\"activeConnections\" : 1").contains("\"oauthCachedTokens\" : 1");
        assertThat(entries.get("accounts.json")).contains("\"maskedEmail\" : \"t***r@example.com\"")
                .contains("\"authType\" : \"PASSWORD\"").contains("\"host\" : \"imap.example.com\"")
                .doesNotContain("tester@example.com").doesNotContain("encrypted-secret").doesNotContain("plain-secret");
        assertThat(entries.get("message-counts.json")).contains("\"messages\" : 12");
        assertThat(entries.get("client-boot.json")).contains("\"phase\" : \"ready\"").contains("\"appReady\" : 1234")
                .contains("\"route\" : \"/settings/about\"").doesNotContain("secret").doesNotContain("apiKey");
        assertThat(entries.get("startup-timings.json")).contains("\"phase\" : \"test.startup-phase\"")
                .contains("\"durationMs\"");
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
