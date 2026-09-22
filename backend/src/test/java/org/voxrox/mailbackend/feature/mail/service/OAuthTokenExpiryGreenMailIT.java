package org.voxrox.mailbackend.feature.mail.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.account.entity.MailServerConfig;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.ExternalProviderLoginService;
import org.voxrox.mailbackend.feature.auth.service.GoogleTokenService;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * An OAuth access token that stops working while the app keeps running — the
 * RELEASE_CHECKLIST §8.1 and §8.2 item a first hour could only wait for — on
 * the wire. GreenMail checks an XOAUTH2 token as the user's password, so
 * changing that password is the server rejecting the token the app has cached;
 * WireMock stands in for Google's token endpoint and says what the next refresh
 * returns.
 * <p>
 * The backend refuses XOAUTH2 without TLS, so GreenMail serves IMAPS with the
 * shared test certificate from {@link TestTls}, which the JVM's default TLS
 * context trusts. The account reaches GreenMail through {@link TcpFaultProxy}:
 * dropping the connections is what makes the next pass authenticate again, the
 * moment a real server ends a session whose token expired.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.read-timeout=5s",
        "mail.client.imap.connection-timeout=5s", "mail.client.retry.initial-delay=100ms",
        "mail.client.retry.max-delay=300ms",
        // A context of its own, so the data dir above is the one in use.
        "mail.test-context=OAuthTokenExpiryGreenMailIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class OAuthTokenExpiryGreenMailIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "OAuthTokenExpiryGreenMailIT").toAbsolutePath()
            .normalize();

    // An OAuth account needs a provider the catalog knows by domain. Nothing
    // reaches
    // Google: the host and the token endpoint are both replaced before the first
    // pass.
    private static final String EMAIL = "voxrox-oauth-it@gmail.com";
    private static final String TOKEN_PATH = "/token";

    static {
        try {
            deleteRecursively(DATA_DIR);
            Files.createDirectories(DATA_DIR.resolve("logs"));
            // Before the extension below opens GreenMail's TLS listener.
            TestTls.install();
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

    private static @Nullable TcpFaultProxy proxy;
    private static @Nullable WireMockServer tokenEndpoint;

    @BeforeAll
    static void startTokenEndpoint() {
        tokenEndpoint = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        tokenEndpoint.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (proxy != null) {
            proxy.close();
        }
        if (tokenEndpoint != null) {
            tokenEndpoint.stop();
        }
        // The greenmail.tls.* properties belong to TestTls and stay for the JVM.
        for (String property : List.of("app.data-dir", "logging.file.name",
                "spring.security.oauth2.client.registration.google.client-id",
                "spring.security.oauth2.client.registration.google.client-secret",
                "spring.security.oauth2.client.registration.microsoft.client-id")) {
            System.clearProperty(property);
        }
    }

    @Autowired
    private ExternalProviderLoginService externalProviderLoginService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private MailSyncService mailSyncService;
    @Autowired
    private GoogleTokenService googleTokenService;

    @Test
    @DisplayName("A rejected access token is refreshed once and the pass goes on; a revoked refresh token asks for sign-in")
    void rejectedTokenIsRefreshedAndRevokedTokenAsksForSignIn() throws Exception {
        WireMockServer tokens = tokenEndpoint;
        assertThat(tokens).isNotNull();
        GoogleTokenService google = AopTestUtils.getUltimateTargetObject(googleTokenService);
        ReflectionTestUtils.setField(google, "tokenEndpoint", tokens.baseUrl() + TOKEN_PATH);
        proxy = new TcpFaultProxy("127.0.0.1", greenMail.getImaps().getPort());
        GreenMailUser user = greenMail.setUser(EMAIL, EMAIL, "access-token-1");
        AccountEntity account = oauthAccount(proxy.port());

        // --- The first pass refreshes once and signs in with the token it got. ---
        answerRefresh("access-token-1");
        deliver(user, "First");
        assertThat(pass(account)).as("error after the first pass").isNull();
        assertThat(inbox(account)).isEqualTo(1);
        tokens.verify(1, postRequestedFor(urlEqualTo(TOKEN_PATH)));

        // --- The server stops accepting the cached token while the app runs. ---
        user.setPassword("access-token-2");
        answerRefresh("access-token-2");
        proxy.dropConnections();
        deliver(user, "Second");

        assertThat(pass(account)).as("error after the pass that met a rejected token").isNull();
        assertThat(inbox(account)).isEqualTo(2);
        // Exactly one more refresh: the rejection is answered once, not in a loop.
        tokens.verify(2, postRequestedFor(urlEqualTo(TOKEN_PATH)));
        assertThat(reload(account).isRequiresReauth()).isFalse();

        // --- The refresh token itself is revoked. ---
        user.setPassword("access-token-3");
        tokens.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_grant\"}")));
        proxy.dropConnections();

        pass(account);
        assertThat(reload(account).isRequiresReauth()).as("account after the refresh token was revoked").isTrue();
    }

    private AccountEntity oauthAccount(int port) {
        externalProviderLoginService.processExternalProviderLogin(GoogleTokenService.PROVIDER_NAME, EMAIL, "OAuth IT",
                "oauth-it-external-id", "refresh-token-it");
        AccountEntity account = accountRepository.findByEmail(EMAIL).orElseThrow();
        // The host has to match the certificate, which is made for localhost.
        account.setImapConfig(new MailServerConfig("localhost", port, true));
        account.setSmtpConfig(new MailServerConfig("localhost", port, true));
        return accountRepository.save(account);
    }

    private void answerRefresh(String accessToken) {
        WireMockServer tokens = tokenEndpoint;
        assertThat(tokens).isNotNull();
        tokens.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(
                okJson("{\"access_token\":\"" + accessToken + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}")));
    }

    /** One whole-account pass on the service itself, finished when this returns. */
    private @Nullable String pass(AccountEntity account) {
        MailSyncService direct = AopTestUtils.getUltimateTargetObject(mailSyncService);
        direct.syncAllFolders(reload(account), SyncTrigger.SCHEDULED);
        return accountRepository.findLastErrorCode(account.getId()).orElse(null);
    }

    private long inbox(AccountEntity account) {
        return messageRepository.countByAccountIdAndFolderName(account.getId(), "INBOX");
    }

    private AccountEntity reload(AccountEntity account) {
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private void deliver(GreenMailUser user, String subject) {
        user.deliver(GreenMailUtil.createTextEmail(EMAIL, "sender@example.com", subject, "body",
                greenMail.getImaps().getServerSetup()));
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
