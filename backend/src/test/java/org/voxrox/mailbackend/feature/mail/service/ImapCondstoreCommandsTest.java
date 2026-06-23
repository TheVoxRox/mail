package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import jakarta.mail.Flags;
import jakarta.mail.MessagingException;

import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.FLAGS;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.UID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.voxrox.mailbackend.feature.mail.service.ImapCondstoreCommands.FlagChange;

/**
 * Unit tests for {@link ImapCondstoreCommands} — the raw RFC 7162 UID FETCH
 * commands. GreenMail does not advertise CONDSTORE, so the {@code CHANGEDSINCE}
 * flag-diff and the UID-only enumeration paths never run in the integration
 * suite; this test exercises the response parsing directly by driving the
 * {@code folder.doCommand(...)} callback against a mocked {@link IMAPProtocol}.
 */
class ImapCondstoreCommandsTest {

    /**
     * Simulates {@code IMAPFolder.doCommand}: run the callback, wrap protocol
     * errors like the real method.
     */
    private static void stubDoCommand(IMAPFolder folder, IMAPProtocol protocol) throws MessagingException {
        when(folder.doCommand(any())).thenAnswer(invocation -> {
            IMAPFolder.ProtocolCommand command = invocation.getArgument(0);
            try {
                return command.doCommand(protocol);
            } catch (ProtocolException e) {
                throw new MessagingException(e.getMessage(), e);
            }
        });
    }

    private static UID uidItem(long value) {
        UID uid = mock(UID.class);
        uid.uid = value;
        return uid;
    }

    private static FetchResponse uidOnlyResponse(long value) {
        FetchResponse fr = mock(FetchResponse.class);
        when(fr.getItem(UID.class)).thenReturn(uidItem(value));
        return fr;
    }

    private static FetchResponse flagResponse(long value, boolean seen, boolean flagged, boolean answered) {
        FLAGS flags = mock(FLAGS.class);
        when(flags.contains(Flags.Flag.SEEN)).thenReturn(seen);
        when(flags.contains(Flags.Flag.FLAGGED)).thenReturn(flagged);
        when(flags.contains(Flags.Flag.ANSWERED)).thenReturn(answered);
        FetchResponse fr = mock(FetchResponse.class);
        when(fr.getItem(UID.class)).thenReturn(uidItem(value));
        when(fr.getItem(FLAGS.class)).thenReturn(flags);
        return fr;
    }

    @Test
    @DisplayName("fetchAllServerUids collects every UID and skips responses without a UID item")
    void fetchAllServerUidsCollectsUids() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        // Build the response mocks first — their own stubbing must complete before the
        // protocol.command(...) stubbing starts, or Mockito reports unfinished
        // stubbing.
        FetchResponse first = uidOnlyResponse(10L);
        FetchResponse second = uidOnlyResponse(20L);
        FetchResponse noUid = mock(FetchResponse.class);
        when(noUid.getItem(UID.class)).thenReturn(null);
        Response status = mock(Response.class); // trailing tagged status response, consumed as the result
        when(protocol.command(eq("UID FETCH"), any())).thenReturn(new Response[]{first, second, noUid, status});
        stubDoCommand(folder, protocol);

        Set<Long> uids = ImapCondstoreCommands.fetchAllServerUids(folder);

        assertThat(uids).containsExactlyInAnyOrder(10L, 20L);
    }

    @Test
    @DisplayName("fetchAllServerUids surfaces an empty server response as a MessagingException")
    void fetchAllServerUidsEmptyResponseThrows() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        when(protocol.command(eq("UID FETCH"), any())).thenReturn(new Response[0]);
        stubDoCommand(folder, protocol);

        assertThatThrownBy(() -> ImapCondstoreCommands.fetchAllServerUids(folder))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("fetchFlagChangesSince maps SEEN/FLAGGED/ANSWERED and skips entries missing UID or FLAGS")
    void fetchFlagChangesMapsFlags() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        // Build the response mocks first (see note above re: unfinished stubbing).
        FetchResponse seenAndAnswered = flagResponse(5L, true, false, true);
        FetchResponse noFlagsSet = flagResponse(6L, false, false, false);
        FetchResponse missingFlags = mock(FetchResponse.class);
        when(missingFlags.getItem(UID.class)).thenReturn(uidItem(7L));
        when(missingFlags.getItem(FLAGS.class)).thenReturn(null);
        Response status = mock(Response.class);
        when(protocol.command(eq("UID FETCH"), any()))
                .thenReturn(new Response[]{seenAndAnswered, noFlagsSet, missingFlags, status});
        stubDoCommand(folder, protocol);

        List<FlagChange> changes = ImapCondstoreCommands.fetchFlagChangesSince(folder, 42L);

        assertThat(changes).extracting(FlagChange::uid).containsExactly(5L, 6L);
        assertThat(changes.get(0)).extracting(FlagChange::seen, FlagChange::flagged, FlagChange::answered)
                .containsExactly(true, false, true);
        assertThat(changes.get(1)).extracting(FlagChange::seen, FlagChange::flagged, FlagChange::answered)
                .containsExactly(false, false, false);
    }
}
