package org.voxrox.mailbackend.core.config.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.util.unit.DataSize;

/**
 * The open-folder budget (IMAP/SMTP audit B1-14) as Spring binds it: the value
 * a beta tester is told to change, and the two ways they are told to change it.
 */
class ImapPropertiesTest {

    private static ImapProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bindOrCreate("mail.client.imap",
                ImapProperties.class);
    }

    @Test
    @DisplayName("The property sets the budget in the units it names")
    void thePropertySetsIt() {
        assertThat(bind(Map.of("mail.client.imap.open-folder-budget", "256MB")).openFolderBudget())
                .isEqualTo(DataSize.ofMegabytes(256));
    }

    /**
     * What application.properties tells a tester to use when a new build is not at
     * hand. The packaged sidecar inherits the environment of the app that starts
     * it: the shell plugin adds the variables the frontend passes to the inherited
     * ones and clears them only when it passes none.
     */
    @Test
    @DisplayName("The environment variable the properties file names sets the budget")
    void theEnvironmentVariableSetsIt() {
        // Spring maps variable names to properties only for a source of this name.
        var environment = new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("MAIL_CLIENT_IMAP_OPENFOLDERBUDGET", "256MB"));
        ImapProperties imap = new Binder(ConfigurationPropertySources.from(environment))
                .bindOrCreate("mail.client.imap", ImapProperties.class);

        assertThat(imap.openFolderBudget()).isEqualTo(DataSize.ofMegabytes(256));
    }

    @Test
    @DisplayName("A budget of zero fails the startup instead of refusing every folder")
    void zeroIsRefused() {
        assertThatThrownBy(() -> bind(Map.of("mail.client.imap.open-folder-budget", "0B")))
                .isInstanceOf(BindException.class).hasRootCauseInstanceOf(IllegalArgumentException.class).rootCause()
                .hasMessageContaining("mail.client.imap.open-folder-budget");
    }
}
