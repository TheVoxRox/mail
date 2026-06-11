package org.voxrox.mailbackend.core.config.mail;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable SMTP knobs. The protocol identifier (always {@code "smtp"} for
 * JavaMail's {@code session.getTransport(...)}) and the default port (always
 * provided by the caller via {@code AccountConnectionDetails.port()}) are not
 * tunables — they are hard-coded in {@code SmtpTransportFactory}.
 */
public record SmtpProperties(@NotNull @DefaultValue("15s") Duration connectionTimeout,
        @NotNull @DefaultValue("10s") Duration readTimeout) {
}
