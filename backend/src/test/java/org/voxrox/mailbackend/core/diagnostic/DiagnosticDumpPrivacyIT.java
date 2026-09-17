package org.voxrox.mailbackend.core.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.voxrox.mailbackend.core.config.StorageProperties;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.auth.service.TokenCache;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.service.MailSyncService;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The privacy half of RELEASE_CHECKLIST §7, against real data instead of mocks:
 * an account synced from a live IMAP server, a folder the user named, message
 * content, a cached OAuth token and the session API key all exist, and none of
 * them may be in the diagnostic dump a user sends to support. The dump is
 * fetched the way the client fetches it, over HTTP with the key, so the
 * controller and the ZIP are the ones a user gets.
 * <p>
 * Every value checked for is a canary: a string that appears nowhere else, so
 * finding it anywhere in any entry is a leak, whatever the entry's shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Keep the background scheduler out: the only sync runs are the explicit ones.
        "mail.client.sync.initial-delay=PT1H",
        // A context of its own: the other GreenMail tests share this setup,
        // and their cached context has another data dir and session key.
        "mail.test-context=DiagnosticDumpPrivacyIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class DiagnosticDumpPrivacyIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "DiagnosticDumpPrivacyIT").toAbsolutePath()
            .normalize();

    private static final String EMAIL = "canary.owner.4417@greenmail.local";
    private static final String LOGIN = "canary-login-4417";
    private static final String PASSWORD = "canary-password-4417";
    private static final String CUSTOM_FOLDER = "Canary Lawyer 4417";
    private static final String SUBJECT = "Canary subject 4417";
    private static final String BODY = "Canary body text 4417";
    private static final String TOKEN = "ya29.canary-access-token-4417-abcdefghijklmnop";

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
    private MailSyncService mailSyncService;
    @Autowired
    private TokenCache tokenCache;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StorageProperties storageProperties;

    @Test
    @DisplayName("The dump carries no address, credential, token, API key, message content or user-named folder")
    void dumpHoldsNothingPersonal() throws Exception {
        GreenMailUser user = greenMail.setUser(EMAIL, LOGIN, PASSWORD);
        MailServerSettings server = new MailServerSettings("127.0.0.1", greenMail.getImap().getPort(), false);
        accountService.createAccount(
                new AccountCreateRequest("Canary account", null, EMAIL, null, server, server, LOGIN, PASSWORD));
        AccountEntity account = accountRepository.findByEmail(EMAIL).orElseThrow();

        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "canary.sender.4417@example.com", SUBJECT, BODY,
                greenMail.getImap().getServerSetup()));
        appendToCustomFolder();
        assertThat(mailSyncService.performFullSyncCycle(account, "INBOX", FolderRole.INBOX)).isTrue();
        assertThat(mailSyncService.performFullSyncCycle(account, CUSTOM_FOLDER)).isTrue();
        cacheToken(account.getId());

        JsonNode session = objectMapper
                .readTree(Files.readString(storageProperties.getDataPath().resolve("session.json")));
        String apiKey = session.get("apiKey").asString();
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest
                        .newBuilder(URI.create(session.get("baseUrl").asString() + "/internal/diagnostic-dump"))
                        .header("X-API-KEY", apiKey).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);

        Map<String, String> entries = unzip(response.body());
        assertThat(entries).containsKeys("summary.json", "accounts.json", "folder-sync-states.json",
                "message-counts.json", "runtime.json");
        String home = System.getProperty("user.home");
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            assertThat(entry.getValue()).as(entry.getKey()).doesNotContain(EMAIL, "canary.owner", LOGIN, PASSWORD,
                    "canary.sender", SUBJECT, BODY, CUSTOM_FOLDER, TOKEN, apiKey,
                    objectMapper.writeValueAsString(home).replace("\"", ""));
        }

        // The dump still says what support needs.
        assertThat(entries.get("accounts.json")).contains("c***7@greenmail.local");
        assertThat(entries.get("folder-sync-states.json")).contains("\"INBOX\"").contains("\"folder-1\"");
        assertThat(objectMapper.readTree(entries.get("summary.json")).get("oauthCachedTokens").asInt()).isEqualTo(1);
    }

    /**
     * A second client creates the folder and files a message there, as a user
     * would.
     */
    private void appendToCustomFolder() throws Exception {
        Session session = Session.getInstance(new Properties());
        try (Store store = session.getStore("imap")) {
            store.connect("127.0.0.1", greenMail.getImap().getPort(), LOGIN, PASSWORD);
            Folder folder = store.getFolder(CUSTOM_FOLDER);
            assertThat(folder.create(Folder.HOLDS_MESSAGES)).isTrue();
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress("canary.sender.4417@example.com"));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(EMAIL));
            message.setSubject(SUBJECT);
            message.setText(BODY, StandardCharsets.UTF_8.name());
            folder.appendMessages(new Message[]{message});
        }
    }

    /**
     * Puts an access token in the shared cache. {@code CachedToken} is private to
     * the auth package, so it is built reflectively; the dump reads only the cache
     * size, and the token is here to prove that stays so.
     */
    private void cacheToken(Long accountId) throws Exception {
        Class<?> cachedToken = Class.forName("org.voxrox.mailbackend.feature.auth.service.CachedToken");
        Constructor<?> constructor = cachedToken.getDeclaredConstructor(String.class, Instant.class);
        constructor.setAccessible(true);
        Method put = TokenCache.class.getMethod("put", Long.class, cachedToken);
        put.invoke(tokenCache, accountId, constructor.newInstance(TOKEN, Instant.now().plusSeconds(3600)));
    }

    private static Map<String, String> unzip(byte[] zipBytes) throws Exception {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }
}
