package org.voxrox.mailbackend.feature.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One sidebar row: a label plus how many contacts carry it. */
@Schema(description = "Contact label with the number of contacts carrying it.")
public record ContactLabelCountResponse(@Schema(example = "12") Long id, @Schema(example = "Rodina") String name,
        @Schema(description = "Number of contacts with this label; matches the size of the list filtered by it.", example = "8") long contacts) {
}
