package org.voxrox.mailbackend.feature.mail.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;
import org.voxrox.mailbackend.feature.mail.repository.FolderSyncStateRepository;

import module java.base;

/**
 * Service that manages the synchronization state of individual folders.
 */
@Service
public class SyncStateService {

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
     * Saves/updates the existing sync state.
     * <p>
     * Note: in the hot path (handleUidValidity / saveMessagesBatchAtomic /
     * performFullSyncCycle) dedicated {@code update*} methods are used — a detached
     * entity bumped across independent transactions would otherwise throw
     * {@code StaleObjectStateException}. This method remains for the cases where
     * the {@code @Version} guard is desirable (cross-thread save).
     */
    @Transactional
    public void saveSyncState(FolderSyncStateEntity state) {
        syncStateRepository.save(state);
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
