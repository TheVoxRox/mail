package org.voxrox.mailbackend.feature.mail.service;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.mail.dto.DraftRequest;

/**
 * The content of each accepted draft save whose APPEND has not started, by the
 * stableId the save was accepted under. Held here rather than by the save's
 * task, which would keep it for as long as the task waits: the 202 returns
 * before the APPEND, the APPEND waits for the account's background lane, and a
 * sync or a slow upload on that lane queued the user's autosaves behind it,
 * each holding the attachments it carried, some 66 MB for 25 MB attached, until
 * the packaged 384 MiB heap ran out at the fifth (measured, IMAP/SMTP audit
 * B1-25).
 *
 * <p>
 * A save of a draft drops the content of the saves it follows when it is
 * accepted ({@link DraftPersistenceService#acceptDraftSave}), and a send drops
 * the content of the draft it sent, so a draft holds the content of its newest
 * waiting save and of the one being appended, whatever the queue. A task that
 * comes for content no longer here appends nothing.
 */
@Component
class WaitingDraftSaves {

    private final ConcurrentMap<String, DraftRequest> requests = new ConcurrentHashMap<>();

    void hold(String stableId, DraftRequest request) {
        requests.put(stableId, request);
    }

    void drop(Collection<String> stableIds) {
        stableIds.forEach(requests::remove);
    }

    /**
     * The content to append for {@code stableId}, handed out once; {@code null}
     * when a later save of the draft, or a send of it, dropped it.
     */
    @Nullable
    DraftRequest take(String stableId) {
        return requests.remove(stableId);
    }
}
