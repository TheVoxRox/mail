package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.init.StorageContextInitializer;
import org.voxrox.mailbackend.exception.MailConnectionException;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.dto.AccountCreateRequest;
import org.voxrox.mailbackend.feature.account.dto.MailServerSettings;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountService;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;

/**
 * A server that sends its certificate without the intermediate that issued it,
 * the shape of #928: a Let's Encrypt server configured with {@code cert.pem}
 * rather than {@code fullchain.pem}. The JDK builds no path from a leaf alone;
 * {@link MailTlsContext} completes it from the intermediates it carries.
 * <p>
 * Real Let's Encrypt leaves live six days, so none can be a fixture. The test
 * builds the same shape with {@code keytool}: a root, an intermediate the root
 * issued, and a leaf the intermediate issued, presented by a server whose key
 * entry's chain is the leaf alone. The context under test is the application's
 * own, with its {@link MailTlsContext} bean replaced by one that trusts the
 * test root and carries the test intermediate, the way the production bean
 * trusts the JDK's roots and carries Let's Encrypt's intermediates. So the
 * connection pool and the probe below are the ones the application wires, and
 * what they are handed is the bean.
 * <p>
 * A server of its own rather than GreenMail: GreenMail builds its TLS factory
 * once per JVM from {@link TestTls}'s certificate, which every other
 * integration test depends on. SMTP takes its sockets from the same bean, which
 * {@code SmtpTransportFactoryTest} pins.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // Nothing connects but the tests below.
        "mail.client.sync.initial-delay=PT1H", "mail.client.imap.read-timeout=3s",
        "mail.client.imap.connection-timeout=3s", "mail.client.retry.initial-delay=100ms",
        "mail.client.retry.max-delay=300ms",
        // A context of its own, so the data dir below is the one in use.
        "mail.test-context=LeafOnlyChainIT"})
@ContextConfiguration(initializers = StorageContextInitializer.class)
class LeafOnlyChainIT {

    private static final Path DATA_DIR = Path.of("target", "test-tmp", "LeafOnlyChainIT").toAbsolutePath().normalize();
    private static final char[] PASSWORD = "changeit".toCharArray();

    private static final Pki PKI;
    /** Presents the leaf alone, from the first byte. */
    private static final HostileImapServer IMPLICIT;
    /** Presents the leaf alone, after STARTTLS. */
    private static final HostileImapServer STARTTLS;
    /**
     * Presents, alone, a leaf the same intermediate issued for a name the tests
     * never connect to.
     */
    private static final HostileImapServer WRONG_HOST;

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
            PKI = Pki.generate(DATA_DIR.resolve("pki"));
            IMPLICIT = HostileImapServer.implicitTls(presenting(PKI.leafKey(), PKI.leaf()));
            STARTTLS = HostileImapServer.startTls(presenting(PKI.leafKey(), PKI.leaf()));
            WRONG_HOST = HostileImapServer.implicitTls(presenting(PKI.wrongHostKey(), PKI.wrongHostLeaf()));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * The application's bean, replaced: the test root as the anchor, the test
     * intermediate as the bundle. Never read: Spring reads the annotation, and the
     * pool and the probe below are handed what {@link #mailTlsContext()} builds.
     */
    @SuppressWarnings("UnusedVariable")
    @TestBean
    private MailTlsContext mailTlsContext;

    static MailTlsContext mailTlsContext() {
        return new MailTlsContext(() -> Set.of(new TrustAnchor(PKI.root(), null)), () -> List.of(PKI.intermediate()));
    }

    @Autowired
    private MailConnectionProbe probe;
    @Autowired
    private ImapConnectionManager connections;
    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private MailClientProperties mailProperties;
    @Autowired
    private OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    @Autowired
    private SmtpTransportFactory smtpTransportFactory;

    private final List<Long> pooled = new ArrayList<>();

    @AfterEach
    void dropPooledConnections() {
        pooled.forEach(connections::removeConnection);
        pooled.clear();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IMPLICIT.close();
        STARTTLS.close();
        WRONG_HOST.close();
        System.clearProperty("app.data-dir");
        System.clearProperty("logging.file.name");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-id");
        System.clearProperty("spring.security.oauth2.client.registration.google.client-secret");
        System.clearProperty("spring.security.oauth2.client.registration.microsoft.client-id");
    }

    @Test
    @DisplayName("The probe completes a leaf-only chain on implicit SSL")
    void probeOverImplicitSsl() {
        assertThatCode(() -> probe.testImap(1L, details(IMPLICIT, true))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The probe completes a leaf-only chain after STARTTLS")
    void probeOverStartTls() {
        assertThatCode(() -> probe.testImap(1L, details(STARTTLS, false))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The connection pool completes a leaf-only chain on implicit SSL")
    void poolOverImplicitSsl() {
        assertThat(connectThroughThePool("implicit@leaf-only.test", IMPLICIT, true)).isTrue();
    }

    @Test
    @DisplayName("The connection pool completes a leaf-only chain after STARTTLS")
    void poolOverStartTls() {
        assertThat(connectThroughThePool("starttls@leaf-only.test", STARTTLS, false)).isTrue();
    }

    /**
     * Angus sets the endpoint identification algorithm on the socket the factory
     * returns, so the check holds through the new factory as it did through the
     * default one. The message is the identity check's own: a refusal retried
     * through the JDK's default factory would report that factory's path failure
     * instead.
     */
    @Test
    @DisplayName("A leaf that does not name the host is refused, by the identity check")
    void aLeafForAnotherHostIsRefused() {
        assertThatThrownBy(() -> probe.testImap(1L, details(WRONG_HOST, true)))
                .isInstanceOf(MailConnectionException.class)
                .hasStackTraceContaining("No subject alternative names matching IP address 127.0.0.1");
    }

    @Test
    @DisplayName("Without the intermediate in the bundle, a leaf-only chain is refused on implicit SSL")
    void withoutTheIntermediateImplicitSslIsRefused() {
        MailConnectionProbe withoutIntermediate = probe(
                new MailTlsContext(() -> Set.of(new TrustAnchor(PKI.root(), null)), List::of));

        assertThatThrownBy(() -> withoutIntermediate.testImap(1L, details(IMPLICIT, true)))
                .isInstanceOf(MailConnectionException.class)
                .hasStackTraceContaining("unable to find valid certification path");
    }

    @Test
    @DisplayName("Without the intermediate in the bundle, a leaf-only chain is refused after STARTTLS")
    void withoutTheIntermediateStartTlsIsRefused() {
        MailConnectionProbe withoutIntermediate = probe(
                new MailTlsContext(() -> Set.of(new TrustAnchor(PKI.root(), null)), List::of));

        assertThatThrownBy(() -> withoutIntermediate.testImap(1L, details(STARTTLS, false)))
                .isInstanceOf(MailConnectionException.class)
                .hasStackTraceContaining("unable to find valid certification path");
    }

    /**
     * The bundle is path material, never trust: a root that sits in it rather than
     * among the anchors makes nothing it issued trusted.
     */
    @Test
    @DisplayName("A root carried in the bundle is not trusted")
    void aRootInTheBundleIsNotAnAnchor() {
        MailConnectionProbe rootInTheBundle = probe(
                new MailTlsContext(MailTlsContext::defaultTrustAnchors, () -> List.of(PKI.intermediate(), PKI.root())));

        assertThatThrownBy(() -> rootInTheBundle.testImap(1L, details(IMPLICIT, true)))
                .isInstanceOf(MailConnectionException.class)
                .hasStackTraceContaining("unable to find valid certification path");
    }

    private MailConnectionProbe probe(MailTlsContext tls) {
        return new MailConnectionProbe(mailProperties, oauth2TokenServiceRegistry, smtpTransportFactory, tls);
    }

    private boolean connectThroughThePool(String email, HostileImapServer server, boolean useSsl) {
        Long accountId = accountRepository.findByEmail(email).orElseGet(() -> {
            MailServerSettings settings = new MailServerSettings("127.0.0.1", server.port(), useSsl);
            accountService.createAccount(new AccountCreateRequest("Leaf-only IT", null, email, null, settings, settings,
                    "user", "password"));
            return accountRepository.findByEmail(email).orElseThrow();
        }).getId();
        pooled.add(accountId);
        Boolean connected = connections.executeWithLock(accountId, Lane.INTERACTIVE, store -> store.isConnected());
        return Boolean.TRUE.equals(connected);
    }

    private static AccountConnectionDetails details(HostileImapServer server, boolean useSsl) {
        return new AccountConnectionDetails("leaf-only@example.test", "127.0.0.1", server.port(), useSsl, "user",
                "password", AuthType.PASSWORD, null);
    }

    /** A server identity whose certificate chain is {@code leaf} alone. */
    private static SSLContext presenting(PrivateKey key, X509Certificate leaf) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("server", key, PASSWORD, new Certificate[]{leaf});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }

    /**
     * A root, an intermediate the root issued, and two leaves the intermediate
     * issued: one for the address the tests connect to, and one for a name they
     * never use. Generated with the JDK's {@code keytool} on every run; nothing
     * key-shaped is committed, and the two-day validity never matters.
     */
    private record Pki(X509Certificate root, X509Certificate intermediate, PrivateKey leafKey, X509Certificate leaf,
            PrivateKey wrongHostKey, X509Certificate wrongHostLeaf) {

        static Pki generate(Path dir) throws Exception {
            Files.createDirectories(dir);
            Path store = dir.resolve("pki.p12");
            keyPair(store, "root", "CN=Leaf-only IT root", "-ext", "BasicConstraints:critical=ca:true", "-ext",
                    "KeyUsage:critical=keyCertSign,cRLSign");
            keyPair(store, "intermediate", "CN=Leaf-only IT intermediate");
            X509Certificate intermediate = issue(store, dir, "intermediate", "root",
                    "BasicConstraints:critical=ca:true,pathlen:0", "KeyUsage:critical=keyCertSign,cRLSign");
            keyPair(store, "leaf", "CN=localhost");
            X509Certificate leaf = issue(store, dir, "leaf", "intermediate", "SAN=dns:localhost,ip:127.0.0.1",
                    "KeyUsage:critical=digitalSignature", "ExtendedKeyUsage=serverAuth");
            keyPair(store, "wrong", "CN=wrong.example.test");
            X509Certificate wrongHost = issue(store, dir, "wrong", "intermediate", "SAN=dns:wrong.example.test",
                    "KeyUsage:critical=digitalSignature", "ExtendedKeyUsage=serverAuth");

            KeyStore keys = KeyStore.getInstance(store.toFile(), PASSWORD);
            return new Pki((X509Certificate) keys.getCertificate("root"), intermediate,
                    (PrivateKey) keys.getKey("leaf", PASSWORD), leaf, (PrivateKey) keys.getKey("wrong", PASSWORD),
                    wrongHost);
        }

        /**
         * A key pair under {@code alias}, its certificate self-signed until
         * {@link #issue} replaces it.
         */
        private static void keyPair(Path store, String alias, String name, String... extensions) throws Exception {
            List<String> arguments = new ArrayList<>(List.of("-genkeypair", "-alias", alias, "-dname", name, "-keyalg",
                    "EC", "-groupname", "secp384r1", "-validity", "2"));
            arguments.addAll(List.of(extensions));
            keytool(store, arguments.toArray(String[]::new));
        }

        /**
         * The certificate {@code issuer} signs for {@code subject}'s key, with these
         * extensions.
         */
        private static X509Certificate issue(Path store, Path dir, String subject, String issuer, String... extensions)
                throws Exception {
            Path request = dir.resolve(subject + ".csr");
            Path certificate = dir.resolve(subject + ".pem");
            keytool(store, "-certreq", "-alias", subject, "-file", request.toString());
            List<String> arguments = new ArrayList<>(List.of("-gencert", "-alias", issuer, "-infile",
                    request.toString(), "-outfile", certificate.toString(), "-rfc", "-validity", "2"));
            for (String extension : extensions) {
                arguments.add("-ext");
                arguments.add(extension);
            }
            keytool(store, arguments.toArray(String[]::new));
            try (InputStream in = Files.newInputStream(certificate)) {
                return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        }

        private static void keytool(Path store, String... arguments) throws Exception {
            Path keytool = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
            List<String> command = new ArrayList<>(List.of(keytool.toString()));
            command.addAll(List.of(arguments));
            command.addAll(List.of("-storetype", "PKCS12", "-keystore", store.toString(), "-storepass",
                    new String(PASSWORD), "-keypass", new String(PASSWORD)));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool " + arguments[0] + " failed: " + output);
            }
        }
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
