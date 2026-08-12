package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Counts for the contacts sidebar: the address book total plus one row per
 * contact label. Every label appears, including the ones nobody uses yet
 * (contacts = 0) — the sidebar has to list them regardless, otherwise a freshly
 * created label would be invisible until the first contact lands on it.
 */
@Schema(description = "Contact counts for the sidebar: the address book total plus one row per contact label. "
        + "Labels with no contacts are included with contacts = 0.")
public record ContactCountsResponse(@Schema(example = "137") long total, List<ContactLabelCountResponse> labels) {
}
