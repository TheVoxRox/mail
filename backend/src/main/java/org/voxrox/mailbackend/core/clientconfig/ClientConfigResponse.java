package org.voxrox.mailbackend.core.clientconfig;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Safe runtime limits and recommendations for the desktop client.", requiredProperties = {
        "mailDefaultPageSize", "mailApiMaxPageSize", "searchQueryMaxLength", "contactDefaultPageSize",
        "contactQueryMaxLength", "contactAutocompleteDefaultLimit", "contactAutocompleteMaxLimit", "attachmentMaxBytes",
        "attachmentTotalMaxBytes", "largeAttachmentWarningBytes"})
public record ClientConfigResponse(
        @Schema(description = "Default page size for message listings.", example = "50", requiredMode = Schema.RequiredMode.REQUIRED) int mailDefaultPageSize,
        @Schema(description = "Maximum allowed page size for paginated mail endpoints.", example = "200", requiredMode = Schema.RequiredMode.REQUIRED) int mailApiMaxPageSize,
        @Schema(description = "Maximum length of the message full-text query.", example = "256", requiredMode = Schema.RequiredMode.REQUIRED) int searchQueryMaxLength,
        @Schema(description = "Default page size for contacts.", example = "20", requiredMode = Schema.RequiredMode.REQUIRED) int contactDefaultPageSize,
        @Schema(description = "Maximum query length for contacts and autocomplete.", example = "100", requiredMode = Schema.RequiredMode.REQUIRED) int contactQueryMaxLength,
        @Schema(description = "Default row limit for contact autocomplete.", example = "10", requiredMode = Schema.RequiredMode.REQUIRED) int contactAutocompleteDefaultLimit,
        @Schema(description = "Maximum row limit for contact autocomplete.", example = "20", requiredMode = Schema.RequiredMode.REQUIRED) int contactAutocompleteMaxLimit,
        @Schema(description = "Recommended client-side limit for a single attachment in bytes.", example = "10485760", requiredMode = Schema.RequiredMode.REQUIRED) long attachmentMaxBytes,
        @Schema(description = "Recommended client-side limit for all attachments in a single message in bytes.", example = "26214400", requiredMode = Schema.RequiredMode.REQUIRED) long attachmentTotalMaxBytes,
        @Schema(description = "Byte threshold above which the client warns about a large attachment.", example = "5242880", requiredMode = Schema.RequiredMode.REQUIRED) long largeAttachmentWarningBytes) {
}
