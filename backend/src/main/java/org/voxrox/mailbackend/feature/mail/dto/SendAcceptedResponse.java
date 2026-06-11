package org.voxrox.mailbackend.feature.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of the 202 Accepted returned by the asynchronous send endpoints. The
 * client uses {@code sendId} to correlate the request with the eventual
 * {@code send_completed} / {@code send_failed} event on the SSE stream.
 */
public record SendAcceptedResponse(
        @Schema(description = "Correlation id for tracking the async send outcome over the notification stream.", example = "b3f1c2de-4a5b-6c7d-8e9f-0a1b2c3d4e5f") String sendId) {
}
