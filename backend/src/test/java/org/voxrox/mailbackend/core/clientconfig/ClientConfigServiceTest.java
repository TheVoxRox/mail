package org.voxrox.mailbackend.core.clientconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.core.config.ClientConfigProperties;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.config.mail.SyncProperties;

class ClientConfigServiceTest {

    @Test
    @DisplayName("Client config composes public limits from typed properties")
    void getClientConfigReturnsTypedProperties() {
        MailClientProperties mailProps = org.mockito.Mockito.mock(MailClientProperties.class);
        SyncProperties sync = new SyncProperties(100, 200, Duration.ofMinutes(5), Duration.ofSeconds(10), 50, 30, 300,
                4, 256, 200, Duration.ofMinutes(30), Duration.ofSeconds(30));
        when(mailProps.sync()).thenReturn(sync);

        ClientConfigProperties clientProps = new ClientConfigProperties(20, 100, 10, 20, 10 * 1024 * 1024L,
                25 * 1024 * 1024L, 5 * 1024 * 1024L);

        ClientConfigResponse response = new ClientConfigService(mailProps, clientProps).getClientConfig();

        assertThat(response.mailDefaultPageSize()).isEqualTo(50);
        assertThat(response.mailApiMaxPageSize()).isEqualTo(200);
        assertThat(response.searchQueryMaxLength()).isEqualTo(256);
        assertThat(response.contactDefaultPageSize()).isEqualTo(20);
        assertThat(response.contactQueryMaxLength()).isEqualTo(100);
        assertThat(response.contactAutocompleteDefaultLimit()).isEqualTo(10);
        assertThat(response.contactAutocompleteMaxLimit()).isEqualTo(20);
        assertThat(response.attachmentMaxBytes()).isEqualTo(10 * 1024 * 1024L);
        assertThat(response.attachmentTotalMaxBytes()).isEqualTo(25 * 1024 * 1024L);
        assertThat(response.largeAttachmentWarningBytes()).isEqualTo(5 * 1024 * 1024L);
    }
}
