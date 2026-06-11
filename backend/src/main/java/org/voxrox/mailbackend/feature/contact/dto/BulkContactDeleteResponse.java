package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

public record BulkContactDeleteResponse(int total, int deleted, int failed, List<BulkContactDeleteResult> results) {

    public record BulkContactDeleteResult(Long id, Status status, String errorCode, String errorMessage) {

        public enum Status {
            DELETED, FAILED
        }

        public static BulkContactDeleteResult success(Long id) {
            return new BulkContactDeleteResult(id, Status.DELETED, null, null);
        }

        public static BulkContactDeleteResult failure(Long id, String errorCode, String errorMessage) {
            return new BulkContactDeleteResult(id, Status.FAILED, errorCode, errorMessage);
        }
    }
}
