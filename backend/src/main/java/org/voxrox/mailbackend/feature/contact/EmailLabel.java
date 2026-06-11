package org.voxrox.mailbackend.feature.contact;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Label for the contact's email address. Optional — when the client omits it, null is "
        + "stored (no label). Matching is case-insensitive, so both \"home\" and \"HOME\" pass.", example = "HOME", allowableValues = {
                "WORK", "HOME", "OTHER"})
public enum EmailLabel {
    WORK, HOME, OTHER
}
