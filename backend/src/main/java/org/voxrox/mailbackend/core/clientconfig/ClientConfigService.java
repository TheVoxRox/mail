package org.voxrox.mailbackend.core.clientconfig;

import org.springframework.stereotype.Service;
import org.voxrox.mailbackend.core.config.ClientConfigProperties;
import org.voxrox.mailbackend.core.config.MailClientProperties;

@Service
public class ClientConfigService {

    private final MailClientProperties mailProps;
    private final ClientConfigProperties clientConfigProps;

    public ClientConfigService(MailClientProperties mailProps, ClientConfigProperties clientConfigProps) {
        this.mailProps = mailProps;
        this.clientConfigProps = clientConfigProps;
    }

    public ClientConfigResponse getClientConfig() {
        return new ClientConfigResponse(mailProps.sync().defaultPageSize(), mailProps.sync().apiMaxPageSize(),
                mailProps.sync().searchQueryMaxLength(), clientConfigProps.contactDefaultPageSize(),
                clientConfigProps.contactQueryMaxLength(), clientConfigProps.contactAutocompleteDefaultLimit(),
                clientConfigProps.contactAutocompleteMaxLimit(), clientConfigProps.attachmentMaxBytes(),
                clientConfigProps.attachmentTotalMaxBytes(), clientConfigProps.largeAttachmentWarningBytes());
    }
}
