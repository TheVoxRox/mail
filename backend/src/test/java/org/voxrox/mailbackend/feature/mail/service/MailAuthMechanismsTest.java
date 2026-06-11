package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MailAuthMechanismsTest {

    @Test
    @DisplayName("configureOAuth2 sets XOAUTH2 and disables LOGIN/PLAIN fallback for the given protocol")
    void configureOAuth2SetsMechanismAndDisablesPasswordFallbacks() {
        Properties props = new Properties();

        MailAuthMechanisms.configureOAuth2(props, "imaps");

        assertThat(props.getProperty("mail.imaps.auth.mechanisms")).isEqualTo(MailAuthMechanisms.XOAUTH2);
        assertThat(props.getProperty("mail.imaps.auth.login.disable")).isEqualTo("true");
        assertThat(props.getProperty("mail.imaps.auth.plain.disable")).isEqualTo("true");
    }
}
