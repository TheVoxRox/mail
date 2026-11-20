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
     * All labels in display order. Sorting by the folded name (not by {@code name})
     * keeps "rodina" next to "Rodina" and a label starting with an S with a caron
     * among the S labels — SQLite orders plain TEXT by code point, which put both
     * after every upper-case ASCII name. {@code nameKey} breaks the tie between two
     * labels that fold the same (they differ by a diacritic alone), so the order is
     * stable.
     */
    List<ContactLabelEntity> findAllByOrderByNameFoldAscNameKeyAsc();

    Optional<ContactLabelEntity> findByNameKey(String nameKey);

    /** Resolves the label IDs of a create/update request. */
    List<ContactLabelEntity> findByIdIn(Collection<Long> ids);
}
