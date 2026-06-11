package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record ContactCreateRequest(
        @NotEmpty(message = "{validation.contact.emailRequired}") @Size(max = 10, message = "{validation.size.max}") @Valid List<ContactEmailRequest> emails,
        @Size(max = 255, message = "{validation.size.max}") String name,
        @Size(max = 255, message = "{validation.size.max}") String surname,
        @Size(max = 1000, message = "{validation.size.max}") String note) {
}
