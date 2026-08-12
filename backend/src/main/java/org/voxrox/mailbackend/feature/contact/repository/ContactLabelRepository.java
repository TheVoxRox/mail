package org.voxrox.mailbackend.feature.contact.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.voxrox.mailbackend.feature.contact.entity.ContactLabelEntity;

/**
 * Labels group entries of the one application-wide address book, so — like
 * {@link ContactRepository} — none of these lookups take an account.
 */
@Repository
public interface ContactLabelRepository extends JpaRepository<ContactLabelEntity, Long> {

    /**
     * All labels in display order. Sorting by the case-folded key (not by
     * {@code name}) keeps "rodina" next to "Rodina" instead of after every
     * upper-case name — SQLite orders plain TEXT by code point.
     */
    List<ContactLabelEntity> findAllByOrderByNameKeyAsc();

    Optional<ContactLabelEntity> findByNameKey(String nameKey);

    /** Resolves the label IDs of a create/update request. */
    List<ContactLabelEntity> findByIdIn(Collection<Long> ids);
}
