package org.voxrox.mailbackend.feature.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of the 202 returned by the draft-save endpoint. The stableId is
 * deterministic (derived from the Message-ID assigned to the draft before the
 * async append is dispatched), so the client can reference the draft — e.g.
 * {@code ?replaces=} on the next revision or {@code ?draft=} reopen — without
 * waiting for a sync or listing the folder.
 */
public record DraftSaveAcceptedResponse(
        @Schema(description = "Stable identifier the saved draft persists under. Valid immediately for replaces= "
                + "chaining; the row itself appears as soon as the async append completes.") String stableId) {
}
