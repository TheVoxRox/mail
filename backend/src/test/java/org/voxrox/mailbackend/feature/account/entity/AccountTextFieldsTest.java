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
    @DisplayName("account name, address and username are stored without surrounding whitespace")
    void requiredFieldsAreStripped() {
        AccountEntity account = new AccountEntity();
        account.setAccountName("  Work ");
        account.setEmail("jan@example.com ");
        AccountCredentialEntity credentials = new AccountCredentialEntity();
        credentials.setUsername("\tjan@example.com ");

        assertThat(account.getAccountName()).isEqualTo("Work");
        assertThat(account.getEmail()).isEqualTo("jan@example.com");
        assertThat(credentials.getUsername()).isEqualTo("jan@example.com");
    }

    @Test
    @DisplayName("display name and signature are stripped, and a blank one is stored as null")
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
}
