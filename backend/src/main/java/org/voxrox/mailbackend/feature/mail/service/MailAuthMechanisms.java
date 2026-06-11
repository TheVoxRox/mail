package org.voxrox.mailbackend.feature.mail.service;

import java.util.Properties;

/**
 * SASL mechanism identifiers used when configuring JavaMail properties
 * ({@code mail.<proto>.auth.mechanisms}). These are protocol constants (RFC
 * 7628), semantically distinct from the domain {@code AuthType} enum.
 */
final class MailAuthMechanisms {

    static final String XOAUTH2 = "XOAUTH2";

    static void configureOAuth2(Properties props, String protocol) {
        props.put("mail." + protocol + ".auth.mechanisms", XOAUTH2);
        props.put("mail." + protocol + ".auth.login.disable", "true");
        props.put("mail." + protocol + ".auth.plain.disable", "true");
    }

    private MailAuthMechanisms() {
    }
}
