package org.voxrox.mailbackend.feature.account.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.voxrox.mailbackend.feature.account.entity.AccountCredentialEntity;

@Repository
public interface AccountCredentialRepository extends JpaRepository<AccountCredentialEntity, Long> {
}
