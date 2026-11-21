package org.voxrox.mailbackend.feature.account.entity;

import jakarta.persistence.*;

import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.util.LogMasker;
import org.voxrox.mailbackend.util.StoredText;

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
        return "AccountCredentialEntity{" + "id=" + id + ", authType=" + authType + ", username='"
                + LogMasker.maskEmail(username) + '\'' + ", password='[PROTECTED]'" + '}';
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

    /**
     * Stored without surrounding whitespace ({@link StoredText#strip}): it is the
     * login the server sees, and a trailing space pasted with it made the sign-in
     * fail as wrong credentials. Required, so never blank here.
     */
    public void setUsername(String username) {
        this.username = StoredText.strip(username);
    }

    public String getEncryptedPassword() {
        return password;
    }

    /**
     * Encryption must happen in the service layer. The column is NOT NULL — "no
     * secret" is stored as an empty string, never as {@code null}.
     */
    public void setEncryptedPassword(String encryptedPassword) {
        this.password = encryptedPassword;
    }
}
