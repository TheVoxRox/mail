package org.voxrox.mailbackend.feature.account.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The account's free-text fields are stored the {@code StoredText} way by the
 * entities' own setters, so every write path — the form, a provider sign-in —
 * gets it.
 */
class AccountTextFieldsTest {

    @Test
    @DisplayName("account name, address, username and hosts are stored without surrounding whitespace")
    void requiredFieldsAreStripped() {
        AccountEntity account = new AccountEntity();
        account.setAccountName("  Work ");
        account.setEmail("jan@example.com ");
        AccountCredentialEntity credentials = new AccountCredentialEntity();
        // An em space, and a no-break space String.strip() would keep.
        credentials.setUsername("\u2003jan@example.com\u00A0");
        MailServerConfig imap = new MailServerConfig(" imap.example.com\t", 993, true);
        MailServerConfig smtp = new MailServerConfig("smtp.example.com", 465, true);
        smtp.setHost("\u00A0smtp.example.com ");

        assertThat(account.getAccountName()).isEqualTo("Work");
        assertThat(account.getEmail()).isEqualTo("jan@example.com");
        assertThat(credentials.getUsername()).isEqualTo("jan@example.com");
        assertThat(imap.getHost()).isEqualTo("imap.example.com");
        assertThat(smtp.getHost()).isEqualTo("smtp.example.com");
    }

    @Test
    @DisplayName("display name is stripped, signature loses its blank lines, and a blank one is stored as null")
    void optionalFieldsAreStrippedOrNulled() {
        AccountEntity account = new AccountEntity();
        account.setDisplayName(" Jan Novak ");
        account.setSignature("\nJan Novak\nVoxRox\n\n");
        assertThat(account.getDisplayName()).isEqualTo("Jan Novak");
        assertThat(account.getSignature()).isEqualTo("Jan Novak\nVoxRox");

        account.setDisplayName("   ");
        account.setSignature(" \n ");
        assertThat(account.getDisplayName()).isNull();
        assertThat(account.getSignature()).isNull();
    }

    @Test
    @DisplayName("a signature keeps the indentation of its first line, in line with the lines below")
    void signatureKeepsItsIndentation() {
        AccountEntity account = new AccountEntity();
        account.setSignature("\n   Jan Novak\n   VoxRox  \n");

        assertThat(account.getSignature()).isEqualTo("   Jan Novak\n   VoxRox");
    }
}
