package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record BulkContactCreateRequest(
        @NotEmpty(message = "{validation.contacts.listRequired}") @Size(max = 100, message = "{validation.contacts.max100}") @Valid List<ContactCreateRequest> contacts) {
}
