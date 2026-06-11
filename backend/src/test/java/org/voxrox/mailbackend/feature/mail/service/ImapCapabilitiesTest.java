package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.mail.Store;

import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ImapCapabilitiesTest {

    @Test
    @DisplayName("Non-IMAP store -> no capability")
    void nonImapStore() throws Exception {
        Store store = mock(Store.class);

        ImapCapabilities caps = ImapCapabilities.probe(store);

        assertThat(caps.hasCondstore()).isFalse();
        assertThat(caps.hasQresync()).isFalse();
    }

    @Test
    @DisplayName("Server with no capability -> fallback path")
    void noCapabilities() throws Exception {
        IMAPStore store = mock(IMAPStore.class);
        when(store.hasCapability("CONDSTORE")).thenReturn(false);
        when(store.hasCapability("QRESYNC")).thenReturn(false);

        ImapCapabilities caps = ImapCapabilities.probe(store);

        assertThat(caps.hasCondstore()).isFalse();
        assertThat(caps.hasQresync()).isFalse();
    }

    @Test
    @DisplayName("CONDSTORE only (no QRESYNC) -> flag sync via CHANGEDSINCE, cleanup via UID enumeration")
    void condstoreOnly() throws Exception {
        IMAPStore store = mock(IMAPStore.class);
        when(store.hasCapability("CONDSTORE")).thenReturn(true);
        when(store.hasCapability("QRESYNC")).thenReturn(false);

        ImapCapabilities caps = ImapCapabilities.probe(store);

        assertThat(caps.hasCondstore()).isTrue();
        assertThat(caps.hasQresync()).isFalse();
    }

    @Test
    @DisplayName("QRESYNC implies CONDSTORE (RFC 7162 §3.2) even without an explicit CONDSTORE flag")
    void qresyncImpliesCondstore() throws Exception {
        IMAPStore store = mock(IMAPStore.class);
        when(store.hasCapability("CONDSTORE")).thenReturn(false);
        when(store.hasCapability("QRESYNC")).thenReturn(true);

        ImapCapabilities caps = ImapCapabilities.probe(store);

        assertThat(caps.hasCondstore()).isTrue();
        assertThat(caps.hasQresync()).isTrue();
    }

    @Test
    @DisplayName("Both — Gmail / Outlook / Seznam / iCloud typical state")
    void bothCapabilities() throws Exception {
        IMAPStore store = mock(IMAPStore.class);
        when(store.hasCapability("CONDSTORE")).thenReturn(true);
        when(store.hasCapability("QRESYNC")).thenReturn(true);

        ImapCapabilities caps = ImapCapabilities.probe(store);

        assertThat(caps.hasCondstore()).isTrue();
        assertThat(caps.hasQresync()).isTrue();
    }
}
