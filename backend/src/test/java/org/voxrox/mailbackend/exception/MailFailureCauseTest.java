package org.voxrox.mailbackend.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Locale;

import javax.net.ssl.SSLHandshakeException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * The cause a failure is reported as, instead of the text it carried (API
 * surface audit, §3).
 */
class MailFailureCauseTest {

    @Test
    @DisplayName("A failure is classified by what is in its cause chain, however deep")
    void classifiesByTheChain() {
        assertThat(MailFailureCause.classify(new MessagingException("x", new UnknownHostException("imap.nowhere"))))
                .isEqualTo(MailFailureCause.UNKNOWN_HOST);
        assertThat(MailFailureCause.classify(new MessagingException("x", new SocketTimeoutException("Read timed out"))))
                .isEqualTo(MailFailureCause.TIMEOUT);
        assertThat(MailFailureCause.classify(new MessagingException("x", new SSLHandshakeException("PKIX path"))))
                .isEqualTo(MailFailureCause.TLS);
        assertThat(MailFailureCause.classify(new MessagingException("x", new ConnectException("refused"))))
                .isEqualTo(MailFailureCause.CONNECTION);
        assertThat(MailFailureCause.classify(new AuthenticationFailedException("[AUTH] no")))
                .isEqualTo(MailFailureCause.AUTHENTICATION);
        assertThat(MailFailureCause.classify(new RuntimeException(new SQLException("SELECT * FROM messages"))))
                .isEqualTo(MailFailureCause.STORAGE);
        assertThat(MailFailureCause.classify(new DataAccessResourceFailureException("database is locked")))
                .isEqualTo(MailFailureCause.STORAGE);
        assertThat(MailFailureCause.classify(new FileNotFoundException("C:\\Users\\someone\\tmp")))
                .isEqualTo(MailFailureCause.LOCAL_FILE);
        assertThat(MailFailureCause.classify(new IllegalStateException("boom"))).isEqualTo(MailFailureCause.UNEXPECTED);
        assertThat(MailFailureCause.classify(null)).isEqualTo(MailFailureCause.UNEXPECTED);
    }

    @Test
    @DisplayName("The more specific cause wins wherever it sits in the chain")
    void theMoreSpecificCauseWins() {
        // A rejected sign-in arrives wrapped in a connection failure.
        assertThat(MailFailureCause
                .classify(new MailConnectionException("probe failed", new AuthenticationFailedException("[AUTH] no"))))
                .isEqualTo(MailFailureCause.AUTHENTICATION);
        // A refusal of BoundedImapProtocol survives only as the text of Angus's BYE.
        assertThat(MailFailureCause.classify(new MessagingException(
                "* BYE Jakarta Mail Exception: " + MailFailureCause.REFUSED_RESPONSE_MARKER + ": EXISTS 99",
                new IOException("closed")))).isEqualTo(MailFailureCause.REFUSED_RESPONSE);
    }

    @Test
    @DisplayName("A send the server refused tells rejected recipients from any other refusal")
    void sendFailuresAreSplit() throws Exception {
        SendFailedException badRecipient = new SendFailedException("550 no such user", null, new InternetAddress[0],
                new InternetAddress[0], new InternetAddress[]{new InternetAddress("nobody@example.com")});
        SendFailedException refused = new SendFailedException("554 rejected");

        assertThat(MailFailureCause.classify(badRecipient)).isEqualTo(MailFailureCause.RECIPIENTS_REJECTED);
        assertThat(MailFailureCause.classify(refused)).isEqualTo(MailFailureCause.SERVER_REJECTED);
    }

    @Test
    @DisplayName("Every cause has a message in both languages, and none of them is the failure's own text")
    void everyCauseIsLocalized() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        messages.setUseCodeAsDefaultMessage(false);

        for (MailFailureCause cause : MailFailureCause.values()) {
            String english = messages.getMessage(cause.getCodes()[0], null, null, Locale.ENGLISH);
            String czech = messages.getMessage(cause.getCodes()[0], null, null, Locale.forLanguageTag("cs"));
            assertThat(english).as("%s in English", cause).isNotBlank().isEqualTo(cause.getDefaultMessage());
            assertThat(czech).as("%s in Czech", cause).isNotBlank().isNotEqualTo(english);
        }
    }

    @Test
    @DisplayName("A stored cause is read back by name, and anything else is not")
    void fromName() {
        assertThat(MailFailureCause.fromName("TIMEOUT")).contains(MailFailureCause.TIMEOUT);
        assertThat(MailFailureCause.fromName("Read timed out")).isEmpty();
        assertThat(MailFailureCause.fromName(null)).isEmpty();
    }
}
