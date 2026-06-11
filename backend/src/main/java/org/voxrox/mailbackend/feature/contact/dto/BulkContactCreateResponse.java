package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import org.jspecify.annotations.Nullable;

public record BulkContactCreateResponse(int total, int created, int failed, List<BulkContactCreateResult> results) {

    public record BulkContactCreateResult(int index, Status status, @Nullable ContactResponse contact,
            @Nullable String errorCode, @Nullable String errorMessage) {

        public enum Status {
            CREATED, FAILED
        }

        public static BulkContactCreateResult success(int index, ContactResponse contact) {
            return new BulkContactCreateResult(index, Status.CREATED, contact, null, null);
        }

        public static BulkContactCreateResult failure(int index, String errorCode, String errorMessage) {
            return new BulkContactCreateResult(index, Status.FAILED, null, errorCode, errorMessage);
        }
    }
}
