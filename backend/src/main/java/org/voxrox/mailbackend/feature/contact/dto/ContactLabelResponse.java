package org.voxrox.mailbackend.feature.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A contact label as referenced from a contact. The contact count is not part
 * of this shape — it belongs to the sidebar view
 * ({@link ContactLabelCountResponse}), not to every contact in a list page.
 */
@Schema(description = "User-defined contact label.")
public record ContactLabelResponse(@Schema(example = "12") Long id, @Schema(example = "Rodina") String name) {
}
