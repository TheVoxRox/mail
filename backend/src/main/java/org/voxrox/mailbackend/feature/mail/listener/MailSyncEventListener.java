package org.voxrox.mailbackend.feature.mail.listener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.voxrox.mailbackend.feature.mail.dto.SyncNotification;
import org.voxrox.mailbackend.feature.mail.dto.SyncStatusNotification;
import org.voxrox.mailbackend.feature.mail.event.MailSyncCompletedEvent;
import org.voxrox.mailbackend.feature.mail.event.MailSyncErrorStateChangedEvent;
import org.voxrox.mailbackend.feature.mail.service.FolderListCache;
import org.voxrox.mailbackend.feature.mail.service.SseNotificationService;
import org.voxrox.mailbackend.util.LogCategory;

@Component
public class MailSyncEventListener {

    private static final Logger log = LoggerFactory.getLogger(MailSyncEventListener.class);

    private final SseNotificationService sseNotificationService;
    private final FolderListCache folderListCache;

    public MailSyncEventListener(SseNotificationService sseNotificationService, FolderListCache folderListCache) {
        this.sseNotificationService = sseNotificationService;
        this.folderListCache = folderListCache;
    }

    /*
     * mailEventExecutor (not mailSyncExecutor) — the publisher is itself a sync
     * task holding a permit and the per-account IMAP lock; submitting back to the
     * sync executor would risk pool-starvation deadlock under load.
     */
    @Async("mailEventExecutor")
    @EventListener
    public void handleSyncCompleted(MailSyncCompletedEvent event) {
        log.info("{} Synchronization completed: account {}, folder {}, new messages: {}", LogCategory.SYNC,
                event.accountId(), event.folderName(), event.newMessagesCount());

        /*
         * Invalidate BEFORE the broadcast: the client reacts to sync_completed with a
         * folder refresh, and that refresh must not be served a pre-sync snapshot from
         * the TTL cache. Unconditional — flag/deletion changes alter unread counts even
         * when no new message arrived.
         */
        folderListCache.invalidate(event.accountId());

        if (event.newMessagesCount() > 0) {
            log.debug("{} {} new messages were automatically indexed by database triggers.", LogCategory.SEARCH,
                    event.newMessagesCount());

            try {
                sseNotificationService.broadcast(new SyncNotification("sync_completed", event.accountId(),
                        event.folderName(), event.newMessagesCount(), event.timestamp()));
            } catch (Exception e) {
                log.warn("{} SSE broadcast failed: {}", LogCategory.SYNC, e.getMessage());
            }
        }
    }

    /**
     * Pushes a changed sync error state to the client. The counterpart of
     * {@code send_failed} on the receive side: until this existed, a persistently
     * failing sync produced no client-visible signal at all — the message list just
     * stopped changing.
     */
    @Async("mailEventExecutor")
    @EventListener
    public void handleErrorStateChanged(MailSyncErrorStateChangedEvent event) {
        String errorCode = event.errorCode();
        SyncStatusNotification notification = errorCode == null
                ? SyncStatusNotification.recovered(event.accountId(), event.timestamp())
                : SyncStatusNotification.failed(event.accountId(), errorCode, event.timestamp());

        log.info("{} Sync error state of account {} changed to {}.", LogCategory.SYNC, event.accountId(),
                errorCode != null ? errorCode : "<clear>");

        try {
            sseNotificationService.broadcast(notification);
        } catch (Exception e) {
            log.warn("{} SSE broadcast failed: {}", LogCategory.SYNC, e.getMessage());
        }
    }
}
