package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.StoredText;

import io.swagger.v3.oas.annotations.media.Schema;

public record ContactCreateRequest(
        @NotEmpty(message = "{validation.contact.emailRequired}") @Size(max = 10, message = "{validation.size.max}") @Valid List<ContactEmailRequest> emails,
        @Size(max = 50, message = "{validation.size.max}") @Schema(description = "Contact labels to attach, by ID. Omit or send an empty list for no labels; an ID that names no contact label is a 404 (CONTACT_LABEL_NOT_FOUND).") @Nullable List<@NotNull(message = "{validation.notNull}") @Positive(message = "{validation.positive}") Long> labelIds,
        @Size(max = 255, message = "{validation.size.max}") @Nullable String name,
        @Size(max = 255, message = "{validation.size.max}") @Nullable String surname,
        @Size(max = 1000, message = "{validation.size.max}") @Nullable String note) {

    /**
     * The text as it is stored ({@link StoredText}), before Bean Validation runs:
     * the names stripped, the note as a block, a blank one as null. The vCard
     * import's bulk create comes through here too.
     */
    public ContactCreateRequest {
        name = StoredText.stripToNull(name);
        surname = StoredText.stripToNull(surname);
        note = StoredText.stripBlankLinesToNull(note);
    }
}
