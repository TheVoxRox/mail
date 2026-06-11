package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

public record BulkContactCreateResponse(int total, int created, int failed, List<BulkContactCreateResult> results) {

    public record BulkContactCreateResult(int index, Status status, ContactResponse contact, String errorCode,
            String errorMessage) {

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
