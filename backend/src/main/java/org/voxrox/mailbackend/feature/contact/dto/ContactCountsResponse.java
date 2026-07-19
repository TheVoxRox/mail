package org.voxrox.mailbackend.feature.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Contact counts for the sidebar: the account total plus per-label counts. A contact is "
        + "counted for a label when at least one of its e-mail addresses bears it, so each figure matches the "
        + "size of the list filtered by the same label.")
public record ContactCountsResponse(long total, long work, long home, long other) {
}
