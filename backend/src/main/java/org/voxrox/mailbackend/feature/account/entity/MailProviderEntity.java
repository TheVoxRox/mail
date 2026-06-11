package org.voxrox.mailbackend.feature.account.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "mail_providers")
public class MailProviderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(unique = true, nullable = false)
    private String name;

    /**
     * Key field for looking up the provider by email domain. Format:
     * ",seznam.cz,email.cz,post.cz,"
     */
    @NotBlank
    @Column(name = "domains", nullable = false, length = 1000)
    private String domains;

    @Embedded
    @AttributeOverrides({@AttributeOverride(name = "host", column = @Column(name = "imap_host", nullable = false)),
            @AttributeOverride(name = "port", column = @Column(name = "imap_port", nullable = false)),
            @AttributeOverride(name = "useSsl", column = @Column(name = "imap_ssl"))})
    private MailServerConfig imapConfig;

    @Embedded
    @AttributeOverrides({@AttributeOverride(name = "host", column = @Column(name = "smtp_host", nullable = false)),
            @AttributeOverride(name = "port", column = @Column(name = "smtp_port", nullable = false)),
            @AttributeOverride(name = "useSsl", column = @Column(name = "smtp_ssl"))})
    private MailServerConfig smtpConfig;

    @Column(name = "is_system_template")
    private boolean systemTemplate = false;

    /**
     * Flag indicating whether the provider has a backend OAuth2 implementation
     * ({@link org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService} + a
     * Spring Security ClientRegistration). The frontend bootstrap loads the
     * providers and uses this value to decide whether to show the OAuth button or
     * the password form.
     */
    @Column(name = "supports_oauth2", nullable = false)
    private boolean supportsOauth2 = false;

    /**
     * RegistrationId of the Spring Security ClientRegistration ({@code "google"},
     * {@code "microsoft"}, …). The value matches the key in
     * {@link org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry}.
     * Only meaningful when {@link #supportsOauth2} is {@code true}.
     */
    @Column(name = "oauth2_registration_id")
    private String oauth2RegistrationId;

    public MailProviderEntity() {
        this.imapConfig = new MailServerConfig();
        this.smtpConfig = new MailServerConfig();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDomains() {
        return domains;
    }

    public void setDomains(String domains) {
        this.domains = domains;
    }

    public MailServerConfig getImapConfig() {
        return imapConfig;
    }

    public void setImapConfig(MailServerConfig imapConfig) {
        this.imapConfig = imapConfig;
    }

    public MailServerConfig getSmtpConfig() {
        return smtpConfig;
    }

    public void setSmtpConfig(MailServerConfig smtpConfig) {
        this.smtpConfig = smtpConfig;
    }

    public boolean isSystemTemplate() {
        return systemTemplate;
    }

    public void setSystemTemplate(boolean systemTemplate) {
        this.systemTemplate = systemTemplate;
    }

    public boolean isSupportsOauth2() {
        return supportsOauth2;
    }

    public void setSupportsOauth2(boolean supportsOauth2) {
        this.supportsOauth2 = supportsOauth2;
    }

    public String getOauth2RegistrationId() {
        return oauth2RegistrationId;
    }

    public void setOauth2RegistrationId(String oauth2RegistrationId) {
        this.oauth2RegistrationId = oauth2RegistrationId;
    }

    @Override
    public String toString() {
        return "MailProviderEntity{" + "id=" + id + ", name='" + name + '\'' + ", domains='" + domains + '\''
                + ", imapHost='" + (imapConfig != null ? imapConfig.getHost() : "null") + '\'' + ", isTemplate="
                + systemTemplate + '}';
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof MailProviderEntity other))
            return false;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }
}
