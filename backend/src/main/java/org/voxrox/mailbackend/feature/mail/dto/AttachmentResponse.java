package org.voxrox.mailbackend.feature.mail.dto;

public record AttachmentResponse(String partPath, String fileName, String contentType, long size) {

    public AttachmentResponse {
        if (fileName == null || fileName.isBlank()) {
            fileName = "unnamed";
        } else {
            fileName = fileName.replaceAll("[\\p{Cntrl}\"]", "");
        }

        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }
        size = Math.max(0, size);
    }

    public static AttachmentResponse fromEntity(org.voxrox.mailbackend.feature.mail.entity.AttachmentEntity entity) {
        return new AttachmentResponse(entity.getPartPath(), entity.getFileName(), entity.getContentType(),
                entity.getSize());
    }
}
