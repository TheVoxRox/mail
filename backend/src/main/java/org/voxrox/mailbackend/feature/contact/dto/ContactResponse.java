package org.voxrox.mailbackend.feature.contact.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;

public record ContactResponse(Long id, List<ContactEmailResponse> emails, String name, String surname,
        @Nullable String note, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
