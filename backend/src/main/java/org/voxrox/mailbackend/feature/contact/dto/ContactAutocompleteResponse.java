package org.voxrox.mailbackend.feature.contact.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.feature.contact.AutocompleteSource;
import org.voxrox.mailbackend.feature.contact.EmailLabel;

/**
 * One compose-window suggestion.
 *
 * <p>
 * Two shapes behind one record, told apart by {@link #source}:
 * <ul>
 * <li>{@code CONTACT} — an address book entry; every field is populated.</li>
 * <li>{@code HISTORY} — an address harvested from message headers, with no
 * contact identity behind it, so {@code contactId}, {@code emailId},
 * {@code label} and {@code primary} are null. The name last seen in the header
 * lands in {@code name} with {@code surname} left null: a display name is one
 * free-text field and splitting it into given/family name would be a guess,
 * wrong for every "Novak, Jan" and every mononym.</li>
 * </ul>
 */
public record ContactAutocompleteResponse(
        @Schema(description = "Address book entry this address belongs to. Null on a HISTORY row.", nullable = true) @Nullable Long contactId,
        @Schema(description = "Identifier of the stored address. Null on a HISTORY row.", nullable = true) @Nullable Long emailId,
        String email,
        @Schema(description = "Address label (WORK/HOME/OTHER). Null on a HISTORY row and on a contact address with no label.", nullable = true) @Nullable EmailLabel label,
        @Schema(description = "Whether this is the contact's primary address. Null on a HISTORY row.", nullable = true) @Nullable Boolean primary,
        @Schema(description = "Given name for a CONTACT row; on a HISTORY row the whole display name last seen in a header.", nullable = true) @Nullable String name,
        @Schema(description = "Family name. Always null on a HISTORY row — a header display name is not split.", nullable = true) @Nullable String surname,
        AutocompleteSource source) {

    public static ContactAutocompleteResponse ofContact(Long contactId, Long emailId, String email,
            @Nullable EmailLabel label, boolean primary, @Nullable String name, @Nullable String surname) {
        return new ContactAutocompleteResponse(contactId, emailId, email, label, primary, name, surname,
                AutocompleteSource.CONTACT);
    }

    public static ContactAutocompleteResponse ofHistory(String email, @Nullable String displayName) {
        return new ContactAutocompleteResponse(null, null, email, null, null, displayName, null,
                AutocompleteSource.HISTORY);
    }
}
