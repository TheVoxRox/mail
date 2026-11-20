package org.voxrox.mailbackend.feature.contact.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * One address book entry. {@code name} and {@code surname} are null for a
 * contact without them, which the address book allows: a contact needs an
 * address, not a name. Typed as nullable since the mapper stores a blank name
 * as null; springdoc does not render jspecify's annotation, so the OpenAPI
 * contract reads the same either way.
 */
public record ContactResponse(Long id, List<ContactEmailResponse> emails, List<ContactLabelResponse> labels,
        @Nullable String name, @Nullable String surname, @Nullable String note, LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
