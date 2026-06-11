package org.voxrox.mailbackend.core.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;
import org.voxrox.mailbackend.core.config.mail.ImapProperties;
import org.voxrox.mailbackend.core.config.mail.RetryProperties;
import org.voxrox.mailbackend.core.config.mail.SmtpProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;

@ConfigurationProperties(prefix = "mail.client")
@Validated
public record MailClientProperties(@NestedConfigurationProperty @Valid ImapProperties imap,

        @NestedConfigurationProperty @Valid SmtpProperties smtp,

        @NotNull @NestedConfigurationProperty @Valid SyncProperties sync,

        @NotNull @NestedConfigurationProperty @Valid RetryProperties retry) {
}
