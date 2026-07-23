package org.voxrox.mailbackend.feature.mail.repository;

import org.jspecify.annotations.Nullable;

/**
 * Lightweight projection for the References backfill — just the message row id
 * and its raw References header, so the pass never loads the {@code @Lob} body
 * of every message with references (which would not fit the 384m heap on a
 * populated account). See
 * {@code ThreadingBackfillService.backfillReferencesAccount}.
 */
public interface MessageReferenceBackfillRow {

    Long getId();

    @Nullable
    String getRefs();
}
