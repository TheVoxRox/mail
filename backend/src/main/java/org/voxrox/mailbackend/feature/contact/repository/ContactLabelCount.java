package org.voxrox.mailbackend.feature.contact.repository;

import org.voxrox.mailbackend.feature.contact.EmailLabel;

/**
 * JPQL constructor projection for
 * {@link ContactRepository#countByAccountIdGroupedByLabel(Long)}.
 */
public record ContactLabelCount(EmailLabel label, long contacts) {
}
