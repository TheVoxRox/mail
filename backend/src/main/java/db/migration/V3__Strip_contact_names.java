package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.voxrox.mailbackend.util.StoredText;
import org.voxrox.mailbackend.util.TextFold;

/**
 * Brings the contacts stored before {@link StoredText} to its form: name,
 * surname and note without surrounding whitespace, a blank one as null.
 *
 * <p>
 * From #980 on, {@code ContactEntity}'s setters apply the rule to every write,
 * but a row is only rewritten when its contact is saved again, so names typed
 * with a stray space stayed in the database: shown with a doubled space between
 * name and surname, exported that way to vCard, and a name of spaces alone
 * still read as present. Whitespace around a name means nothing, so it is
 * removed here rather than left for each reader to ignore.
 *
 * <p>
 * Java for the same reason as {@code V2__Fold_keys_for_names}: SQLite's
 * {@code trim()} removes ASCII spaces only, and the rule has to be the one the
 * application applies, so the result does not depend on which of the two wrote
 * the row. The folded keys are written from the stripped names, as the setters
 * write them; they fold to the same key anyway, except that a name of
 * whitespace alone now has none, which it already did not.
 *
 * <p>
 * Only rows that change are written. Data only; no schema change.
 */
// @callerless Flyway discovers and runs it by its class name in db.migration.
public class V3__Strip_contact_names extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (Statement read = connection.createStatement();
                ResultSet rows = read.executeQuery("SELECT id, name, surname, note FROM contacts");
                PreparedStatement write = connection.prepareStatement(
                        "UPDATE contacts SET name = ?, surname = ?, note = ?, name_fold = ?, surname_fold = ? "
                                + "WHERE id = ?")) {
            while (rows.next()) {
                String name = rows.getString(2);
                String surname = rows.getString(3);
                String note = rows.getString(4);
                String strippedName = StoredText.stripToNull(name);
                String strippedSurname = StoredText.stripToNull(surname);
                String strippedNote = StoredText.stripToNull(note);
                if (Objects.equals(name, strippedName) && Objects.equals(surname, strippedSurname)
                        && Objects.equals(note, strippedNote)) {
                    continue;
                }
                write.setString(1, strippedName);
                write.setString(2, strippedSurname);
                write.setString(3, strippedNote);
                write.setString(4, TextFold.key(strippedName));
                write.setString(5, TextFold.key(strippedSurname));
                write.setLong(6, rows.getLong(1));
                write.addBatch();
            }
            write.executeBatch();
        }
    }
}
