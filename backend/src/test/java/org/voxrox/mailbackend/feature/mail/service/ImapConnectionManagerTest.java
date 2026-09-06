package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import org.voxrox.mailbackend.core.config.mail.ImapProperties;
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
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.ConnectionKey;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager.Lane;

/**
 * Unit tests for {@link ImapConnectionManager}.
 *
 * Constructing a new {@link Store} via {@code Session.getStore()} is JavaMail
 * static, so for paths that would actually create a new store we use a Mockito
 * spy and stub {@link ImapConnectionManager#getConnectedStore(Long, Lane)}.
 * Cached stores are injected directly into the internal {@code connectionPool}
 * via reflection.
 *
 * <p>
 * Tests that predate the lane split use {@link Lane#BACKGROUND}, which is the
 * behaviour they were written against — one connection, one lock, no
 * degradation. Lane-specific behaviour has its own nested class.
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
    /**
     * How long the background-lane holder keeps its connection in the concurrency
     * test. Only an upper bound so a failing run cannot hang the suite — the test
     * releases the holder itself and never waits this long when it passes.
     */
    private static final int HOLD_TIMEOUT_SECONDS = 5;

    @BeforeEach
    void setUp() {
        manager = new ImapConnectionManager(connectionDetailsService, accountRepository, oauth2TokenServiceRegistry,
                mailProps, imapRetryTemplate, mailMetrics);
    }

    @SuppressWarnings("unchecked")
    private Map<ConnectionKey, Store> pool() throws Exception {
        Field f = ImapConnectionManager.class.getDeclaredField("connectionPool");
        f.setAccessible(true);
        return (Map<ConnectionKey, Store>) f.get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<ConnectionKey, ?> locks() throws Exception {
        Field f = ImapConnectionManager.class.getDeclaredField("accountLocks");
        f.setAccessible(true);
        return (Map<ConnectionKey, ?>) f.get(manager);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Instant> cooldown() throws Exception {
        Field f = ImapConnectionManager.class.getDeclaredField("interactiveLaneCooldown");
        f.setAccessible(true);
        return (Map<Long, Instant>) f.get(manager);
    }

    private static ConnectionKey key(Lane lane) {
        return new ConnectionKey(ACCOUNT_ID, lane);
    }

    private Store aliveStore() throws MessagingException {
        Store store = mock(Store.class);
        when(store.isConnected()).thenReturn(true);
        when(store.getDefaultFolder()).thenReturn(mock(Folder.class));
        return store;
    }

    private void stubInteractiveRetryAfter(Duration retryAfter) {
        when(mailProps.imap()).thenReturn(new ImapProperties(993, Duration.ofSeconds(30), Duration.ofSeconds(60),
                "imaps", "imap", Duration.ofSeconds(1), retryAfter));
    }

    @Nested
    @DisplayName("executeWithLock")
    class ExecuteWithLock {

        @Test
        @DisplayName("Happy path — the action runs against the cached, alive store")
        void happyPath() throws Exception {
            Store store = aliveStore();
            pool().put(key(Lane.BACKGROUND), store);

            String result = manager.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> {
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
            doReturn(first).doReturn(second).when(spy).getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);
            pool().put(key(Lane.BACKGROUND), first);

            // The auth-retry path (invalidateOauthTokenIfPresent) calls
            // connectionDetailsService.getImapConnectionDetails and resolves the matching
            // OAuth2TokenService from the registry based on authType/provider.
            AccountConnectionDetails details = oauth2Details(GoogleTokenService.PROVIDER_NAME);
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(details);
            when(oauth2TokenServiceRegistry.resolve(GoogleTokenService.PROVIDER_NAME)).thenReturn(oauth2TokenService);

            int[] calls = {0};
            String result = spy.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> {
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
            doReturn(first).doReturn(second).when(spy).getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);
            pool().put(key(Lane.BACKGROUND), first);

            AccountConnectionDetails details = oauth2Details(GoogleTokenService.PROVIDER_NAME);
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(details);
            when(oauth2TokenServiceRegistry.resolve(GoogleTokenService.PROVIDER_NAME)).thenReturn(oauth2TokenService);

            assertThatThrownBy(() -> spy.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> {
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
            doReturn(first).doReturn(second).when(spy).getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);
            pool().put(key(Lane.BACKGROUND), first);

            // PASSWORD account -> invalidateOauthTokenIfPresent skips the token registry
            // (no-op); persistentAuthenticationFailure proceeds outside the OAuth2 branch
            // and returns a localized MailAuthenticationException.
            AccountConnectionDetails details = passwordDetails();
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(details);

            assertThatThrownBy(() -> spy.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> {
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
            pool().put(key(Lane.BACKGROUND), aliveStore());

            assertThatThrownBy(() -> manager.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> {
                throw new MessagingException("boom");
            })).isInstanceOf(MailConnectionException.class).hasMessageContaining("Critical IMAP error")
                    .hasMessageContaining(String.valueOf(ACCOUNT_ID));
        }

        @Test
        @DisplayName("IOException is wrapped in MailConnectionException")
        void wrapsIoException() throws Exception {
            pool().put(key(Lane.BACKGROUND), aliveStore());

            assertThatThrownBy(() -> manager.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> {
                throw new java.io.IOException("network down");
            })).isInstanceOf(MailConnectionException.class).hasMessageContaining("network down");
        }
    }

    @Nested
    @DisplayName("Lanes")
    class Lanes {

        @Test
        @DisplayName("Each lane runs the action against its own store")
        void lanesUseSeparateStores() throws Exception {
            Store background = aliveStore();
            Store interactive = aliveStore();
            pool().put(key(Lane.BACKGROUND), background);
            pool().put(key(Lane.INTERACTIVE), interactive);

            Store seenByBackground = manager.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> s);
            Store seenByInteractive = manager.executeWithLock(ACCOUNT_ID, Lane.INTERACTIVE, s -> s);

            assertThat(seenByBackground).isSameAs(background);
            assertThat(seenByInteractive).isSameAs(interactive);
        }

        @Test
        @DisplayName("An interactive action runs while a background action holds its lane — the point of the split")
        void interactiveDoesNotQueueBehindBackground() throws Exception {
            pool().put(key(Lane.BACKGROUND), aliveStore());
            pool().put(key(Lane.INTERACTIVE), aliveStore());

            CountDownLatch backgroundHoldsLock = new CountDownLatch(1);
            CountDownLatch releaseBackground = new CountDownLatch(1);
            Thread holder = new Thread(() -> manager.executeWithLock(ACCOUNT_ID, Lane.BACKGROUND, s -> {
                backgroundHoldsLock.countDown();
                try {
                    // Stands in for a folder cycle: holds its connection until the
                    // test lets go, which before the split meant the read below could
                    // not start.
                    releaseBackground.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }), "background-lane-holder");
            holder.setDaemon(true);
            holder.start();
            assertThat(backgroundHoldsLock.await(HOLD_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            /*
             * The read runs on its own thread and the assertion is on whether it FINISHED,
             * not on what it returned. Calling it inline would still pass on a shared lock
             * — it would simply block until the holder timed out and then succeed, turning
             * a real regression into a slow green test. Which is what the first version of
             * this test did.
             */
            CountDownLatch interactiveFinished = new CountDownLatch(1);
            String[] seen = new String[1];
            Thread reader = new Thread(() -> {
                seen[0] = manager.executeWithLock(ACCOUNT_ID, Lane.INTERACTIVE, s -> "read through");
                interactiveFinished.countDown();
            }, "interactive-lane-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finishedWhileBackgroundHeld = interactiveFinished.await(2, TimeUnit.SECONDS);
            releaseBackground.countDown();
            holder.join(HOLD_TIMEOUT_SECONDS * 1000);
            reader.join(HOLD_TIMEOUT_SECONDS * 1000);

            assertThat(finishedWhileBackgroundHeld)
                    .as("interactive action must complete while the background lane is still held").isTrue();
            assertThat(seen[0]).isEqualTo("read through");
        }

        @Test
        @DisplayName("A failed interactive connect runs the action on the background lane instead")
        void failedInteractiveConnectDegrades() throws Exception {
            Store background = mock(Store.class);
            ImapConnectionManager spy = spy(manager);
            doThrow(new MessagingException("Maximum number of connections from user+IP exceeded")).when(spy)
                    .getConnectedStore(ACCOUNT_ID, Lane.INTERACTIVE);
            doReturn(background).when(spy).getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);
            stubInteractiveRetryAfter(Duration.ofMinutes(5));

            Store used = spy.executeWithLock(ACCOUNT_ID, Lane.INTERACTIVE, s -> s);

            assertThat(used).isSameAs(background);
            verify(mailMetrics).incrementImapLaneFallback();
        }

        @Test
        @DisplayName("After a failed interactive connect the lane is skipped until the cooldown expires")
        void cooldownStopsRepeatedInteractiveConnects() throws Exception {
            Store background = mock(Store.class);
            ImapConnectionManager spy = spy(manager);
            doThrow(new MessagingException("Maximum number of connections from user+IP exceeded")).when(spy)
                    .getConnectedStore(ACCOUNT_ID, Lane.INTERACTIVE);
            doReturn(background).when(spy).getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);
            stubInteractiveRetryAfter(Duration.ofMinutes(5));

            spy.executeWithLock(ACCOUNT_ID, Lane.INTERACTIVE, s -> s);
            Store secondCall = spy.executeWithLock(ACCOUNT_ID, Lane.INTERACTIVE, s -> s);

            assertThat(secondCall).isSameAs(background);
            // The second call must not pay another connect attempt: a failing connect
            // costs connectionTimeout plus the retry template's attempts, and paying
            // that per message opened would make a degraded lane worse than none.
            verify(spy, times(1)).getConnectedStore(ACCOUNT_ID, Lane.INTERACTIVE);
            verify(mailMetrics, times(1)).incrementImapLaneFallback();
        }

        @Test
        @DisplayName("An expired cooldown lets the interactive lane be tried again")
        void expiredCooldownRetriesInteractive() throws Exception {
            Store interactive = mock(Store.class);
            ImapConnectionManager spy = spy(manager);
            doReturn(interactive).when(spy).getConnectedStore(ACCOUNT_ID, Lane.INTERACTIVE);
            cooldown().put(ACCOUNT_ID, Instant.now().minusSeconds(1));

            Store used = spy.executeWithLock(ACCOUNT_ID, Lane.INTERACTIVE, s -> s);

            assertThat(used).isSameAs(interactive);
            assertThat(cooldown()).doesNotContainKey(ACCOUNT_ID);
            verify(mailMetrics, never()).incrementImapLaneFallback();
        }

        @Test
        @DisplayName("An auth failure on the interactive lane refreshes the token; it does not degrade")
        void authFailureDoesNotDegrade() throws Exception {
            Store first = mock(Store.class);
            Store second = mock(Store.class);

            ImapConnectionManager spy = spy(manager);
            doReturn(first).doReturn(second).when(spy).getConnectedStore(ACCOUNT_ID, Lane.INTERACTIVE);
            pool().put(key(Lane.INTERACTIVE), first);

            AccountConnectionDetails details = oauth2Details(GoogleTokenService.PROVIDER_NAME);
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(details);
            when(oauth2TokenServiceRegistry.resolve(GoogleTokenService.PROVIDER_NAME)).thenReturn(oauth2TokenService);

            int[] calls = {0};
            String result = spy.executeWithLock(ACCOUNT_ID, Lane.INTERACTIVE, s -> {
                if (++calls[0] == 1) {
                    throw new AuthenticationFailedException("token expired");
                }
                return "recovered on the same lane";
            });

            assertThat(result).isEqualTo("recovered on the same lane");
            // Credentials belong to the account, not to a connection — the background
            // lane would fail identically, so degrading would only hide the refresh.
            verify(spy, never()).getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);
            verify(mailMetrics, never()).incrementImapLaneFallback();
            verify(mailMetrics).incrementImapAuthRefresh();
        }

        @Test
        @DisplayName("executeWithLockOrSkip degrades too, keeping its timeout semantics")
        void skipVariantDegrades() throws Exception {
            Store background = mock(Store.class);
            ImapConnectionManager spy = spy(manager);
            doThrow(new MessagingException("connection limit")).when(spy).getConnectedStore(ACCOUNT_ID,
                    Lane.INTERACTIVE);
            doReturn(background).when(spy).getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);
            stubInteractiveRetryAfter(Duration.ofMinutes(5));

            Optional<Store> used = spy.executeWithLockOrSkip(ACCOUNT_ID, Lane.INTERACTIVE, Duration.ofSeconds(1),
                    s -> s);

            assertThat(used).containsSame(background);
            verify(mailMetrics).incrementImapLaneFallback();
        }
    }

    @Nested
    @DisplayName("removeConnection / purgeAccount")
    class Cleanup {

        @Test
        @DisplayName("removeConnection closes the store and removes it from the pool")
        void removesAndCloses() throws Exception {
            Store store = mock(Store.class);
            pool().put(key(Lane.BACKGROUND), store);

            manager.removeConnection(ACCOUNT_ID);

            assertThat(pool()).doesNotContainKey(key(Lane.BACKGROUND));
            verify(store).close();
        }

        @Test
        @DisplayName("removeConnection closes both lanes, not just the one that failed")
        void removesBothLanes() throws Exception {
            Store background = mock(Store.class);
            Store interactive = mock(Store.class);
            pool().put(key(Lane.BACKGROUND), background);
            pool().put(key(Lane.INTERACTIVE), interactive);

            manager.removeConnection(ACCOUNT_ID);

            // Every caller is reacting to something that invalidates the account's
            // sessions as a whole (deleted, re-authenticated, network dropped);
            // leaving one lane's socket behind keeps the half-open connection this
            // method exists to clear.
            assertThat(pool()).isEmpty();
            verify(background).close();
            verify(interactive).close();
        }

        @Test
        @DisplayName("purgeAccount clears the interactive-lane cooldown so a re-added account starts clean")
        void purgeClearsCooldown() throws Exception {
            cooldown().put(ACCOUNT_ID, Instant.now().plusSeconds(300));

            manager.purgeAccount(ACCOUNT_ID);

            assertThat(cooldown()).doesNotContainKey(ACCOUNT_ID);
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
            pool().put(key(Lane.BACKGROUND), store);

            // Must not propagate — cleanup must proceed even for broken stores.
            manager.removeConnection(ACCOUNT_ID);

            assertThat(pool()).doesNotContainKey(key(Lane.BACKGROUND));
        }

        @Test
        @DisplayName("shutdown closes all active connections and clears the pool and locks")
        void shutdownClosesAll() throws Exception {
            Store a = mock(Store.class);
            Store b = mock(Store.class);
            when(a.isConnected()).thenReturn(true);
            when(b.isConnected()).thenReturn(true);
            pool().put(new ConnectionKey(1L, Lane.BACKGROUND), a);
            pool().put(new ConnectionKey(2L, Lane.BACKGROUND), b);

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
            pool().put(key(Lane.BACKGROUND), broken);

            manager.shutdown();

            assertThat(pool()).isEmpty();
        }

        @Test
        @DisplayName("purgeAccount removes the store but keeps the lock record")
        void purgeRemovesStoreButKeepsLock() throws Exception {
            Store store = mock(Store.class);
            pool().put(key(Lane.BACKGROUND), store);
            // removeConnection creates a lock record for the given account.
            manager.removeConnection(ACCOUNT_ID);
            assertThat(locks()).containsKey(key(Lane.BACKGROUND));

            pool().put(key(Lane.BACKGROUND), store);

            manager.purgeAccount(ACCOUNT_ID);

            assertThat(pool()).doesNotContainKey(key(Lane.BACKGROUND));
            // The lock must survive the purge: removing it while another thread
            // is parked on it would let a third thread mint a fresh lock via
            // computeIfAbsent and enter the critical section concurrently.
            assertThat(locks()).containsKey(key(Lane.BACKGROUND));
        }
    }

    @Nested
    @DisplayName("getConnectedStore")
    class GetConnectedStore {

        @Test
        @DisplayName("Returns the cached store if it is alive (isConnected + getDefaultFolder succeed)")
        void returnsCached() throws Exception {
            Store store = aliveStore();
            pool().put(key(Lane.BACKGROUND), store);

            Store returned = manager.getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND);

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
            pool().put(key(Lane.BACKGROUND), zombie);

            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new RuntimeException("reconnect attempted"));

            assertThatThrownBy(() -> manager.getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND))
                    .hasMessageContaining("reconnect attempted");

            // The zombie store must be closed before the reconnect attempt.
            verify(zombie).close();
        }

        @Test
        @DisplayName("Dead store leads to close + reconnect attempt (triggers connectionDetailsService)")
        void deadStoreTriggersReconnect() throws Exception {
            Store dead = mock(Store.class);
            when(dead.isConnected()).thenReturn(false);
            pool().put(key(Lane.BACKGROUND), dead);

            // We cannot stub Session.getStore(); it is enough to verify the reconnect
            // path is triggered — connectionDetailsService is the first thing it asks.
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID))
                    .thenThrow(new RuntimeException("reconnect attempted"));

            assertThatThrownBy(() -> manager.getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND))
                    .hasMessageContaining("reconnect attempted");

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
            pool().put(key(Lane.BACKGROUND), dead);

            AccountConnectionDetails plaintext = new AccountConnectionDetails("user@gmail.com", "imap.gmail.com", 143,
                    /* useSsl */ false, "user@gmail.com", "refresh-token", AuthType.OAUTH2,
                    GoogleTokenService.PROVIDER_NAME);
            when(connectionDetailsService.getImapConnectionDetails(ACCOUNT_ID)).thenReturn(plaintext);

            assertThatThrownBy(() -> manager.getConnectedStore(ACCOUNT_ID, Lane.BACKGROUND))
                    .isInstanceOf(MailConnectionException.class).hasMessageContaining("SSL/TLS");

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
