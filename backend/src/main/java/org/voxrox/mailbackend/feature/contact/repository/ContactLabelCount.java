package org.voxrox.mailbackend.feature.contact.repository;

/**
 * JPQL constructor projection for
 * {@link ContactRepository#countGroupedByLabel()} — how many contacts carry the
 * label with the given ID.
 */
public record ContactLabelCount(Long labelId, long contacts) {
}
