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

/**
 * Brings the accounts stored before {@code AccountEntity} and
 * {@code AccountCredentialEntity} stripped their text to the form their setters
 * now write: the account name and the username without surrounding whitespace,
 * the display name and the signature also with a blank one as null
 * ({@link StoredText}).
 *
 * <p>
 * The form sent these fields as typed, so a space typed or pasted around them
 * was stored: the display name went into the From header of every message with
 * it, and a username with a trailing space was the login the server refused.
 * The same reasoning as {@code V3__Strip_contact_names}, and Java for the same
 * reason: the rule must be the application's, not SQLite's ASCII-only
 * {@code trim()}.
 *
 * <p>
 * The address is left alone. Every path that stores one validates it with
 * {@code @Email}, which refuses whitespace, so no stored address carries any;
 * and {@code ux_accounts_email} is unique, so stripping one could only ever
 * turn into a collision that stops the start. The setter strips it from now on.
 *
 * <p>
 * Only rows that change are written. Data only; no schema change.
 */
// @callerless Flyway discovers and runs it by its class name in db.migration.
public class V4__Strip_account_text extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        stripAccounts(connection);
        stripUsernames(connection);
    }

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

    /**
     * The rule for a required field: stripped, and never nulled. Both columns are
     * validated non-blank on the way in (the username is {@code NOT NULL} too), so
     * nulling a blank one could only break a row that is broken already.
     */
    private static @Nullable String strip(@Nullable String value) {
        return value == null ? null : value.strip();
    }
}
