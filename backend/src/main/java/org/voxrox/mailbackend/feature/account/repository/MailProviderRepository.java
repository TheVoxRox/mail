package org.voxrox.mailbackend.feature.account.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;

@Repository
public interface MailProviderRepository extends JpaRepository<MailProviderEntity, Long> {

    /**
     * Looks up the provider via exact match in the comma-separated domain list.
     * {@code domainKey} must be wrapped in commas (e.g. {@code ",gmail.com,"}) so
     * that {@code mail.com} and {@code gmail.com} are not confused.
     */
    @Query("SELECT p FROM MailProviderEntity p WHERE p.domains LIKE %:domainKey%")
    List<MailProviderEntity> findByDomainKey(@Param("domainKey") String domainKey);

    List<MailProviderEntity> findAllBySystemTemplateTrue();

    Optional<MailProviderEntity> findByName(String name);

    boolean existsByName(String name);
}
