package org.voxrox.mailbackend.feature.contact.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;

@Repository
public interface ContactLabelRepository extends JpaRepository<ContactLabelEntity, Long> {

    /**
     * All labels of an account in display order. Sorting by the case-folded key
     * (not by {@code name}) keeps "rodina" next to "Rodina" instead of after every
     * upper-case name — SQLite orders plain TEXT by code point.
     */
    List<ContactLabelEntity> findByAccountIdOrderByNameKeyAsc(Long accountId);

    Optional<ContactLabelEntity> findByIdAndAccountId(Long id, Long accountId);

    Optional<ContactLabelEntity> findByAccountIdAndNameKey(Long accountId, String nameKey);

    /**
     * Resolves the label IDs of a create/update request. Scoped by account on
     * purpose: an ID belonging to another account simply does not come back, and
     * the caller reports it as unknown rather than silently attaching a foreign
     * label.
     */
    List<ContactLabelEntity> findByAccountIdAndIdIn(Long accountId, Collection<Long> ids);

    long countByAccountId(Long accountId);
}
