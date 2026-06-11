package org.voxrox.mailbackend.feature.mail.service;

import jakarta.mail.Flags;
import jakarta.mail.MessagingException;

import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.ProtocolException;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.FetchResponse;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.eclipse.angus.mail.imap.protocol.UID;

import module java.base;

/**
 * Raw IMAP commands for RFC 7162 (CONDSTORE/QRESYNC) issued through
 * {@link IMAPFolder#doCommand(IMAPFolder.ProtocolCommand)}. Angus Mail only
 * exposes {@link IMAPFolder#getHighestModSeq()} from the high-level API; the
 * {@code CHANGEDSINCE} parameter in UID FETCH and the UID-only enumeration for
 * cleanup must be built manually via
 * {@link IMAPProtocol#command(String, Argument)}.
 */
final class ImapCondstoreCommands {

    /**
     * One entry from a {@code UID FETCH ... CHANGEDSINCE} response — UID + flags +
     * (optionally) the message's MODSEQ.
     */
    record FlagChange(long uid, boolean seen, boolean flagged, boolean answered) {
    }

    private ImapCondstoreCommands() {
    }

    /**
     * Returns all UIDs + flags of messages whose MODSEQ is higher than
     * {@code sinceModseq}. For servers without CONDSTORE: the caller must not
     * invoke this (the capability check has already run).
     *
     * <p>
     * Wire syntax (RFC 7162 §3.1.5): {@code UID FETCH 1:* (FLAGS) (CHANGEDSINCE
     * <modseq>)}. The server returns untagged FETCH responses only for messages
     * with {@code MODSEQ > sinceModseq}.
     */
    static List<FlagChange> fetchFlagChangesSince(IMAPFolder folder, long sinceModseq) throws MessagingException {
        Object result = folder.doCommand(protocol -> issueChangedSinceCommand(protocol, sinceModseq));
        @SuppressWarnings("unchecked")
        List<FlagChange> changes = (List<FlagChange>) result;
        return changes;
    }

    /**
     * Lightweight UID enumeration — sends {@code UID FETCH 1:* (UID)} and returns
     * the set of all UIDs on the server. Drastically cheaper than a full metadata
     * fetch: the server returns only the UID values with no headers/flags/body.
     *
     * <p>
     * Use: detection of deleted messages (local UIDs minus server UIDs). A
     * substitute for a full QRESYNC SELECT VANISHED, which would require raw access
     * to the SELECT command outside the {@code folder.open()} flow.
     */
    static Set<Long> fetchAllServerUids(IMAPFolder folder) throws MessagingException {
        Object result = folder.doCommand(ImapCondstoreCommands::issueUidEnumerationCommand);
        @SuppressWarnings("unchecked")
        Set<Long> uids = (Set<Long>) result;
        return uids;
    }

    private static List<FlagChange> issueChangedSinceCommand(IMAPProtocol protocol, long sinceModseq)
            throws ProtocolException {
        Argument args = new Argument();
        args.writeAtom("1:*");
        Argument attrs = new Argument();
        attrs.writeAtom("FLAGS");
        args.writeArgument(attrs);
        Argument changedSince = new Argument();
        changedSince.writeAtom("CHANGEDSINCE");
        changedSince.writeNumber(sinceModseq);
        args.writeArgument(changedSince);

        Response[] responses = protocol.command("UID FETCH", args);
        if (responses.length == 0) {
            throw new ProtocolException("Empty response to UID FETCH ... CHANGEDSINCE");
        }
        List<FlagChange> changes = new ArrayList<>();
        for (int i = 0; i < responses.length - 1; i++) {
            Response r = responses[i];
            if (r instanceof FetchResponse fr) {
                FlagChange change = parseFlagChange(fr);
                if (change != null) {
                    changes.add(change);
                }
            }
        }
        protocol.notifyResponseHandlers(responses);
        protocol.handleResult(responses[responses.length - 1]);
        return changes;
    }

    private static Set<Long> issueUidEnumerationCommand(IMAPProtocol protocol) throws ProtocolException {
        Argument args = new Argument();
        args.writeAtom("1:*");
        Argument attrs = new Argument();
        attrs.writeAtom("UID");
        args.writeArgument(attrs);

        Response[] responses = protocol.command("UID FETCH", args);
        if (responses.length == 0) {
            throw new ProtocolException("Empty response to UID FETCH 1:* (UID)");
        }
        Set<Long> uids = new HashSet<>();
        for (int i = 0; i < responses.length - 1; i++) {
            Response r = responses[i];
            if (r instanceof FetchResponse fr) {
                UID uidItem = fr.getItem(UID.class);
                if (uidItem != null) {
                    uids.add(uidItem.uid);
                }
            }
        }
        protocol.notifyResponseHandlers(responses);
        protocol.handleResult(responses[responses.length - 1]);
        return uids;
    }

    private static FlagChange parseFlagChange(FetchResponse fr) {
        UID uidItem = fr.getItem(UID.class);
        org.eclipse.angus.mail.imap.protocol.FLAGS flagsItem = fr
                .getItem(org.eclipse.angus.mail.imap.protocol.FLAGS.class);
        if (uidItem == null || flagsItem == null) {
            return null;
        }
        boolean seen = flagsItem.contains(Flags.Flag.SEEN);
        boolean flagged = flagsItem.contains(Flags.Flag.FLAGGED);
        boolean answered = flagsItem.contains(Flags.Flag.ANSWERED);
        return new FlagChange(uidItem.uid, seen, flagged, answered);
    }
}
