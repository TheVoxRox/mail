package org.voxrox.mailbackend.feature.contact.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.jspecify.annotations.Nullable;

public record ContactResponse(Long id, List<ContactEmailResponse> emails, List<ContactLabelResponse> labels,
        String name, String surname, @Nullable String note, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
