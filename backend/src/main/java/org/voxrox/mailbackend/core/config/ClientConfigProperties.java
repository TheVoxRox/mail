package org.voxrox.mailbackend.core.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "mail.client-config")
@Validated
public record ClientConfigProperties(@Min(1) @DefaultValue("20") int contactDefaultPageSize,
        @Min(1) @DefaultValue("100") int contactQueryMaxLength,
        @Min(1) @DefaultValue("10") int contactAutocompleteDefaultLimit,
        @Min(1) @DefaultValue("20") int contactAutocompleteMaxLimit,
        @Min(1) @DefaultValue("10485760") long attachmentMaxBytes,
        @Min(1) @DefaultValue("26214400") long attachmentTotalMaxBytes,
        @Min(1) @DefaultValue("5242880") long largeAttachmentWarningBytes) {

    @AssertTrue(message = "contactAutocompleteDefaultLimit must not exceed contactAutocompleteMaxLimit")
    public boolean isAutocompleteDefaultWithinMax() {
        return contactAutocompleteDefaultLimit <= contactAutocompleteMaxLimit;
    }

    @AssertTrue(message = "attachmentMaxBytes must not exceed attachmentTotalMaxBytes")
    public boolean isAttachmentMaxWithinTotal() {
        return attachmentMaxBytes <= attachmentTotalMaxBytes;
    }

    @AssertTrue(message = "largeAttachmentWarningBytes must not exceed attachmentMaxBytes")
    public boolean isLargeAttachmentWarningWithinMax() {
        return largeAttachmentWarningBytes <= attachmentMaxBytes;
    }
}
