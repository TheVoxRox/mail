package org.voxrox.mailbackend.feature.mail.dto;

import org.voxrox.mailbackend.exception.ValidationException;

/**
 * Domain message flag that can be set via the REST API. Deliberately unaware of
 * the concrete protocol representation ({@code jakarta.mail.Flags.Flag}, Graph
 * {@code isRead}, JMAP keyword) — mapping to the backend-specific type belongs
 * in the adapter layer (currently {@code ImapActionService}).
 */
public enum MessageFlag {
    SEEN, FLAGGED, ANSWERED;

    public static MessageFlag from(String value) {
        if (value == null) {
            throw new ValidationException("Flag type is missing", "validation.messageFlag.required");
        }
        try {
            return MessageFlag.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Unknown flag type: " + value, "validation.messageFlag.unknown", value);
        }
    }
}
