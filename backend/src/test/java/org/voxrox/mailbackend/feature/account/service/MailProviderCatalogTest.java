package org.voxrox.mailbackend.feature.account.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.voxrox.mailbackend.exception.ValidationException;
import org.voxrox.mailbackend.feature.account.entity.MailProviderEntity;
import org.voxrox.mailbackend.feature.account.service.MailProviderCatalog.SystemProviderTemplate;

class MailProviderCatalogTest {

    @Test
    @DisplayName("Microsoft takes no password")
    void microsoftRefusesPassword() {
        assertThatThrownBy(() -> MailProviderCatalog.requirePasswordSignIn(named("Microsoft")))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Microsoft");
    }

    /*
     * Google takes an app password, Seznam an account or app password, and a row
     * the catalog does not list is a server nobody has said anything about.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Google", "Seznam", "Retired provider"})
    @DisplayName("every other provider takes a password")
    void otherProvidersTakePassword(String name) {
        assertThatCode(() -> MailProviderCatalog.requirePasswordSignIn(named(name))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a template that takes neither a password nor OAuth2 is refused")
    void templateWithoutAnySignInRefused() {
        assertThatThrownBy(() -> new SystemProviderTemplate("Nowhere", ",nowhere.test,", "imap.nowhere.test", 993, true,
                "smtp.nowhere.test", 465, true, false, null, false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nowhere");
    }

    private static MailProviderEntity named(String name) {
        MailProviderEntity provider = new MailProviderEntity();
        provider.setName(name);
        return provider;
    }
}
