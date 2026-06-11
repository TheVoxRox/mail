package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.Folder;
import jakarta.mail.UIDFolder;

import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.entity.FolderSyncStateEntity;

public record FolderSyncContext(AccountEntity account, String folderName, Folder folder, UIDFolder uidFolder,
        FolderSyncStateEntity syncState) {

    public Long getAccountId() {
        return account.getId();
    }
}
