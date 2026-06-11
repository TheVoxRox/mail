package org.voxrox.mailbackend.feature.account.entity;

import java.io.Serializable;

import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Embeddable
public class MailServerConfig implements Serializable {

    @NotBlank
    private String host;

    @NotNull
    @Min(1)
    @Max(65535)
    private Integer port;

    private boolean useSsl = true;

    public MailServerConfig() {
    }

    public MailServerConfig(String host, Integer port, boolean useSsl) {
        this.host = host;
        this.port = port;
        this.useSsl = useSsl;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public boolean isUseSsl() {
        return useSsl;
    }

    public void setUseSsl(boolean useSsl) {
        this.useSsl = useSsl;
    }
}
