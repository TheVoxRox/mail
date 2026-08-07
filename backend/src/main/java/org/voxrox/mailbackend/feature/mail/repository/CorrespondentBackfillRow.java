package org.voxrox.mailbackend.feature.mail.repository;

import java.time.LocalDateTime;

import org.jspecify.annotations.Nullable;

/**
 * Lightweight projection for the correspondent backfill — the address headers of
 * one message plus what the pass needs to place it (folder, date, cursor id).
 *
 * <p>
 * A projection rather than {@code MessageEntity} for the same reason
 * {@link SubjectNormBackfillRow} is one: the entity carries the {@code @Lob}
 * body, and a sweep over a populated account would drag every message body
 * through the 384m heap to read a header field. See
 * {@code CorrespondentBackfillService}.
 */
public interface CorrespondentBackfillRow {

    Long getId();

    String getFolderName();

    @Nullable
    String getSender();

    @Nullable
    String getRecipientsTo();

    @Nullable
    String getRecipientsCc();

    @Nullable
    String getRecipientsBcc();

    LocalDateTime getReceivedAt();
}
