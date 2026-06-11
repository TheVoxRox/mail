package org.voxrox.mailbackend.feature.mail.entity;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "attachments", indexes = {@Index(name = "idx_attachments_message_id", columnList = "message_id")})
public class AttachmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nullable only transiently: {@code MessageEntity.removeAttachment} unlinks the
     * bidirectional association before orphanRemoval deletes the row — a null is
     * never flushed to the NOT NULL column.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private @Nullable MessageEntity message;

    @Column(name = "part_path", nullable = false)
    private String partPath;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "content_type")
    private String contentType;

    private long size;

    public AttachmentEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public @Nullable MessageEntity getMessage() {
        return message;
    }

    public void setMessage(@Nullable MessageEntity message) {
        this.message = message;
    }

    public String getPartPath() {
        return partPath;
    }

    public void setPartPath(String partPath) {
        this.partPath = partPath;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}
