package org.voxrox.mailbackend.feature.mail.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.dto.FolderRole;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Entity
@Table(name = "folder_sync_state", uniqueConstraints = {
        @UniqueConstraint(name = "uk_account_folder", columnNames = {"account_id", "folder_name"})})
public class FolderSyncStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler", "messages", "syncStates"})
    private AccountEntity account;

    @Column(name = "folder_name", nullable = false)
    private String folderName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private FolderRole role = FolderRole.USER;

    @Column(name = "last_known_uid")
    private Long lastKnownUid;

    @Column(name = "uid_validity")
    private Long uidValidity;

    /**
     * Folder HIGHESTMODSEQ from the last CONDSTORE-aware synchronization. NULL =
     * the server does not advertise CONDSTORE capability, or the CONDSTORE path has
     * not yet been used with this account (first cycle after deploy). When
     * non-null, sync runs through {@code CHANGEDSINCE} / QRESYNC instead of a full
     * UID sweep.
     */
    @Column(name = "last_known_modseq")
    private @Nullable Long lastKnownModseq;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Version
    private Integer version;

    public FolderSyncStateEntity() {
    }

    public FolderSyncStateEntity(AccountEntity account, String folderName, FolderRole role) {
        this.account = account;
        this.folderName = folderName;
        this.role = Objects.requireNonNullElse(role, FolderRole.USER);
        this.lastKnownUid = 0L;
    }

    public FolderSyncStateEntity(AccountEntity account, String folderName) {
        this(account, folderName, FolderRole.USER);
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof FolderSyncStateEntity other))
            return false;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "FolderSyncState{" + "id=" + id + ", folder='" + folderName + '\'' + ", role=" + role + ", lastUid="
                + lastKnownUid + ", validity=" + uidValidity + ", modseq=" + lastKnownModseq + ", lastSync="
                + lastSyncAt + ", ver=" + version + '}';
    }

    public Long getId() {
        return id;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public void setAccount(AccountEntity account) {
        this.account = account;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    public FolderRole getRole() {
        return role;
    }

    public void setRole(FolderRole role) {
        this.role = role;
    }

    public Long getLastKnownUid() {
        return lastKnownUid;
    }

    public void setLastKnownUid(Long lastKnownUid) {
        this.lastKnownUid = lastKnownUid;
    }

    public Long getUidValidity() {
        return uidValidity;
    }

    public void setUidValidity(Long uidValidity) {
        this.uidValidity = uidValidity;
    }

    public @Nullable Long getLastKnownModseq() {
        return lastKnownModseq;
    }

    public void setLastKnownModseq(@Nullable Long lastKnownModseq) {
        this.lastKnownModseq = lastKnownModseq;
    }

    public LocalDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(LocalDateTime lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public Integer getVersion() {
        return version;
    }
}
