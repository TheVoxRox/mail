package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.voxrox.mailbackend.util.TextFold;

/**
 * Stores the folded form ({@link TextFold}) of every name the address book and
 * the compose typeahead search or sort by, and fills it in for the rows already
 * there.
 *
 * <p>
 * The first migration after the frozen {@code V1__init.sql}, and a Java one
 * because the fold cannot be written in SQL: SQLite's {@code lower()} folds
 * ASCII only and it has no way to remove diacritics, which is the whole reason
 * the columns exist. Flyway finds the class by its name in this package, the
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
 * Like V1, never edited once released: a correction is a {@code V3}.
 */
// @callerless Flyway discovers and runs it by its class name in db.migration.
public class V2__Fold_keys_for_names extends BaseJavaMigration {

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
        foldContacts(connection);
        foldLabels(connection);
        foldCorrespondents(connection);
    }

    private static void foldContacts(Connection connection) throws SQLException {
        try (Statement read = connection.createStatement();
                ResultSet rows = read.executeQuery("SELECT id, name, surname FROM contacts");
                PreparedStatement write = connection
                        .prepareStatement("UPDATE contacts SET name_fold = ?, surname_fold = ? WHERE id = ?")) {
            while (rows.next()) {
                write.setString(1, TextFold.key(rows.getString(2)));
                write.setString(2, TextFold.key(rows.getString(3)));
                write.setLong(3, rows.getLong(1));
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
}
