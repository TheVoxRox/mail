package org.voxrox.mailbackend.feature.mail.repository;

import java.time.LocalDateTime;

import org.jspecify.annotations.Nullable;

/**
 * Lightweight projection for the correspondent backfill — the address headers
 * of one message plus what the pass needs to place it (folder, date, cursor
 * id).
 *
 * <p>
 * A projection rather than {@code MessageEntity} for the same reason
 * {@link SubjectNormBackfillRow} is one: the entity carries the {@code @Lob}
 * body, and a sweep over a populated account would drag every message body
 * through the 384m heap to read a header field.
 *
 * <p>
 * A JPQL constructor projection rather than the native-query interface
 * projection the other backfill rows use, because this one carries a
 * {@code LocalDateTime}. Over a native query the SQLite driver reports
 * {@code received_at} as a {@code LocalDate} and the projection fails with
 * "Cannot project java.time.LocalDate to java.time.LocalDateTime" — at runtime,
 * on the startup pass. JPQL takes the type from the entity mapping, where the
 * column is already declared as a {@code LocalDateTime}, so the conversion is
 * the same one every other read of {@code messages} performs. See
 * {@code CorrespondentBackfillIT}.
 */
public record CorrespondentBackfillRow(Long id, String folderName, @Nullable String sender,
        @Nullable String recipientsTo, @Nullable String recipientsCc, @Nullable String recipientsBcc,
        LocalDateTime receivedAt) {
}
