package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * PATCH semantics — only non-null fields are applied. If {@code emails} is
 * non-null, it replaces the entire list of contact addresses (replace
 * semantics).
 */
public record ContactPatchRequest(
        @Size(max = 10, message = "{validation.size.max}") @Valid List<ContactEmailRequest> emails,
        @Size(max = 255, message = "{validation.size.max}") String name,
        @Size(max = 255, message = "{validation.size.max}") String surname,
        @Size(max = 1000, message = "{validation.size.max}") String note) {
}
