package org.voxrox.mailbackend.feature.mail.repository;

import org.jspecify.annotations.Nullable;

/**
 * Lightweight projection for the subject-norm backfill — just the message row
 * id and its raw subject, so the pass never loads the {@code @Lob} body of
 * every message (which would not fit the 384m heap on a populated account). See
 * {@code ThreadingBackfillService}.
 */
public interface SubjectNormBackfillRow {

    Long getId();

    @Nullable
    String getSubject();
}
