package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Dovecot in a container, for the tests that need a real IMAP server rather
 * than GreenMail: {@link MailSyncQresyncDovecotIT} for the QRESYNC and
 * CONDSTORE extensions GreenMail does not offer, and {@link SyncSoakIT} for
 * another client changing the mailbox while the backend has it selected, which
 * GreenMail does not number correctly (see the soak).
 * <p>
 * One definition so both run against the same server. Needs Docker.
 */
final class DovecotTestServer {

    /*
     * dovecot/dovecot:2.4.5, the rootless image, pinned by digest rather than by
     * tag: a tag can be republished, and a test is evidence only for the server it
     * actually ran against. The rootless image serves IMAP on 31143; the
     * configuration below adds STARTTLS to it.
     */
    private static final DockerImageName IMAGE = DockerImageName
            .parse("dovecot/dovecot@sha256:c807be4fb5a97d9c3a90770569d3a6c4cbdcb36742ad41f90409cbd929166553");
    static final int IMAP_PORT = 31143;

    private DovecotTestServer() {
    }

    /**
     * A container, not yet started. The image's passdb accepts any user whose
     * password matches {@code password}. The extra configuration turns TLS on with
     * the shared test certificate from {@link TestTls} and makes it required, so
     * Dovecot offers STARTTLS on the IMAP port and refuses LOGIN before it — the
     * same posture the backend holds on its side (audit B1-4). (Until STARTTLS
     * became required, the QRESYNC IT instead lifted Dovecot's refusal of cleartext
     * LOGIN, auth_allow_cleartext, for the plain connection.)
     */
    static GenericContainer<?> container(String password) {
        return new GenericContainer<>(IMAGE).withEnv("USER_PASSWORD", "{PLAIN}" + password)
                .withCopyToContainer(Transferable.of(TestTls.certificatePem(), 0644), "/etc/dovecot/it-tls/cert.pem")
                .withCopyToContainer(Transferable.of(TestTls.privateKeyPem(), 0644), "/etc/dovecot/it-tls/key.pem")
                .withCopyToContainer(Transferable.of("""
                        ssl = required
                        ssl_server_cert_file = /etc/dovecot/it-tls/cert.pem
                        ssl_server_key_file = /etc/dovecot/it-tls/key.pem
                        """), "/etc/dovecot/conf.d/zz-it.conf").withExposedPorts(IMAP_PORT)
                .waitingFor(Wait.forLogMessage(".*starting up for.*\\n", 1));
    }

    /**
     * A second mail client: its own IMAP session, outside the backend's pooled
     * connections. It upgrades with STARTTLS too: the container requires TLS and
     * offers no login mechanism before it.
     */
    static Store connect(GenericContainer<?> dovecot, String login, String password) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.starttls.enable", "true");
        props.put("mail.imap.starttls.required", "true");
        Store store = Session.getInstance(props).getStore("imap");
        store.connect(dovecot.getHost(), dovecot.getMappedPort(IMAP_PORT), login, password);
        return store;
    }
}
