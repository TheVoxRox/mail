package org.voxrox.mailbackend.feature.mail.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;

/**
 * The one TLS context every connection to a mail server is made with: IMAP
 * through the connection pool and the credential probe
 * ({@link ImapTransportSecurity}), SMTP through {@link SmtpTransportFactory}.
 * <p>
 * It trusts exactly what the JDK's default trust manager trusts. What it adds
 * is material to build a path with: the Let's Encrypt intermediates, and the
 * cross-signs that connect Let's Encrypt's Generation Y roots to the ISRG roots
 * in the JDK's {@code cacerts}, bundled as {@link #CURRENT} and
 * {@link #RETIRED}. The JDK neither fetches a certificate a server left out nor
 * knows the Generation Y roots, so a server that sends its leaf alone, or the
 * leaf and its intermediate without the cross-sign, used to fail path building
 * and the account showed {@code error.mail.cause.tls} (#928). With the bundle
 * the client completes the chain itself.
 * <p>
 * A bundled certificate is <b>never a trust anchor</b>. A path still has to
 * end, by signature, at a root the trust store holds, so the bundle accepts
 * nothing a server sending its full chain could not already have reached.
 * Revocation stays off, as it is in the JSSE default
 * ({@code com.sun.net.ssl.checkRevocation}). Fetching a missing intermediate
 * over AIA instead was rejected; {@code docs/DECISIONS.md} says why.
 * <p>
 * The anchors are read from the default trust store as JSSE resolves it
 * ({@code javax.net.ssl.trustStore} if set, otherwise the JDK's
 * {@code cacerts}), and on first use rather than when the application context
 * starts, so a test that points the property at its own store before it
 * connects is honoured. One context serves every connection: the TLS session
 * cache lives in it, and reading the trust store and parsing the bundle once
 * per connect would be waste.
 * <p>
 * The callers hand {@link #socketFactory()} to Angus as
 * {@code mail.<protocol>.ssl.socketFactory} and turn
 * {@code mail.<protocol>.socketFactory.fallback} off beside it. Angus defaults
 * the fallback to on: when the configured factory fails an implicit-SSL connect
 * with anything but a timeout, it connects again through
 * {@link SSLSocketFactory#getDefault()}, which costs a second connect and
 * handshake and reports the second attempt's error instead of the first (a
 * hostname mismatch read as a path failure).
 */
@Component
public class MailTlsContext {

    /**
     * The intermediates Let's Encrypt issues from or may switch to without notice,
     * and the Generation Y cross-signs, as a classpath resource.
     * {@code LetsEncryptIntermediatesTest} fails six months before one expires.
     */
    static final String CURRENT = "tls/letsencrypt-intermediates.pem";

    /**
     * The intermediates Let's Encrypt has retired whose certificates are still in
     * use, as a classpath resource. {@code LetsEncryptIntermediatesTest} fails once
     * one has expired, when it can complete no valid path and is deleted.
     */
    static final String RETIRED = "tls/letsencrypt-retired-intermediates.pem";

    private final SingletonSupplier<SSLSocketFactory> socketFactory;

    public MailTlsContext() {
        this(MailTlsContext::defaultTrustAnchors, MailTlsContext::bundledIntermediates);
    }

    /**
     * A context that trusts {@code anchors} and builds paths with
     * {@code intermediates}, both read on first use. The seam an integration test
     * uses to put its own root and intermediate in place of the JDK's and the
     * bundle's.
     */
    MailTlsContext(Supplier<Set<TrustAnchor>> anchors, Supplier<Collection<X509Certificate>> intermediates) {
        this.socketFactory = SingletonSupplier.of(() -> build(anchors.get(), intermediates.get()).getSocketFactory());
    }

    /**
     * The socket factory of the shared context, built on the first call; every call
     * returns the same instance.
     */
    public SSLSocketFactory socketFactory() {
        return socketFactory.obtain();
    }

    private static SSLContext build(Set<TrustAnchor> anchors, Collection<X509Certificate> intermediates) {
        try {
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, new X509CertSelector());
            parameters.addCertStore(
                    CertStore.getInstance("Collection", new CollectionCertStoreParameters(intermediates)));
            parameters.setRevocationEnabled(false);
            TrustManagerFactory trust = TrustManagerFactory.getInstance("PKIX");
            trust.init(new CertPathTrustManagerParameters(parameters));
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not build the TLS context for mail servers", e);
        }
    }

    /**
     * What the JDK's default trust manager trusts, read the way JSSE reads it, so
     * {@code javax.net.ssl.trustStore} and its siblings are honoured.
     */
    static Set<TrustAnchor> defaultTrustAnchors() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    Set<TrustAnchor> anchors = new HashSet<>();
                    for (X509Certificate certificate : x509.getAcceptedIssuers()) {
                        anchors.add(new TrustAnchor(certificate, null));
                    }
                    return anchors;
                }
            }
            throw new IllegalStateException("The default trust manager factory has no X.509 trust manager");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not read the default trust store", e);
        }
    }

    /** Every bundled certificate, {@link #CURRENT}'s first. */
    static List<X509Certificate> bundledIntermediates() {
        List<X509Certificate> certificates = new ArrayList<>(certificates(CURRENT));
        certificates.addAll(certificates(RETIRED));
        return List.copyOf(certificates);
    }

    /** The certificates of one bundled resource, in file order. */
    static List<X509Certificate> certificates(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            List<X509Certificate> certificates = new ArrayList<>();
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                certificates.add((X509Certificate) certificate);
            }
            return certificates;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not read the bundled certificates in " + resource, e);
        }
    }
}
