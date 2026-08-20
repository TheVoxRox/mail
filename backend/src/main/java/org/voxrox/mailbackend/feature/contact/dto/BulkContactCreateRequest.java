package org.voxrox.mailbackend.feature.contact.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Body of the bulk contact create.
 *
 * <p>
 * The 100-item cap bounds the <em>work</em> of one request, not its bytes:
 * ContactBulkService writes the list item by item, each in its own transaction,
 * so a full batch is up to 100 transactions and (at ten addresses per contact)
 * some 1100 rows against a database with a single writer. It is not a statement
 * about how large an address book may be, and the number itself is a match with
 * the sibling caps — bulk delete and label assignment take 100 ids, merge takes
 * 9 sources — rather than a measured limit. Bytes are deliberately out of scope
 * here: bean validation runs after Jackson has already materialized the whole
 * payload, which docs/API_SURFACE_AUDIT.md records as the accepted residual of
 * finding A1.
 *
 * <p>
 * A client with a larger file splits it into batches of this size, the way the
 * vCard import does; raising the cap would not remove the need to split, only
 * move the file size at which it starts to matter.
 */
public record BulkContactCreateRequest(
        @NotEmpty(message = "{validation.contacts.listRequired}") @Size(max = 100, message = "{validation.contacts.max100}") @Valid List<ContactCreateRequest> contacts) {
}
