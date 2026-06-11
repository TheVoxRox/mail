package org.voxrox.mailbackend.feature.account.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;

import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String accountName;
    private String email;
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    private @Nullable MailProviderEntity provider;

    @Embedded
    @AttributeOverrides({@AttributeOverride(name = "host", column = @Column(name = "imap_host")),
            @AttributeOverride(name = "port", column = @Column(name = "imap_port")),
            @AttributeOverride(name = "useSsl", column = @Column(name = "imap_ssl"))})
    private MailServerConfig imapConfig;

    @Embedded
    @AttributeOverrides({@AttributeOverride(name = "host", column = @Column(name = "smtp_host")),
            @AttributeOverride(name = "port", column = @Column(name = "smtp_port")),
            @AttributeOverride(name = "useSsl", column = @Column(name = "smtp_ssl"))})
    private MailServerConfig smtpConfig;

    private boolean active = true;

    /**
     * Set to true when the OAuth2 provider rejects the refresh token (revoke /
     * expiry / scope change). The account is then excluded from the scheduled sync
     * until a successful re-login resets the flag. This prevents repeatedly
     * hammering the provider /token endpoint with valid but server-rejected tokens.
     */
    @Column(name = "requires_reauth", nullable = false)
    private boolean requiresReauth = false;

    /**
     * RegistrationId of the OAuth2 provider ({@code "google"}, {@code "microsoft"},
     * …) or {@code null} for PASSWORD accounts. The value matches both the Spring
     * Security ClientRegistration ID and the key in
     * {@link org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry}
     * — a single source of truth for provider routing.
     */
    @Column(name = "oauth2_provider")
    private String oauth2Provider;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    @Column(name = "last_error")
    private @Nullable String lastError;

    @Column(name = "last_error_code")
    private @Nullable String lastErrorCode;

    @Column(name = "last_error_args")
    private @Nullable String lastErrorArgs;

    @OneToOne(mappedBy = "account", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private AccountCredentialEntity credentials;

    public AccountEntity() {
        this.imapConfig = new MailServerConfig();
        this.smtpConfig = new MailServerConfig();
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof AccountEntity other))
            return false;
        return getId() != null && getId().equals(other.getId());
    }

    @Override
    public final int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "AccountEntity{" + "id=" + id + ", accountName='" + accountName + '\'' + ", email='" + email + '\''
                + ", displayName='" + displayName + '\'' + ", active=" + active + ", lastSyncAt=" + lastSyncAt + '}';
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public @Nullable MailProviderEntity getProvider() {
        return provider;
    }

    public void setProvider(@Nullable MailProviderEntity provider) {
        this.provider = provider;
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

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isRequiresReauth() {
        return requiresReauth;
    }

    public void setRequiresReauth(boolean requiresReauth) {
        this.requiresReauth = requiresReauth;
    }

    public String getOauth2Provider() {
        return oauth2Provider;
    }

    public void setOauth2Provider(String oauth2Provider) {
        this.oauth2Provider = oauth2Provider;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public LocalDateTime getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(LocalDateTime lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public @Nullable String getLastError() {
        return lastError;
    }

    public void setLastError(@Nullable String lastError) {
        this.lastError = lastError;
    }

    public @Nullable String getLastErrorCode() {
        return lastErrorCode;
    }

    public void setLastErrorCode(@Nullable String lastErrorCode) {
        this.lastErrorCode = lastErrorCode;
    }

    public @Nullable String getLastErrorArgs() {
        return lastErrorArgs;
    }

    public void setLastErrorArgs(@Nullable String lastErrorArgs) {
        this.lastErrorArgs = lastErrorArgs;
    }

    public AccountCredentialEntity getCredentials() {
        return credentials;
    }

    public void setCredentials(AccountCredentialEntity credentials) {
        this.credentials = credentials;
    }
}
