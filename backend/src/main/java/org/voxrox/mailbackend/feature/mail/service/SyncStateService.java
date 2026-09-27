package org.voxrox.mailbackend.feature.mail.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;
import org.voxrox.mailbackend.util.LogCategory;

import module java.base;

/**
 * Service that manages the synchronization state of individual folders.
 */
@Service
public class SyncStateService {

    private static final Logger log = LoggerFactory.getLogger(SyncStateService.class);

    private final FolderSyncStateRepository syncStateRepository;
    private final AccountRepository accountRepository;

    public SyncStateService(FolderSyncStateRepository syncStateRepository, AccountRepository accountRepository) {
        this.syncStateRepository = syncStateRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Returns the sync state for the given folder with role detection. If the state
     * does not exist, creates it with the detected role. If it exists with a USER
     * role and we now have a better detection, the role is updated.
     *
     * <p>
     * Only ever upward: most callers pass {@code USER} for "not known here" rather
     * than "detected as a user folder", so a USER argument cannot lower a stored
     * role. {@link #reconcileRoles} is what does, from a folder listing.
     */
    @Transactional
    public FolderSyncStateEntity getOrCreateState(Long accountId, String folderName, FolderRole detectedRole) {
        return syncStateRepository.findByAccountIdAndFolderName(accountId, folderName).map(state -> {
            // Update the role only when we have better information than USER
            if (state.getRole() == FolderRole.USER && detectedRole != FolderRole.USER) {
                state.setRole(detectedRole);
                return syncStateRepository.save(state);
            }
            return state;
        }).orElseGet(() -> syncStateRepository.save(
                new FolderSyncStateEntity(accountRepository.getReferenceById(accountId), folderName, detectedRole)));
    }

    /**
     * Brings the stored roles of an account's folders in line with a fresh
     * detection, in both directions, and returns how many it changed.
     *
     * <p>
     * A folder listing detects every folder's role, so unlike
     * {@link #getOrCreateState} it can also lower one: a stored role that the
     * current detection no longer gives — a user folder that an earlier, looser
     * name match took for the trash, or a folder whose SPECIAL-USE attribute the
     * server has since moved — would otherwise stay, and keep deciding where a
     * deleted message goes. Folders missing from {@code detectedRoles} are left
     * alone. Each change is a targeted UPDATE of the role alone (see
     * {@link FolderSyncStateRepository#updateRole}).
     */
    @Transactional
    public int reconcileRoles(Long accountId, Map<String, FolderRole> detectedRoles) {
        int changed = 0;
        for (FolderSyncStateEntity state : syncStateRepository.findByAccountId(accountId)) {
            FolderRole detected = detectedRoles.get(state.getFolderName());
            if (detected != null && detected != state.getRole()) {
                log.info("{} Folder role corrected: account {}, folder {}, {} -> {}", LogCategory.SYNC, accountId,
                        state.getFolderName(), state.getRole(), detected);
                syncStateRepository.updateRole(state.getId(), detected);
                changed++;
            }
        }
        return changed;
    }

    /**
     * The folder's sync state if it has one, without creating it.
     * <p>
     * The read-only twin of {@link #getOrCreateState}, for the caller that needs
     * the state <em>before</em> the folder is opened — the QRESYNC parameters of
     * the SELECT are built from it. Creating the row there instead would leave one
     * behind for every folder name that turns out not to exist on the server.
     */
    @Transactional(readOnly = true)
    public Optional<FolderSyncStateEntity> findState(Long accountId, String folderName) {
        return syncStateRepository.findByAccountIdAndFolderName(accountId, folderName);
    }

    /**
     * Targeted UPDATE of {@code last_known_uid}; bypasses JPA merge and therefore
     * also the {@code @Version} guard. Safe — sync is serialized per (account,
     * folder) via {@code SyncLockManager}.
     */
    @Transactional
    public void updateLastKnownUid(Long syncStateId, Long lastKnownUid) {
        syncStateRepository.updateLastKnownUid(syncStateId, lastKnownUid);
    }

    @Transactional
    public void updateUidValidity(Long syncStateId, Long uidValidity) {
        syncStateRepository.updateUidValidity(syncStateId, uidValidity);
    }

    /**
     * Advances {@code last_known_modseq} to the new folder HIGHESTMODSEQ after a
     * CONDSTORE sync cycle. Like {@link #updateLastKnownUid} it bypasses JPA merge
     * — sync is serialized per (account, folder) via {@code SyncLockManager}.
     */
    @Transactional
    public void updateLastKnownModseq(Long syncStateId, Long modseq) {
        syncStateRepository.updateLastKnownModseq(syncStateId, modseq);
    }

    @Transactional
    public void touchLastSyncAt(Long syncStateId, LocalDateTime when) {
        syncStateRepository.updateLastSyncAt(syncStateId, when);
    }

    /**
     * Resets the state after a server UIDValidity change: new validity + reset of
     * {@code lastKnownUid} to 0 in a single UPDATE.
     */
    @Transactional
    public void resetForUidValidityChange(Long syncStateId, Long newUidValidity) {
        syncStateRepository.resetForUidValidityChange(syncStateId, newUidValidity);
    }
}
