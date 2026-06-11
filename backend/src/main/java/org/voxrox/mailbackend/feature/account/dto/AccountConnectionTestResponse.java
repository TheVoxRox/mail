package org.voxrox.mailbackend.feature.account.dto;

public record AccountConnectionTestResponse(boolean imapOk, boolean smtpOk, String message) {
}
