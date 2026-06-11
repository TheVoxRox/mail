package org.voxrox.mailbackend.feature.contact.dto;

import org.voxrox.mailbackend.feature.contact.EmailLabel;

public record ContactAutocompleteResponse(Long contactId, Long emailId, String email, EmailLabel label, boolean primary,
        String name, String surname) {
}
