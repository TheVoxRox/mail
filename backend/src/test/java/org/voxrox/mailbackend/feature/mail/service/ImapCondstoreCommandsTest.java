package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.mail.Flags;
import jakarta.mail.MessagingException;

import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.FLAGS;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.UID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

    /**
     * Answers the command with {@code untagged}, then its tagged completion, one
     * response per read, the way the wire does.
     */
    private static Response stubStream(IMAPProtocol protocol, Response... untagged) throws Exception {
        Response tagged = mock(Response.class);
        when(tagged.isTagged()).thenReturn(true);
        when(tagged.getTag()).thenReturn("A7");
        when(protocol.writeCommand(eq("UID FETCH"), any())).thenReturn("A7");
        Response[] rest = new Response[untagged.length];
        System.arraycopy(untagged, 1, rest, 0, untagged.length - 1);
        rest[untagged.length - 1] = tagged;
        when(protocol.readResponse()).thenReturn(untagged[0], rest);
        return tagged;
    }

    @Test
    @DisplayName("fetchAllServerUids collects every UID and skips responses without a UID item")
    void fetchAllServerUidsCollectsUids() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        // Build the response mocks first — their own stubbing must complete before the
        // protocol's stubbing starts, or Mockito reports unfinished stubbing.
        FetchResponse first = uidOnlyResponse(10L);
        FetchResponse second = uidOnlyResponse(20L);
        FetchResponse noUid = mock(FetchResponse.class);
        when(noUid.getItem(UID.class)).thenReturn(null);
        Response tagged = stubStream(protocol, first, second, noUid);
        stubDoCommand(folder, protocol);

        Set<Long> uids = ImapCondstoreCommands.fetchAllServerUids(folder);

        assertThat(uids).containsExactlyInAnyOrder(10L, 20L);
        verify(protocol).handleResult(tagged);
    }

    /**
     * B1-8. Protocol.command holds every response of a command until the tagged
     * one, and the listing answers once per message in the folder: 154 MB for
     * 500,000 messages, measured at audit 1.16.
     */
    @Test
    @DisplayName("The UID listing reads one response at a time and never lets Protocol.command collect them")
    void uidListingIsNotCollected() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        FetchResponse first = uidOnlyResponse(10L);
        FetchResponse second = uidOnlyResponse(20L);
        stubStream(protocol, first, second);
        stubDoCommand(folder, protocol);

        ImapCondstoreCommands.fetchAllServerUids(folder);

        verify(protocol, never()).command(any(), any());
        // Each response reaches the folder's handlers on its own, the tagged one too.
        verify(protocol, times(3)).notifyResponseHandlers(any());
    }

    @Test
    @DisplayName("A response that does not parse is skipped, as Protocol.command skips it")
    void unparseableResponseIsSkipped() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        FetchResponse first = uidOnlyResponse(10L);
        Response tagged = mock(Response.class);
        when(tagged.isTagged()).thenReturn(true);
        when(tagged.getTag()).thenReturn("A7");
        when(protocol.writeCommand(eq("UID FETCH"), any())).thenReturn("A7");
        when(protocol.readResponse()).thenThrow(new ProtocolException("garbled")).thenReturn(first, tagged);
        stubDoCommand(folder, protocol);

        assertThat(ImapCondstoreCommands.fetchAllServerUids(folder)).containsExactly(10L);
    }

    @Test
    @DisplayName("A connection lost mid-listing ends the command with a BYE, and the listing fails")
    void lostConnectionEndsWithBye() throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        FetchResponse first = uidOnlyResponse(10L);
        when(protocol.writeCommand(eq("UID FETCH"), any())).thenReturn("A7");
        when(protocol.readResponse()).thenReturn(first).thenThrow(new IOException("connection reset"));
        // What Angus's handleResult does with a BYE.
        doAnswer(invocation -> {
            Response result = invocation.getArgument(0);
            if (result.isBYE()) {
                throw new ProtocolException("connection lost");
            }
            return null;
        }).when(protocol).handleResult(any());
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
        stubStream(protocol, seenAndAnswered, noFlagsSet, missingFlags);
        stubDoCommand(folder, protocol);

        List<FlagChange> changes = ImapCondstoreCommands.fetchFlagChangesSince(folder, 42L);

        assertThat(changes).extracting(FlagChange::uid).containsExactly(5L, 6L);
        assertThat(changes.get(0)).extracting(FlagChange::seen, FlagChange::flagged, FlagChange::answered)
                .containsExactly(true, false, true);
        assertThat(changes.get(1)).extracting(FlagChange::seen, FlagChange::flagged, FlagChange::answered)
                .containsExactly(false, false, false);
        verify(protocol, never()).command(any(), any());
    }

    /**
     * B1-8. Reading one response at a time bounds nothing by itself: the caller
     * keeps a UID or a change per response, and a server can send more responses
     * than a folder may hold messages.
     */
    @Test
    @DisplayName("Past the limit the rest is read and dropped, and the command fails once it is complete")
    void pastTheLimitTheCommandFailsInStep() throws Exception {
        IMAPProtocol protocol = mock(IMAPProtocol.class);
        FetchResponse first = uidOnlyResponse(1L);
        FetchResponse second = uidOnlyResponse(2L);
        FetchResponse third = uidOnlyResponse(3L);
        Response tagged = stubStream(protocol, first, second, third);
        List<Response> kept = new ArrayList<>();

        assertThatThrownBy(() -> ImapCondstoreCommands.readEach(protocol, "UID FETCH", new Argument(), 2, kept::add))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("more than the 2");
        assertThat(kept).containsExactly(first, second);
        // Read to its completion, so the connection is in step for the next command.
        verify(protocol).handleResult(tagged);
    }

    /**
     * B1-8. The bounded protocol charges every response to its per-command budget
     * unless told the command is read one at a time; a listing charged there would
     * be refused past 64 MiB.
     */
    @Test
    @DisplayName("A bounded protocol is told when a command is read one response at a time, and when it ends")
    void boundedProtocolKnowsWhenResponsesAreStreamed() throws Exception {
        BoundedImapProtocol protocol = mock(BoundedImapProtocol.class);
        FetchResponse first = uidOnlyResponse(1L);
        stubStream(protocol, first);

        ImapCondstoreCommands.readEach(protocol, "UID FETCH", new Argument(), 10, r -> {
        });

        InOrder order = inOrder(protocol);
        order.verify(protocol).readingOneAtATime(true);
        order.verify(protocol, times(2)).readResponse();
        order.verify(protocol).readingOneAtATime(false);
    }
}
