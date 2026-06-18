package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MailTlsConfig} — the centralised JavaMail TLS hardening
 * (server-identity verification and STARTTLS enforcement).
 */
class MailTlsConfigTest {

    @Nested
    @DisplayName("verifyServerIdentity")
    class VerifyServerIdentity {

        @Test
        @DisplayName("sets checkserveridentity=true for the given protocol")
        void setsCheckServerIdentity() {
            Properties props = new Properties();

            MailTlsConfig.verifyServerIdentity(props, "smtp");

            assertThat(props.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
        }

        @Test
        @DisplayName("uses the protocol name in the property key and touches nothing else")
        void usesProtocolInKey() {
            Properties props = new Properties();

            MailTlsConfig.verifyServerIdentity(props, "imaps");

            assertThat(props.getProperty("mail.imaps.ssl.checkserveridentity")).isEqualTo("true");
            assertThat(props.getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
            assertThat(props.getProperty("mail.imaps.starttls.required")).isNull();
        }
    }

    @Nested
    @DisplayName("requireStartTls")
    class RequireStartTls {

        @Test
        @DisplayName("enables and requires STARTTLS for the given protocol")
        void enablesAndRequiresStartTls() {
            Properties props = new Properties();

            MailTlsConfig.requireStartTls(props, "smtp");

            assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
            assertThat(props.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        }

        @Test
        @DisplayName("does not set implicit SSL or server-identity on its own")
        void doesNotTouchOtherProps() {
            Properties props = new Properties();

            MailTlsConfig.requireStartTls(props, "smtp");

            assertThat(props.getProperty("mail.smtp.ssl.enable")).isNull();
            assertThat(props.getProperty("mail.smtp.ssl.checkserveridentity")).isNull();
        }
    }
}
