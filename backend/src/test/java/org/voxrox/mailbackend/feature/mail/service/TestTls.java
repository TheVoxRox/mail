package org.voxrox.mailbackend.feature.mail.service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.jspecify.annotations.Nullable;

/**
 * The TLS every mail-server integration test shares: one certificate, named for
 * {@code localhost} and {@code 127.0.0.1}, that the test servers present and
 * the backend under test trusts.
 * <p>
 * The backend never talks to a mail server without TLS — IMAP is implicit SSL
 * or required STARTTLS (IMAP/SMTP audit B1-4) and pins
 * {@code checkserveridentity} either way — so a test server has to present a
 * certificate the client accepts, for the address the test connects to.
 * GreenMail's bundled certificate names nobody, so a key pair is generated with
 * the JDK's {@code keytool} at first use; nothing key-shaped is committed.
 * <p>
 * <b>One per JVM, on purpose.</b> Failsafe runs every integration test class in
 * one JVM, and GreenMail builds its TLS server socket factory once, from the
 * {@code greenmail.tls.*} system properties it finds the first time a TLS
 * listener starts. A keystore per test class would leave every class after the
 * first presenting a certificate it does not trust. {@link #install()} is
 * therefore idempotent, and every test that starts a TLS mail server calls it
 * from its static initializer, before the server starts.
 * <p>
 * The default {@link SSLContext} is replaced with one that trusts this
 * certificate and nothing else, and it is not restored: an integration test JVM
 * has no business trusting anything else.
 */
public final class TestTls {

    public static final String PASSWORD = "changeit";

    private static final Path KEYSTORE = Path.of("target", "test-tmp", "tls", "test-mail-server.p12").toAbsolutePath()
            .normalize();
    private static final String ALIAS = "test-mail-server";

    private static @Nullable KeyStore installed;

    private TestTls() {
    }

    /**
     * Generates the certificate (once per JVM), points GreenMail's TLS listener at
     * it and makes the default {@link SSLContext} trust it. Safe to call from every
     * test class.
     */
    public static synchronized void install() {
        if (installed != null) {
            return;
        }
        try {
            generateKeystore();
            System.setProperty("greenmail.tls.keystore.file", KEYSTORE.toString());
            System.setProperty("greenmail.tls.keystore.password", PASSWORD);
            System.setProperty("greenmail.tls.key.password", PASSWORD);

            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(KEYSTORE)) {
                store.load(in, PASSWORD.toCharArray());
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            SSLContext.setDefault(context);
            installed = store;
        } catch (Exception e) {
            throw new IllegalStateException("Could not set up the test TLS certificate", e);
        }
    }

    /**
     * The keystore file, for a JVM this one starts (the soak test's backend
     * process): pass it as that JVM's {@code javax.net.ssl.trustStore}. The JDK
     * trusts the certificate of a key entry, so no separate trust store is needed.
     */
    public static Path keystoreFile() {
        install();
        return KEYSTORE;
    }

    /** The certificate as PEM, for a test server that is not GreenMail. */
    public static String certificatePem() {
        try {
            Certificate certificate = keystore().getCertificate(ALIAS);
            return pem("CERTIFICATE", certificate.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Could not export the test certificate", e);
        }
    }

    /**
     * The private key as unencrypted PKCS#8 PEM, for a test server that is not
     * GreenMail.
     */
    public static String privateKeyPem() {
        try {
            Key key = keystore().getKey(ALIAS, PASSWORD.toCharArray());
            return pem("PRIVATE KEY", key.getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Could not export the test private key", e);
        }
    }

    /**
     * A server socket factory presenting the certificate, for a test server this
     * class does not otherwise reach: one written in the test itself
     * ({@link HostileImapServer}).
     */
    public static SSLServerSocketFactory serverSocketFactory() {
        try {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(keystore(), PASSWORD.toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context.getServerSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Could not build the test TLS server socket factory", e);
        }
    }

    private static synchronized KeyStore keystore() {
        install();
        KeyStore store = installed;
        if (store == null) {
            throw new IllegalStateException("The test TLS certificate is not installed");
        }
        return store;
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    /*
     * Regenerated on every JVM start rather than reused from an earlier build, so
     * its short validity never matters and a stale file from another branch is
     * never trusted.
     */
    private static void generateKeystore() throws Exception {
        Files.createDirectories(KEYSTORE.getParent());
        Files.deleteIfExists(KEYSTORE);
        Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext",
                "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", KEYSTORE.toString(),
                "-storepass", PASSWORD, "-keypass", PASSWORD).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }
}
