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
import org.jspecify.annotations.Nullable;

import module java.base;

/**
 * Raw IMAP commands for RFC 7162 (CONDSTORE/QRESYNC) issued through
 * {@link IMAPFolder#doCommand(IMAPFolder.ProtocolCommand)}. Angus Mail only
 * exposes {@link IMAPFolder#getHighestModSeq()} from the high-level API; the
 * {@code CHANGEDSINCE} parameter in UID FETCH and the UID-only enumeration for
 * cleanup must be built manually. Both are read through {@link #readEach}, not
 * {@link IMAPProtocol#command(String, Argument)}, because they answer once per
 * message.
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
     * Use: detection of deleted messages (local UIDs minus server UIDs), and the
     * server-only holes the same UID set reveals. It is the every-cycle deletion
     * path only on a CONDSTORE-but-not-QRESYNC server; where QRESYNC is available
     * the SELECT reports expunged UIDs itself (see
     * {@code FlagSyncService.applyResyncEvents}) and this drops to the hourly hole
     * scan, which is the half VANISHED does not cover.
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

        List<FlagChange> changes = new ArrayList<>();
        readEach(protocol, "UID FETCH", args, BoundedImapProtocol.MAX_MESSAGES, r -> {
            if (r instanceof FetchResponse fr) {
                FlagChange change = parseFlagChange(fr);
                if (change != null) {
                    changes.add(change);
                }
            }
        });
        return changes;
    }

    private static Set<Long> issueUidEnumerationCommand(IMAPProtocol protocol) throws ProtocolException {
        Argument args = new Argument();
        args.writeAtom("1:*");
        Argument attrs = new Argument();
        attrs.writeAtom("UID");
        args.writeArgument(attrs);

        Set<Long> uids = new HashSet<>();
        readEach(protocol, "UID FETCH", args, BoundedImapProtocol.MAX_MESSAGES, r -> {
            if (r instanceof FetchResponse fr) {
                UID uidItem = fr.getItem(UID.class);
                if (uidItem != null) {
                    uids.add(uidItem.uid);
                }
            }
        });
        return uids;
    }

    /**
     * {@code Protocol.command} without the collecting (IMAP/SMTP audit B1-8). Both
     * commands here answer once per message in the folder — every message for the
     * UID listing, every changed one for {@code CHANGEDSINCE} — and
     * {@code Protocol.command} holds all of those responses until the tagged one:
     * 308 bytes each, 154 MB for a 500,000-message folder, before the set built
     * from them. This reads them one at a time, hands each to {@code each} and to
     * the folder's response handlers, and keeps none, so what the command costs is
     * what {@code each} keeps.
     *
     * <p>
     * It does what {@code Protocol.command} does in Angus 2.0.5, read from its
     * bytecode, in the same order: a write that fails ends the command with a
     * synthetic BYE; a response that fails to parse ({@code ProtocolException}) is
     * skipped; a BYE is kept to end the command with; an {@code IOException} ends
     * it with a BYE; and the tagged response of this command, or the BYE, goes to
     * {@code handleResult}. Synchronized on the protocol as {@code command} is.
     * {@code BoundedImapProtocolTest} pins the Angus version this was read against.
     *
     * <p>
     * Not charged to {@code BoundedImapProtocol}'s per-command budget, which exists
     * for what a command collects; the bound here is on what {@code each} keeps. It
     * gets at most {@code limit} responses — for a per-message answer, as many as a
     * folder may hold messages — and past that the rest of the command is read and
     * dropped and the command fails once it is complete, so the connection stays in
     * step and nothing a server sends can grow the caller's set or list further.
     */
    static void readEach(IMAPProtocol protocol, String command, Argument args, long limit, Consumer<Response> each)
            throws ProtocolException {
        synchronized (protocol) {
            BoundedImapProtocol bounded = protocol instanceof BoundedImapProtocol b ? b : null;
            if (bounded != null) {
                bounded.readingOneAtATime(true);
            }
            try {
                readEachLocked(protocol, command, args, limit, each);
            } finally {
                if (bounded != null) {
                    bounded.readingOneAtATime(false);
                }
            }
        }
    }

    private static void readEachLocked(IMAPProtocol protocol, String command, Argument args, long limit,
            Consumer<Response> each) throws ProtocolException {
        String tag;
        try {
            tag = protocol.writeCommand(command, args);
        } catch (Exception e) {
            protocol.handleResult(Response.byeResponse(e));
            return;
        }
        Response bye = null;
        Response tagged = null;
        long handed = 0;
        while (tagged == null) {
            Response r;
            try {
                r = protocol.readResponse();
            } catch (IOException e) {
                if (bye == null) {
                    bye = Response.byeResponse(e);
                }
                break;
            } catch (ProtocolException e) {
                continue;
            }
            if (r.isBYE()) {
                bye = r;
                continue;
            }
            if (r.isTagged() && tag.equals(r.getTag())) {
                tagged = r;
            } else if (++handed <= limit) {
                each.accept(r);
            }
            protocol.notifyResponseHandlers(new Response[]{r});
        }
        if (bye != null) {
            protocol.notifyResponseHandlers(new Response[]{bye});
            protocol.handleResult(bye);
            return;
        }
        protocol.handleResult(Objects.requireNonNull(tagged));
        if (handed > limit) {
            throw new ProtocolException(command + " answered with " + handed + " responses, more than the " + limit
                    + " a folder may hold messages");
        }
    }

    private static @Nullable FlagChange parseFlagChange(FetchResponse fr) {
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
