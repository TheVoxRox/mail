package org.voxrox.mailbackend.feature.account.entity;

import jakarta.persistence.*;

import org.voxrox.mailbackend.feature.auth.dto.AuthType;

@Entity
@Table(name = "account_credentials")
public class AccountCredentialEntity {

    /**
     * The identifier is derived from {@code account.id} via {@link MapsId}. Do not
     * set it manually before persist — Spring Data {@code save()} would see a
     * non-null id and call {@code merge()} instead of {@code persist()}, which for
     * a {@code @MapsId} entity ends with {@code AssertionFailure: null identifier}
     * (Hibernate cannot derive the id from the association during merge of a
     * transient entity with relaxed OneToOne checks).
     */
    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "account_id")
    private AccountEntity account;

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false)
    private AuthType authType;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    public AccountCredentialEntity() {
    }

    public AccountCredentialEntity(AccountEntity account, AuthType authType, String username,
            String encryptedPassword) {
        this.account = account;
        this.authType = authType;
        this.username = username;
        this.password = encryptedPassword;
    }

    @Override
    public String toString() {
        return "AccountCredentialEntity{" + "id=" + id + ", authType=" + authType + ", username='" + username + '\''
                + ", password='[PROTECTED]'" + '}';
    }

    public Long getId() {
        return id;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public void setAccount(AccountEntity account) {
        this.account = account;
    }

    public AuthType getAuthType() {
        return authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEncryptedPassword() {
        return password;
    }

    /** Encryption must happen in the service layer. */
    public void setEncryptedPassword(String encryptedPassword) {
        this.password = encryptedPassword;
    }
}
