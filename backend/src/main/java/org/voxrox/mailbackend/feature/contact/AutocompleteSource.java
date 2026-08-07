package org.voxrox.mailbackend.feature.contact;

/**
 * Where a compose-window suggestion came from.
 *
 * <p>
 * The client shows the distinction — a history row is offered but is not in the
 * address book, and saying so is what stops the typeahead from looking like the
 * address book silently grew. Ranking deliberately does not treat the two as
 * separate blocks: a strong history match outranks a weak contact match,
 * because the user is looking for an address, not for a data source.
 */
public enum AutocompleteSource {

    /** A hand-curated address book entry. */
    CONTACT,

    /**
     * An address harvested from message headers ({@code correspondent}). Carries no
     * contact identity, so {@code contactId}, {@code emailId}, {@code label} and
     * {@code primary} are all null on such a row.
     */
    HISTORY
}
