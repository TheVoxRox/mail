package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.mail.dto.FolderConstants;
import org.voxrox.mailbackend.feature.mail.dto.FolderResponse;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

@Service
public class ImapFolderService {
    private static final Logger log = LoggerFactory.getLogger(ImapFolderService.class);
    private final ImapConnectionManager imapConnectionManager;
    private final ImapFolderExecutor imapFolderExecutor;
    private final FolderSyncStateRepository folderSyncStateRepository;
    private final MessageRepository messageRepository;
    private final FolderListCache folderListCache;
    private final MailClientProperties mailProps;

    public ImapFolderService(ImapConnectionManager imapConnectionManager, ImapFolderExecutor imapFolderExecutor,
            FolderSyncStateRepository folderSyncStateRepository, MessageRepository messageRepository,
            FolderListCache folderListCache, MailClientProperties mailProps) {
        this.imapConnectionManager = imapConnectionManager;
        this.imapFolderExecutor = imapFolderExecutor;
        this.folderSyncStateRepository = folderSyncStateRepository;
        this.messageRepository = messageRepository;
        this.folderListCache = folderListCache;
        this.mailProps = mailProps;
    }

    public <R> @Nullable R executeInFolder(Long accountId, String folderName, int mode, ImapFolderAction<R> action) {
        if (mode == Folder.READ_WRITE) {
            return imapFolderExecutor.executeReadWrite(accountId, folderName, action);
        } else {
            return imapFolderExecutor.executeReadOnly(accountId, folderName, action);
        }
    }

    /**
     * Drops the pooled IMAP connection for the account so the next operation builds
     * a fresh one. Used by the sync retry path after a transient connectivity
     * failure: the dead/half-open store that produced "failed to create new store
     * connection" must be discarded before retrying, otherwise the liveness probe
     * in {@link ImapConnectionManager#getConnectedStore(Long)} may keep handing the
     * same broken connection back.
     */
    public void invalidateConnection(Long accountId) {
        imapConnectionManager.removeConnection(accountId);
    }

    public List<FolderResponse> getFolders(Long accountId) {
        /*
         * TTL cache first — a cold listing is one IMAP LIST plus one STATUS per folder,
         * serialized behind the per-account connection lock. The sidebar refresh after
         * sync, the post-delete/move refresh and the per-message target validation of a
         * bulk move all land here; see FolderListCache for the freshness/invalidation
         * contract.
         */
        Optional<List<FolderResponse>> cached = folderListCache.get(accountId);
        if (cached.isPresent()) {
            return cached.get();
        }

        // The action always returns a (possibly empty) list or throws — it never
        // yields null, so the nullable executor result can be required here.
        List<FolderResponse> fresh = java.util.Objects.requireNonNull(
                imapConnectionManager.executeWithLock(accountId, store -> listFolders(accountId, store)));
        folderListCache.put(accountId, fresh);
        return fresh;
    }

    /**
     * Folder listing that gives up instead of queuing behind a running sync.
     * <p>
     * {@link #getFolders(Long)} waits on the per-account connection lock, which a
     * background sync holds for a whole folder cycle — tens of seconds on a large
     * mailbox. That is fine for the sync path, but a read request that only needs
     * to know which folder is the trash must not block the user's message list on
     * it. An empty result means "could not resolve right now"; the caller decides
     * how to degrade, and the TTL cache means this is at most one cold lookup.
     */
    public Optional<List<FolderResponse>> getFoldersWithinTimeout(Long accountId, Duration timeout) {
        Optional<List<FolderResponse>> cached = folderListCache.get(accountId);
        if (cached.isPresent()) {
            return cached;
        }

        Optional<List<FolderResponse>> fresh = imapConnectionManager.executeWithLockOrSkip(accountId, timeout,
                store -> listFolders(accountId, store));
        fresh.ifPresent(folders -> folderListCache.put(accountId, folders));
        return fresh;
    }

    private List<FolderResponse> listFolders(Long accountId, Store store) throws MessagingException {
        log.debug("{} Listing folders for account {}", LogCategory.IMAP, accountId);
        Folder defaultFolder = store.getDefaultFolder();
        Folder[] listed = defaultFolder.list("*");

        // Drop \Noselect containers (e.g. Gmail's "[Gmail]" parent node). They have
        // HOLDS_FOLDERS but not HOLDS_MESSAGES — SELECT on them fails, so surfacing
        // them in the sidebar would only lead to a click-to-error.
        List<Folder> folders = new ArrayList<>(listed.length);
        for (Folder folder : listed) {
            if (isSelectable(folder)) {
                folders.add(folder);
            }
        }

        /*
         * Two-pass role detection. Pass 1 assigns the "strong" role from INBOX,
         * NEWSLETTERS-by-name (seznam.cz quirk), or RFC 6154 SPECIAL-USE attributes —
         * no name fallback yet. Pass 2 applies the name fallback only for roles not
         * already claimed in pass 1, so a stray user label like "[Gmail]Koš" or
         * "Archiv 2024" cannot impersonate the system Trash/Archive when the real one
         * was detected via SPECIAL-USE.
         */
        FolderRole[] primary = new FolderRole[folders.size()];
        EnumSet<FolderRole> claimed = EnumSet.noneOf(FolderRole.class);
        for (int i = 0; i < folders.size(); i++) {
            primary[i] = detectPrimaryRole(folders.get(i));
            if (primary[i] != FolderRole.USER) {
                claimed.add(primary[i]);
            }
        }

        List<FolderResponse> result = new ArrayList<>(folders.size());
        for (int i = 0; i < folders.size(); i++) {
            Folder folder = folders.get(i);
            FolderRole role = primary[i];
            if (role == FolderRole.USER) {
                FolderRole byName = FolderRole.fromNameFallback(folder.getFullName());
                if (byName != FolderRole.USER && !claimed.contains(byName)) {
                    role = byName;
                }
            }
            result.add(buildResponse(folder, accountId, role));
        }
        return result;
    }

    private boolean isSelectable(Folder folder) {
        try {
            return (folder.getType() & Folder.HOLDS_MESSAGES) != 0;
        } catch (MessagingException e) {
            // Type unavailable — assume selectable so a transient error can't hide
            // real folders from the sidebar.
            log.trace("{} Could not determine folder type for {}; assuming selectable.", LogCategory.IMAP,
                    folder.getFullName(), e);
            return true;
        }
    }

    /**
     * Pass-1 role detection: INBOX → NEWSLETTERS-by-name override → SPECIAL-USE
     * attribute. Returns {@link FolderRole#USER} when nothing matches and a name
     * fallback may apply in pass 2.
     */
    private FolderRole detectPrimaryRole(Folder folder) {
        String fullName = folder.getFullName();
        if (FolderConstants.INBOX.equalsIgnoreCase(fullName)) {
            return FolderRole.INBOX;
        }
        // Name-based NEWSLETTERS wins over SPECIAL-USE — seznam.cz tags its
        // "newsletters" folder with \Junk, but it isn't really spam. NEWSLETTERS
        // isn't in RFC 6154 anyway, so the explicit folder name is the stronger
        // signal.
        if (FolderRole.fromNameFallback(fullName) == FolderRole.NEWSLETTERS) {
            return FolderRole.NEWSLETTERS;
        }
        return detectRoleFromAttributes(folder);
    }

    private FolderResponse buildResponse(Folder folder, Long accountId, FolderRole role) {
        String fullName = folder.getFullName();
        int unreadCount = resolveUnreadCount(folder, accountId, fullName);
        return new FolderResponse(folder.getName(), fullName, unreadCount, role);
    }

    /**
     * Finds the folder name for an RFC 6154 SPECIAL-USE role. Looks in the DB first
     * (fast), and only as a fallback queries the IMAP server.
     *
     * Returns an empty {@link Optional} if no folder exists for the given role. The
     * caller must handle this explicitly — no blind hardcoded fallbacks like
     * "Drafts"/"Sent" (localization, edge cases like "[Gmail]/Sent Mail" etc.).
     * Exception: {@link FolderRole#INBOX} always resolves to
     * {@link FolderConstants#INBOX}, which is guaranteed by RFC 3501.
     */
    public Optional<String> findFolderNameByRole(Long accountId, FolderRole role) {
        List<String> names = findFolderNamesByRole(accountId, role);
        return names.isEmpty() ? Optional.empty() : Optional.of(names.getFirst());
    }

    /**
     * Every folder name carrying {@code role}, same DB-then-IMAP resolution as
     * {@link #findFolderNameByRole(Long, FolderRole)}. Callers that treat the role
     * as a <em>set</em> to skip — the conversation listing excluding trash, junk
     * and drafts from its cross-folder counts — need all of them: role detection is
     * not unique (see {@code FolderSyncStateRepository.findFolderNamesByRole}), and
     * skipping only the first would let a second trash folder leak back into live
     * conversations. Returns an empty list when the account has no folder for the
     * role.
     */
    /**
     * Read-path variant of {@link #findFolderNamesByRole(Long, FolderRole)} that
     * never queues behind a running sync.
     *
     * <p>
     * The DB lookup answers this in the overwhelming majority of calls; the IMAP
     * fallback only fires when the sync has not recorded the folder yet — first
     * visit on a fresh account, which is exactly when a long initial sync is
     * holding the connection lock. Blocking there would stall the user's message
     * list on a lookup that only decides which folders to exclude from a count.
     *
     * <p>
     * The empty {@link Optional} is deliberately distinct from an empty list: "the
     * account has no trash folder" and "could not find out" lead to opposite
     * decisions in the conversation listing, and conflating them would silently
     * promote the trash into cross-folder conversations.
     */
    public Optional<List<String>> findFolderNamesByRoleWithoutWaiting(Long accountId, FolderRole role) {
        List<String> fromDb = folderSyncStateRepository.findFolderNamesByRole(accountId, role);
        if (!fromDb.isEmpty()) {
            return Optional.of(fromDb);
        }

        return getFoldersWithinTimeout(accountId, mailProps.imap().roleLookupTimeout()).map(folders -> {
            List<String> fromImap = folders.stream().filter(f -> f.role() == role).map(FolderResponse::folderRef)
                    .toList();
            if (!fromImap.isEmpty()) {
                return fromImap;
            }
            return role == FolderRole.INBOX ? List.of(FolderConstants.INBOX) : List.<String>of();
        });
    }

    public List<String> findFolderNamesByRole(Long accountId, FolderRole role) {
        List<String> fromDb = folderSyncStateRepository.findFolderNamesByRole(accountId, role);
        if (!fromDb.isEmpty()) {
            return fromDb;
        }

        List<String> fromImap = getFolders(accountId).stream().filter(f -> f.role() == role)
                .map(FolderResponse::folderRef).toList();
        if (!fromImap.isEmpty()) {
            return fromImap;
        }

        if (role == FolderRole.INBOX) {
            return List.of(FolderConstants.INBOX);
        }
        return List.of();
    }

    /**
     * Like {@link #findFolderNameByRole(Long, FolderRole)} but throws
     * {@link MailOperationException} with {@link ErrorCode#FOLDER_ROLE_NOT_FOUND}
     * when no folder is detected for the role. Used by call sites that cannot
     * proceed without the folder (send a draft, move to trash, archive a sent
     * message), so the resolve-or-fail decision and its English fallback message
     * live here once instead of being duplicated per caller. The fallback text only
     * surfaces when client-side i18n fails — the frontend resolves user copy from
     * the errorCode.
     */
    public String findFolderNameByRoleOrThrow(Long accountId, FolderRole role) {
        return findFolderNameByRole(accountId, role)
                .orElseThrow(() -> new MailOperationException(ErrorCode.FOLDER_ROLE_NOT_FOUND,
                        "Account " + accountId + " has no detectable folder for role " + role + "."));
    }

    private int resolveUnreadCount(Folder folder, Long accountId, String folderName) {
        try {
            int serverUnread = folder.getUnreadMessageCount();
            if (serverUnread >= 0) {
                return serverUnread;
            }
        } catch (MessagingException e) {
            log.trace("{} IMAP server did not return an unread count for folder {}, using local DB fallback.",
                    LogCategory.IMAP, folderName, e);
        }

        long localUnread = messageRepository.countByAccountIdAndFolderNameAndSeenFalse(accountId, folderName);
        return localUnread > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) localUnread;
    }

    /**
     * Detects the folder role from RFC 6154 SPECIAL-USE attributes via reflection.
     */
    private FolderRole detectRoleFromAttributes(Folder folder) {
        try {
            Method getAttributesMethod = folder.getClass().getMethod("getAttributes");
            String[] attrs = (String[]) getAttributesMethod.invoke(folder);

            if (attrs != null) {
                for (String attr : attrs) {
                    FolderRole role = FolderRole.fromAttribute(attr);
                    if (role != FolderRole.USER)
                        return role;
                }
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // Folder does not implement the IMAP extension or is disconnected — fall back
            // to USER.
            log.trace("{} SPECIAL-USE attributes are not available for the folder.", LogCategory.IMAP);
        }
        return FolderRole.USER;
    }
}
