package org.voxrox.mailbackend.feature.mail.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.entity.RemoteImageSenderEntity;

@Repository
public interface RemoteImageSenderRepository extends JpaRepository<RemoteImageSenderEntity, Long> {

    boolean existsByAccountIdAndSenderEmail(Long accountId, String senderEmail);

    @Transactional
    @Modifying
    void deleteByAccountIdAndSenderEmail(Long accountId, String senderEmail);

    @Query("SELECT r.senderEmail FROM RemoteImageSenderEntity r WHERE r.account.id = :accountId "
            + "ORDER BY r.senderEmail ASC")
    List<String> findSenderEmailsByAccountId(@Param("accountId") Long accountId);
}
