package org.voxrox.mailbackend.feature.mail.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.account.event.AccountRequiresReauthEvent;
import org.voxrox.mailbackend.feature.mail.service.ImapConnectionManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountReauthEventListener")
class AccountReauthEventListenerTest {

    @Mock
    private ImapConnectionManager imapConnectionManager;

    @InjectMocks
    private AccountReauthEventListener listener;

    @Test
    @DisplayName("account requires a new sign-in -> both lanes' connections are closed")
    void closesConnectionsOfTheAccount() {
        listener.handleRequiresReauth(new AccountRequiresReauthEvent(42L));

        // removeConnection, not purgeAccount: the account still exists and can be
        // signed in again; only its connections are dead weight.
        verify(imapConnectionManager).removeConnection(42L);
    }

    @Test
    @DisplayName("purge failure is swallowed — the flag is already written and the caller is long gone")
    void purgeFailureDoesNotPropagate() {
        doThrow(new IllegalStateException("connection lock interrupted")).when(imapConnectionManager)
                .removeConnection(7L);

        // Nothing can act on this: the event is asynchronous, the account is already
        // unusable, and an orphan dies with the process at the latest.
        assertThatCode(() -> listener.handleRequiresReauth(new AccountRequiresReauthEvent(7L)))
                .doesNotThrowAnyException();
    }
}
