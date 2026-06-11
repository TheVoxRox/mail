package org.voxrox.mailbackend.core.config.mail;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record ImapProperties(@Min(1) @Max(65535) @DefaultValue("993") int defaultPort,
        @NotNull @DefaultValue("30s") Duration connectionTimeout, @NotNull @DefaultValue("60s") Duration readTimeout,
        @NotBlank @DefaultValue("imaps") String protocolSsl, @NotBlank @DefaultValue("imap") String protocolStandard) {
}
