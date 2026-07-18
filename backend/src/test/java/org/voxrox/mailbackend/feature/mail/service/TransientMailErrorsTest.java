package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.StoreClosedException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TransientMailErrors} — the classifier that decides
 * which IMAP failures the sync retry loop reconnects-and-retries (bug D, #78)
 * versus which it records as a hard {@code last_error}.
 */
class TransientMailErrorsTest {

    @Test
    @DisplayName("Genuine transient network errors are transient")
    void networkErrorsAreTransient() {
        assertThat(TransientMailErrors.isTransient(new SocketTimeoutException("read timed out"))).isTrue();
        assertThat(TransientMailErrors.isTransient(new ConnectException("connection refused"))).isTrue();
        assertThat(TransientMailErrors.isTransient(new UnknownHostException("imap.example.com"))).isTrue();
        assertThat(TransientMailErrors.isTransient(new SSLException("connection reset"))).isTrue();
        assertThat(TransientMailErrors.isTransient(new SSLHandshakeException("peer reset"))).isTrue();
        assertThat(TransientMailErrors.isTransient(new IOException("broken pipe"))).isTrue();
        assertThat(TransientMailErrors.isTransient(new StoreClosedException(null, "store closed"))).isTrue();
        assertThat(TransientMailErrors.isTransient(new FolderClosedException(null, "folder closed"))).isTrue();
    }

    @Test
    @DisplayName("Angus 'failed to create new store connection' (bug D) is transient via message, case-insensitive")
    void angusNewStoreConnectionMessageIsTransient() {
        assertThat(TransientMailErrors.isTransient(new MessagingException("failed to create new store connection")))
                .isTrue();
        assertThat(TransientMailErrors.isTransient(new MessagingException("Failed To Create New Store Connection")))
                .isTrue();
    }

    @Test
    @DisplayName("Transient cause buried in the chain is detected (traverses causes)")
    void transientDeepInCauseChainIsTransient() {
        MessagingException wrapped = new MessagingException("connect failed", new ConnectException("refused"));
        RuntimeException outer = new RuntimeException("sync blew up", wrapped);
        assertThat(TransientMailErrors.isTransient(outer)).isTrue();
    }

    @Test
    @DisplayName("Authentication failures are never transient (own refresh-token path), even when wrapped")
    void authenticationFailureIsNotTransient() {
        assertThat(TransientMailErrors.isTransient(new AuthenticationFailedException("bad credentials"))).isFalse();
        // An auth failure anywhere in the chain wins over a transient-looking wrapper.
        MessagingException wrapped = new MessagingException("failed to create new store connection",
                new AuthenticationFailedException("token expired"));
        assertThat(TransientMailErrors.isTransient(wrapped)).isFalse();
    }

    @Test
    @DisplayName("A cyclic cause chain terminates and still classifies correctly")
    void cyclicCauseChainTerminates() {
        // Cycle of non-transient links -> classified false, and the walk ends.
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second); // first -> second -> first -> ...
        assertThat(TransientMailErrors.isTransient(second)).isFalse();

        // A transient link inside a cycle is still found.
        RuntimeException wrapper = new RuntimeException("wrapper");
        ConnectException refused = new ConnectException("refused");
        wrapper.initCause(refused);
        refused.initCause(wrapper); // wrapper -> refused -> wrapper -> ...
        assertThat(TransientMailErrors.isTransient(wrapper)).isTrue();
    }

    @Test
    @DisplayName("Permanent / programming errors are not transient")
    void permanentErrorsAreNotTransient() {
        assertThat(TransientMailErrors.isTransient(new RuntimeException("IMAP timeout"))).isFalse();
        assertThat(TransientMailErrors.isTransient(new IllegalStateException("bad state"))).isFalse();
        assertThat(TransientMailErrors.isTransient(new MessagingException("Unable to load BODYSTRUCTURE"))).isFalse();
        assertThat(TransientMailErrors.isTransient(new MessagingException((String) null))).isFalse();
    }
}
