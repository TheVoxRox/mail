package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Let's Encrypt certificates {@link MailTlsContext} completes chains with
 * are what they claim to be, and still worth carrying.
 * <p>
 * Date-driven on purpose. Let's Encrypt rotates its intermediates on a schedule
 * of its own, and the client finds out only when a server's chain stops
 * building, which reaches the project as user reports. A red build six months
 * ahead is the cheaper messenger. It will turn every branch red at once, the
 * dependency bots' included; what fixes it is a refresh on {@code main}, never
 * a wider margin.
 * <p>
 * What it cannot see is a <b>new</b> intermediate: it knows only the expiry of
 * the ones it holds. {@code docs/DECISIONS.md} names that as a reason to reopen
 * the bundle.
 */
class LetsEncryptIntermediatesTest {

    /** About six months: the lead a refresh gets before an intermediate expires. */
    private static final Duration WARNING = Duration.ofDays(183);

    private static final String REFRESH = "Refresh backend/src/main/resources/tls/ from "
            + "https://letsencrypt.org/certificates/: every intermediate it lists as active or backup, and the "
            + "cross-signs of the roots that are not in the JDK's cacerts, go into letsencrypt-intermediates.pem; "
            + "what it lists as retired moves to letsencrypt-retired-intermediates.pem.";

    private static Set<TrustAnchor> jdkAnchors;
    private static List<X509Certificate> bundle;

    @BeforeAll
    static void load() throws Exception {
        // The build JDK's own store, not whatever javax.net.ssl.trustStore names:
        // this is the store the packaged sidecar's JDK ships.
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        KeyStore store = KeyStore.getInstance(cacerts.toFile(), "changeit".toCharArray());
        Set<TrustAnchor> anchors = new HashSet<>();
        for (String alias : Collections.list(store.aliases())) {
            if (store.isCertificateEntry(alias) && store.getCertificate(alias) instanceof X509Certificate root) {
                anchors.add(new TrustAnchor(root, null));
            }
        }
        jdkAnchors = anchors;
        bundle = MailTlsContext.bundledIntermediates();
    }

    @Test
    @DisplayName("Both bundle files hold certificates, and the production loader reads them all")
    void theBundleIsNotEmpty() {
        assertThat(MailTlsContext.certificates(MailTlsContext.CURRENT)).isNotEmpty();
        assertThat(MailTlsContext.certificates(MailTlsContext.RETIRED)).isNotEmpty();
        assertThat(bundle).hasSize(MailTlsContext.certificates(MailTlsContext.CURRENT).size()
                + MailTlsContext.certificates(MailTlsContext.RETIRED).size());
    }

    @Test
    @DisplayName("Every bundled certificate chains, through the bundle, to a root in the build JDK's cacerts")
    void everyCertificateChainsToTheJdk() throws Exception {
        for (X509Certificate certificate : bundle) {
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(certificate);
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(jdkAnchors, target);
            parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(bundle)));
            parameters.setRevocationEnabled(false);
            try {
                CertPathBuilder.getInstance("PKIX").build(parameters);
            } catch (CertPathBuilderException e) {
                fail(certificate.getSubjectX500Principal() + " does not chain to the JDK's cacerts (" + e.getMessage()
                        + "). A bundled certificate is path material, never an anchor, so one that reaches no "
                        + "root the JDK trusts completes nothing. " + REFRESH);
            }
        }
    }

    @Test
    @DisplayName("No current intermediate or cross-sign expires within six months")
    void noCurrentCertificateExpiresSoon() {
        Instant deadline = Instant.now().plus(WARNING);
        for (X509Certificate certificate : MailTlsContext.certificates(MailTlsContext.CURRENT)) {
            assertThat(certificate.getNotAfter().toInstant())
                    .as("%s expires on %s, within six months. Let's Encrypt will be issuing from its successor "
                            + "by then, and a server that sends its leaf alone will fail again. %s",
                            certificate.getSubjectX500Principal(), certificate.getNotAfter().toInstant(), REFRESH)
                    .isAfter(deadline);
        }
    }

    /**
     * A retired intermediate gets no warning: it has no successor to fetch, and
     * once it expires nothing it issued is valid either, so it can complete no path
     * and is only weight.
     */
    @Test
    @DisplayName("No retired intermediate has expired")
    void noRetiredCertificateHasExpired() {
        Instant now = Instant.now();
        for (X509Certificate certificate : MailTlsContext.certificates(MailTlsContext.RETIRED)) {
            assertThat(certificate.getNotAfter().toInstant()).as(
                    "%s expired on %s. Delete it from letsencrypt-retired-intermediates.pem (and the file, with "
                            + "MailTlsContext.RETIRED, once it is empty).",
                    certificate.getSubjectX500Principal(), certificate.getNotAfter().toInstant()).isAfter(now);
        }
    }
}
