package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.jspecify.annotations.Nullable;
import org.voxrox.mailbackend.util.StoredText;
import org.voxrox.mailbackend.util.TextFold;

/**
 * Stores the folded form ({@link TextFold}) of every name the address book and
 * the compose typeahead search or sort by, and brings the text typed into the
 * address book and the account settings to the form the application stores it
 * in ({@link StoredText}): without surrounding whitespace, a blank optional
 * value as null.
 *
 * <p>
 * The first migration after the frozen {@code V1__init.sql}, and a Java one
 * because neither rule can be written in SQL: SQLite's {@code lower()} folds
 * ASCII only and it has no way to remove diacritics, which is the whole reason
 * the fold columns exist, and its {@code trim()} removes ASCII spaces only.
 * Running the application's own {@link TextFold} and {@link StoredText} also
 * keeps a stored value from depending on whether this migration or the
 * application wrote it. Flyway finds the class by its name in this package, the
 * same way it finds {@code V1__init.sql} in the resource directory of the same
 * name, so it runs wherever V1 runs — the application, the {@code @DataJpaTest}
 * slices, and the plain Flyway of the migration tests alike. Schema change and
 * backfill are one migration, so one transaction: no build ever sees the
 * columns without their values.
 *
 * <p>
 * The columns, each a fold of the column named before it and written by the
 * application whenever that column is:
 * <ul>
 * <li>{@code contacts.name_fold}, {@code contacts.surname_fold} — the address
 * book search and its default order (surname, then given name). Null when the
 * name is null or blank, so a contact without a surname still orders last.</li>
 * <li>{@code contact_labels.name_fold} — the order of the labels in the
 * sidebar, in the filter and on a contact. {@code name_key} keeps carrying the
 * uniqueness: it folds case only, because two Czech words can differ by a
 * diacritic alone.</li>
 * <li>{@code correspondent.display_name_fold} — the display-name half of the
 * typeahead's history search. Backfilled rather than left to a rebuild of the
 * cache: a rebuild would also bring back every suggestion the user has
 * forgotten.</li>
 * </ul>
 *
 * <p>
 * {@code ix_contacts_surname_name} is dropped and not replaced. Its comment in
 * V1 says it serves the default list order, but that order is
 * {@code NULLS LAST}, and SQLite does not use an ascending index for it
 * ({@code EXPLAIN QUERY PLAN} shows a temporary B-tree with the index present);
 * nothing else reads the raw names in index order. The same would hold for an
 * index on the folded columns, so none is created — the sort runs over a table
 * of a few thousand rows.
 *
 * <p>
 * The text, which the forms used to send as typed and the application stored
 * that way:
 * <ul>
 * <li>{@code contacts.name}, {@code surname} and {@code note} — stripped, a
 * blank one as null, and the fold keys computed from what is left. Stored with
 * a stray space, a name showed with a doubled space between name and surname
 * and went into the vCard export that way, and a name of spaces alone read as
 * present.</li>
 * <li>{@code accounts.account_name} and {@code account_credentials.username} —
 * stripped and never nulled: both are required and validated non-blank on the
 * way in, so nulling a blank one could only break a row that is broken already.
 * A username with a trailing space was the login the server refused.</li>
 * <li>{@code accounts.display_name} and {@code signature} — stripped, a blank
 * one as null. The display name goes into the From header of every message sent
 * from the account.</li>
 * </ul>
 * {@code accounts.email} is left alone. Every path that stores one validates it
 * with {@code @Email}, which refuses whitespace, so no stored address carries
 * any; and {@code ux_accounts_email} is unique, so stripping one could only
 * ever turn into a collision that stops the start.
 *
 * <p>
 * One migration rather than three, as none of its parts had shipped when the
 * next was written. Like V1, never edited once released: a correction is a
 * {@code V3}.
 */
// @callerless Flyway discovers and runs it by its class name in db.migration.
public class V2__Fold_keys_and_strip_text extends BaseJavaMigration {

    private static final String[] SCHEMA = {"ALTER TABLE contacts ADD COLUMN name_fold VARCHAR(255)",
            "ALTER TABLE contacts ADD COLUMN surname_fold VARCHAR(255)", "DROP INDEX ix_contacts_surname_name",
            "ALTER TABLE contact_labels ADD COLUMN name_fold VARCHAR(60) NOT NULL DEFAULT ''",
            "ALTER TABLE correspondent ADD COLUMN display_name_fold VARCHAR(255)"};

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        try (Statement statement = connection.createStatement()) {
            for (String ddl : SCHEMA) {
                statement.execute(ddl);
            }
        }
        stripAndFoldContacts(connection);
        foldLabels(connection);
        foldCorrespondents(connection);
        stripAccounts(connection);
        stripUsernames(connection);
    }

    /**
     * Every row is written, not only the ones the strip changes: the fold columns
     * are new, so each needs its keys either way.
     */
    private static void stripAndFoldContacts(Connection connection) throws SQLException {
        try (Statement read = connection.createStatement();
                ResultSet rows = read.executeQuery("SELECT id, name, surname, note FROM contacts");
                PreparedStatement write = connection.prepareStatement(
                        "UPDATE contacts SET name = ?, surname = ?, note = ?, name_fold = ?, surname_fold = ? "
                                + "WHERE id = ?")) {
            while (rows.next()) {
                String name = StoredText.stripToNull(rows.getString(2));
                String surname = StoredText.stripToNull(rows.getString(3));
                write.setString(1, name);
                write.setString(2, surname);
                write.setString(3, StoredText.stripToNull(rows.getString(4)));
                write.setString(4, TextFold.key(name));
                write.setString(5, TextFold.key(surname));
                write.setLong(6, rows.getLong(1));
                write.addBatch();
            }
            write.executeBatch();
        }
    }

    private static void foldLabels(Connection connection) throws SQLException {
        try (Statement read = connection.createStatement();
                ResultSet rows = read.executeQuery("SELECT id, name FROM contact_labels");
                PreparedStatement write = connection
                        .prepareStatement("UPDATE contact_labels SET name_fold = ? WHERE id = ?")) {
            while (rows.next()) {
                // NOT NULL column, and a label name is never null (V1 says so).
                write.setString(1, TextFold.fold(rows.getString(2)));
                write.setLong(2, rows.getLong(1));
                write.addBatch();
            }
            write.executeBatch();
        }
    }

    private static void foldCorrespondents(Connection connection) throws SQLException {
        try (Statement read = connection.createStatement();
                ResultSet rows = read
                        .executeQuery("SELECT id, display_name FROM correspondent WHERE display_name IS NOT NULL");
                PreparedStatement write = connection
                        .prepareStatement("UPDATE correspondent SET display_name_fold = ? WHERE id = ?")) {
            while (rows.next()) {
                write.setString(1, TextFold.key(rows.getString(2)));
                write.setLong(2, rows.getLong(1));
                write.addBatch();
            }
            write.executeBatch();
        }
    }

    /** Only the rows the strip changes are written. */
    private static void stripAccounts(Connection connection) throws SQLException {
        try (Statement read = connection.createStatement();
                ResultSet rows = read.executeQuery("SELECT id, account_name, display_name, signature FROM accounts");
                PreparedStatement write = connection.prepareStatement(
                        "UPDATE accounts SET account_name = ?, display_name = ?, signature = ? WHERE id = ?")) {
            while (rows.next()) {
                String accountName = rows.getString(2);
                String displayName = rows.getString(3);
                String signature = rows.getString(4);
                String strippedName = strip(accountName);
                String strippedDisplayName = StoredText.stripToNull(displayName);
                String strippedSignature = StoredText.stripToNull(signature);
                if (Objects.equals(accountName, strippedName) && Objects.equals(displayName, strippedDisplayName)
                        && Objects.equals(signature, strippedSignature)) {
                    continue;
                }
                write.setString(1, strippedName);
                write.setString(2, strippedDisplayName);
                write.setString(3, strippedSignature);
                write.setLong(4, rows.getLong(1));
                write.addBatch();
            }
            write.executeBatch();
        }
    }

    /** Only the rows the strip changes are written. */
    private static void stripUsernames(Connection connection) throws SQLException {
        try (Statement read = connection.createStatement();
                ResultSet rows = read.executeQuery("SELECT account_id, username FROM account_credentials");
                PreparedStatement write = connection
                        .prepareStatement("UPDATE account_credentials SET username = ? WHERE account_id = ?")) {
            while (rows.next()) {
                String username = rows.getString(2);
                String stripped = strip(username);
                if (Objects.equals(username, stripped)) {
                    continue;
                }
                write.setString(1, stripped);
                write.setLong(2, rows.getLong(1));
                write.addBatch();
            }
            write.executeBatch();
        }
    }

    /** The rule for a required field: stripped, and never nulled. */
    private static @Nullable String strip(@Nullable String value) {
        return value == null ? null : value.strip();
    }
}
