package org.voxrox.mailbackend.feature.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a bulk label assignment. {@code changed} counts the contacts whose
 * label set actually differed afterwards — the UI reports that instead of the
 * request size, so re-applying a label the selection already had honestly says
 * "0 changed" rather than claiming work it did not do.
 */
@Schema(description = "Bulk label assignment result.")
public record ContactLabelAssignmentResponse(
        @Schema(description = "Contacts addressed by the request.", example = "12") int total,
        @Schema(description = "Contacts whose label set changed.", example = "9") int changed) {
}
