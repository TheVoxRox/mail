package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.Map;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.retry.support.RetryTemplate;
import org.voxrox.mailbackend.core.config.MailClientProperties;
import org.voxrox.mailbackend.core.metrics.MailMetrics;
import org.voxrox.mailbackend.exception.ErrorCode;
import org.voxrox.mailbackend.exception.MailAuthenticationException;
import org.voxrox.mailbackend.exception.MailConnectionException;
import org.voxrox.mailbackend.exception.MailOperationException;
import org.voxrox.mailbackend.feature.account.AccountLastError;
import org.voxrox.mailbackend.feature.account.AccountLastErrorCode;
import org.voxrox.mailbackend.feature.account.dto.AccountConnectionDetails;
import org.voxrox.mailbackend.feature.account.repository.AccountRepository;
import org.voxrox.mailbackend.feature.account.service.AccountConnectionDetailsService;
import org.voxrox.mailbackend.feature.auth.dto.AuthType;
import org.voxrox.mailbackend.feature.auth.service.GoogleTokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenService;
import org.voxrox.mailbackend.feature.auth.service.OAuth2TokenServiceRegistry;

/**
 * Unit tests for {@link ImapConnectionManager}.
 *
 * Constructing a new {@link Store} via {@code Session.getStore()} is JavaMail
 * static, so for paths that would actually create a new store we use a Mockito
 * spy and stub {@link ImapConnectionManager#getConnectedStore(Long)}. Cached
 * stores are injected directly into the internal {@code connectionPool} via
 * reflection.
 */
@ExtendWith(MockitoExtension.class)
class ImapConnectionManagerTest {

    @Mock
    private AccountConnectionDetailsService connectionDetailsService;
    @Mock
    private AccountRepository accountRepository;
    @Mock
    private OAuth2TokenServiceRegistry oauth2TokenServiceRegistry;
    @Mock
    private OAuth2TokenService oauth2TokenService;
    @Mock
    private MailClientProperties mailProps;
    @Mock
    private RetryTemplate imapRetryTemplate;
    @Mock
    private MailMetrics mailMetrics;

    private ImapConnectionManager manager;
    private static final Long ACCOUNT_ID = 42L;

    @BeforeEach
    void setUp() {
        manager = new ImapConnectionManager(connectionDetailsService, accountRepository, oauth2TokenServiceRegistry,
                mailProps, imapRetryTemplate, mailMetrics);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Store> pool() throws Exception {
        Field f = ImapConnectionManager.class.getDeclaredField("connectionPool");
        f.setAccessible(true);
        return (Map<Long, Store>) f.get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ?> locks() throws Exception {
        Field f = ImapConnectionManager.class.getDeclaredField("accountLocks");
        f.setAccessible(true);
        return (Map<Long, ?>) f.get(manager);
    }

    private Store aliveStore() throws MessagingException {
        Store store = mock(Store.class);
        when(store.isConnected()).thenReturn(true);
        when(store.getDefaultFolder()).thenReturn(mock(Folder.class));
        return store;
    }

    @Nested
    @DisplayName("executeWithLock")
    class ExecuteWithLock {

        @Test
        @DisplayName("Happy path — the action runs against the cached, alive store")
        void happyPath() throws Exception {
            Store store = aliveStore();
            pool().put(ACCOUNT_ID, store);

            String result = manager.executeWithLock(ACCOUNT_ID, s -> {
                assertThat(s).isSameAs(store);
                return "ok";
            });

            assertThat(result).isEqualTo("ok");
            verifyNoInteractions(oauth2TokenServiceRegistry, oauth2TokenService);
        }

        @Test
        @DisplayName("Auth fail -> invalidate token -> retry with a freshly connected store succeeds")
        void authFailRetries() throws Exception {
            // On the spy path, the aliveStore() stubs (isConnected/getDefaultFolder) are
            // not used; a plain mock is enough — the spy bypasses getConnectedStore,
            // which would otherwise rely on those stubs.
            Store first = mock(Store.class);
            Store second = mock(Store.class);

            ImapConnectionManager spy = spy(manager);
            // First getConnectedStore returns "first", the second (after invalidate+remove)
            // returns "second".
            doReturn(first).doReturn(second).when(spy).getConnectedStore(ACCOUNT_ID);
            pool().put(ACCOUNT_ID, first);

            // The auth-retry path (invalidateOauthTokenIfPresent) calls
            // connectionDetailsService.getImapConnectionDetails and resolves the matching
            // OAuth2TokenService from the registry based on authType/provider.
            AccountConnectionDetails details = oauth2Details(GoogleTokenService.PROVIDER_NAME);
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(details);
            when(oauth2TokenServiceRegistry.resolve(GoogleTokenService.PROVIDER_NAME)).thenReturn(oauth2TokenService);

            int[] calls = {0};
            String result = spy.executeWithLock(ACCOUNT_ID, s -> {
                calls[0]++;
                if (calls[0] == 1) {
                    assertThat(s).isSameAs(first);
                    throw new AuthenticationFailedException("token expired");
                }
                assertThat(s).isSameAs(second);
                return "recovered";
            });

            assertThat(result).isEqualTo("recovered");
            assertThat(calls[0]).isEqualTo(2);
            verify(oauth2TokenService).invalidate(ACCOUNT_ID);
            // The first store was removed from the pool and close was called.
            verify(first).close();
            // Metric: auth refresh counter incremented exactly once.
            verify(mailMetrics).incrementImapAuthRefresh();
        }

        @Test
        @DisplayName("Repeated OAuth2 auth fail -> account marked for reauth and returns MAIL_AUTHENTICATION_FAILED")
        void doubleAuthFailWraps() throws Exception {
            Store first = mock(Store.class);
            Store second = mock(Store.class);

            ImapConnectionManager spy = spy(manager);
            doReturn(first).doReturn(second).when(spy).getConnectedStore(ACCOUNT_ID);
            pool().put(ACCOUNT_ID, first);

            AccountConnectionDetails details = oauth2Details(GoogleTokenService.PROVIDER_NAME);
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(details);
            when(oauth2TokenServiceRegistry.resolve(GoogleTokenService.PROVIDER_NAME)).thenReturn(oauth2TokenService);

            assertThatThrownBy(() -> spy.executeWithLock(ACCOUNT_ID, s -> {
                throw new AuthenticationFailedException("still bad");
            })).isInstanceOf(MailOperationException.class).hasMessageContaining("denied IMAP access")
                    .satisfies(ex -> assertThat(((MailOperationException) ex).getCode())
                            .isEqualTo(ErrorCode.MAIL_OAUTH2_IMAP_ACCESS_DENIED));

            verify(oauth2TokenService).invalidate(ACCOUNT_ID);
            verify(accountRepository).updateRequiresReauth(ACCOUNT_ID, true);
            verify(accountRepository).updateLastError(eq(ACCOUNT_ID),
                    argThat((AccountLastError err) -> err.code() == AccountLastErrorCode.OAUTH2_IMAP_ACCESS_DENIED
                            && err.fallbackMessage().contains("denied IMAP access")),
                    any());
        }

        @Test
        @DisplayName("PASSWORD account: repeated auth fail -> MailAuthenticationException without an English cause and without accountId in the text")
        void doubleAuthFailPasswordReturnsLocalized() throws Exception {
            Store first = mock(Store.class);
            Store second = mock(Store.class);

            ImapConnectionManager spy = spy(manager);
            doReturn(first).doReturn(second).when(spy).getConnectedStore(ACCOUNT_ID);
            pool().put(ACCOUNT_ID, first);

            // PASSWORD account -> invalidateOauthTokenIfPresent skips the token registry
            // (no-op); persistentAuthenticationFailure proceeds outside the OAuth2 branch
            // and returns a localized MailAuthenticationException.
            AccountConnectionDetails details = passwordDetails();
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(details);

            assertThatThrownBy(() -> spy.executeWithLock(ACCOUNT_ID, s -> {
                throw new AuthenticationFailedException("[AUTHENTICATIONFAILED] Incorrect authentication data");
            })).isInstanceOf(MailAuthenticationException.class).satisfies(ex -> {
                MailAuthenticationException mae = (MailAuthenticationException) ex;
                assertThat(mae.getCode()).isEqualTo(ErrorCode.MAIL_AUTHENTICATION_FAILED);
                assertThat(mae.getMessageKey()).isEqualTo("error.mail.authenticationFailed");
                // No English leak from the raw IMAP response.
                assertThat(mae.getMessage()).doesNotContain("AUTHENTICATIONFAILED");
                assertThat(mae.getMessage()).doesNotContain("Incorrect authentication");
                // No internal accountId in user-facing text.
                assertThat(mae.getMessage()).doesNotContain(String.valueOf(ACCOUNT_ID));
                assertThat(mae.getMessage()).doesNotContain("after token refresh");
            });

            // PASSWORD account: no OAuth token invalidate and no requires_reauth flip.
            verifyNoInteractions(oauth2TokenServiceRegistry, oauth2TokenService);
            verify(accountRepository, never()).updateRequiresReauth(eq(ACCOUNT_ID), anyBoolean());
        }

        @Test
        @DisplayName("MessagingException is wrapped in MailConnectionException")
        void wrapsMessagingException() throws Exception {
            pool().put(ACCOUNT_ID, aliveStore());

            assertThatThrownBy(() -> manager.executeWithLock(ACCOUNT_ID, s -> {
                throw new MessagingException("boom");
            })).isInstanceOf(MailConnectionException.class).hasMessageContaining("Critical IMAP error")
                    .hasMessageContaining(String.valueOf(ACCOUNT_ID));
        }

        @Test
        @DisplayName("IOException is wrapped in MailConnectionException")
        void wrapsIoException() throws Exception {
            pool().put(ACCOUNT_ID, aliveStore());

            assertThatThrownBy(() -> manager.executeWithLock(ACCOUNT_ID, s -> {
                throw new java.io.IOException("network down");
            })).isInstanceOf(MailConnectionException.class).hasMessageContaining("network down");
        }
    }

    @Nested
    @DisplayName("removeConnection / purgeAccount")
    class Cleanup {

        @Test
        @DisplayName("removeConnection closes the store and removes it from the pool")
        void removesAndCloses() throws Exception {
            Store store = mock(Store.class);
            pool().put(ACCOUNT_ID, store);

            manager.removeConnection(ACCOUNT_ID);

            assertThat(pool()).doesNotContainKey(ACCOUNT_ID);
            verify(store).close();
        }

        @Test
        @DisplayName("removeConnection is no-op when the pool has nothing for the account")
        void removeNonExistentIsNoop() throws Exception {
            manager.removeConnection(ACCOUNT_ID);
            assertThat(pool()).isEmpty();
        }

        @Test
        @DisplayName("removeConnection swallows exceptions from Store.close()")
        void swallowsCloseException() throws Exception {
            Store store = mock(Store.class);
            doThrow(new MessagingException("close failed")).when(store).close();
            pool().put(ACCOUNT_ID, store);

            // Must not propagate — cleanup must proceed even for broken stores.
            manager.removeConnection(ACCOUNT_ID);

            assertThat(pool()).doesNotContainKey(ACCOUNT_ID);
        }

        @Test
        @DisplayName("shutdown closes all active connections and clears the pool and locks")
        void shutdownClosesAll() throws Exception {
            Store a = mock(Store.class);
            Store b = mock(Store.class);
            when(a.isConnected()).thenReturn(true);
            when(b.isConnected()).thenReturn(true);
            pool().put(1L, a);
            pool().put(2L, b);

            manager.shutdown();

            assertThat(pool()).isEmpty();
            assertThat(locks()).isEmpty();
            verify(a).close();
            verify(b).close();
        }

        @Test
        @DisplayName("shutdown swallows exceptions from individual store.close() and completes cleanup")
        void shutdownSwallowsCloseExceptions() throws Exception {
            Store broken = mock(Store.class);
            when(broken.isConnected()).thenReturn(true);
            doThrow(new MessagingException("close boom")).when(broken).close();
            pool().put(ACCOUNT_ID, broken);

            manager.shutdown();

            assertThat(pool()).isEmpty();
        }

        @Test
        @DisplayName("purgeAccount removes the store but keeps the lock record")
        void purgeRemovesStoreButKeepsLock() throws Exception {
            Store store = mock(Store.class);
            pool().put(ACCOUNT_ID, store);
            // removeConnection creates a lock record for the given account.
            manager.removeConnection(ACCOUNT_ID);
            assertThat(locks()).containsKey(ACCOUNT_ID);

            pool().put(ACCOUNT_ID, store);

            manager.purgeAccount(ACCOUNT_ID);

            assertThat(pool()).doesNotContainKey(ACCOUNT_ID);
            // The lock must survive the purge: removing it while another thread
            // is parked on it would let a third thread mint a fresh lock via
            // computeIfAbsent and enter the critical section concurrently.
            assertThat(locks()).containsKey(ACCOUNT_ID);
        }
    }

    @Nested
    @DisplayName("getConnectedStore")
    class GetConnectedStore {

        @Test
        @DisplayName("Returns the cached store if it is alive (isConnected + getDefaultFolder succeed)")
        void returnsCached() throws Exception {
            Store store = aliveStore();
            pool().put(ACCOUNT_ID, store);

            Store returned = manager.getConnectedStore(ACCOUNT_ID);

            assertThat(returned).isSameAs(store);
            verify(store).isConnected();
            verify(store).getDefaultFolder();
            verifyNoInteractions(connectionDetailsService);
        }

        @Test
        @DisplayName("Store isConnected=true but getDefaultFolder throws -> considered dead (liveness fail)")
        void getDefaultFolderThrowsTriggersReconnect() throws Exception {
            Store zombie = mock(Store.class);
            when(zombie.isConnected()).thenReturn(true);
            when(zombie.getDefaultFolder()).thenThrow(new MessagingException("stale tcp"));
            pool().put(ACCOUNT_ID, zombie);

            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new RuntimeException("reconnect attempted"));

            assertThatThrownBy(() -> manager.getConnectedStore(ACCOUNT_ID)).hasMessageContaining("reconnect attempted");

            // The zombie store must be closed before the reconnect attempt.
            verify(zombie).close();
        }

        @Test
        @DisplayName("Dead store leads to close + reconnect attempt (triggers connectionDetailsService)")
        void deadStoreTriggersReconnect() throws Exception {
            Store dead = mock(Store.class);
            when(dead.isConnected()).thenReturn(false);
            pool().put(ACCOUNT_ID, dead);

            // We cannot stub Session.getStore(); it is enough to verify the reconnect
            // path is triggered — connectionDetailsService is the first thing it asks.
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new RuntimeException("reconnect attempted"));

            assertThatThrownBy(() -> manager.getConnectedStore(ACCOUNT_ID)).hasMessageContaining("reconnect attempted");

            // The dead store was closed before the reconnect attempt.
            verify(dead).close();
        }
    }

    @Nested
    @DisplayName("OAuth2 SSL guard")
    class OAuth2SslGuard {

        @Test
        @DisplayName("OAuth2 account without SSL -> MailConnectionException (defense-in-depth)")
        void oauth2WithoutSslThrows() throws Exception {
            // A dead store in the pool forces the reconnect path that goes through
            // createNewConnectedStore.
            Store dead = mock(Store.class);
            when(dead.isConnected()).thenReturn(false);
            pool().put(ACCOUNT_ID, dead);

            AccountConnectionDetails plaintext = new AccountConnectionDetails("user@gmail.com", "imap.gmail.com", 143,
                    /* useSsl */ false, "user@gmail.com", "refresh-token", AuthType.OAUTH2,
                    GoogleTokenService.PROVIDER_NAME);
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(plaintext);

            assertThatThrownBy(() -> manager.getConnectedStore(ACCOUNT_ID)).isInstanceOf(MailConnectionException.class)
                    .hasMessageContaining("SSL/TLS");

            // The token must not be sent — the guard fires before getAccessToken,
            // so we never even reach the provider resolve in the registry.
            verifyNoInteractions(oauth2TokenServiceRegistry, oauth2TokenService);
            // The retry template is not called at all — the guard runs before it.
            verifyNoInteractions(imapRetryTemplate);
        }
    }

    private AccountConnectionDetails oauth2Details(String oauth2Provider) {
        return new AccountConnectionDetails("user@gmail.com", "imap.gmail.com", 993, /* useSsl */ true,
                "user@gmail.com", "refresh-token", AuthType.OAUTH2, oauth2Provider);
    }

    private AccountConnectionDetails passwordDetails() {
        return new AccountConnectionDetails("user@seznam.cz", "imap.seznam.cz", 993, /* useSsl */ true,
                "user@seznam.cz", "app-password", AuthType.PASSWORD, null);
    }
}
