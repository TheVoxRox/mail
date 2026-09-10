package org.voxrox.mailbackend.feature.mail.mapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jspecify.annotations.Nullable;

/**
 * Computes the {@code stableId} — the external, URL-facing identifier for a
 * message — deterministically from the message's identity instead of a random
 * UUID.
 *
 * <p>
 * A random id breaks every time a folder is re-downloaded: on a UIDVALIDITY
 * change the local cache is cleared and the folder is re-synced (see
 * {@code FlagSyncService#handleUidValidity} →
 * {@code MailboxMaintenanceService#clearLocalCache}), so every message is
 * re-inserted with a brand-new id. The client still holds the old id from its
 * message list and its detail request 404s ("ghost" message). Deriving the id
 * from the RFC&nbsp;5322 Message-ID — which survives a re-download unchanged —
 * keeps existing references valid.
 *
 * <p>
 * The id is scoped by account + folder so the same message under two Gmail
 * labels stays distinct (each folder keeps its own row), and falls back to uid
 * + uidValidity for the rare message with no Message-ID header (those still
 * churn on a UIDVALIDITY change, but are negligible in practice). The
 * {@code mid} / {@code uid} domain tags keep the two derivations from ever
 * colliding.
 *
 * <p>
 * <b>A Message-ID is not unique within a folder.</b> The header identifies the
 * <em>content</em>, not the <em>copy</em>: a trash folder aggregates deletions
 * from every other folder, so deleting a message from the inbox and its own
 * copy from Sent leaves two distinct IMAP messages — different uids, identical
 * Message-ID — sitting in the same folder. Observed on seznam.cz, where a
 * 111-message trash collided on the very first sync. Gmail never trips it,
 * because its folders are labels: one Message-ID is one physical message
 * however many folders show it. That is why this went unseen for months — the
 * two accounts the project develops against cannot produce the input.
 *
 * <p>
 * So the derivation cannot be the only guard, and the caller owns the
 * tie-break: {@code MessageDownloader.disambiguateStableIds} detects a taken id
 * and moves the losing copy onto {@link #computeFromUid}. Deliberately the
 * <em>same</em> uid derivation the no-Message-ID case already uses rather than
 * a third domain — a uid is unique per (account, folder) by the
 * {@code idx_messages_unique_uid} constraint, so nothing else can land on it,
 * and one fewer identity shape has to be kept collision-free.
 *
 * <p>
 * The alternative — deriving every id from uid and repairing "ghost" 404s with
 * a Message-ID lookup — was not taken: it would put every message back on an id
 * that churns on a UIDVALIDITY change to fix a case that affects duplicates
 * only. The tie-break keeps the re-download guarantee for the overwhelming
 * majority and gives it up exactly where the identity is genuinely ambiguous.
 */
public final class MessageStableId {

    private MessageStableId() {
    }

    public static String compute(long accountId, String folderName, @Nullable String messageId, @Nullable Long uid,
            @Nullable Long uidValidity) {
        if (messageId != null && !messageId.isBlank()) {
            return sha256Hex32(accountId + "\0mid\0" + folderName + "\0" + messageId.trim());
        }
        return computeFromUid(accountId, folderName, uid, uidValidity);
    }

    /**
     * The uid-scoped derivation: the identity of a <em>copy</em> rather than of its
     * content. Used for a message with no usable Message-ID header, and as the
     * tie-break for the second and further copies of one Message-ID inside a single
     * folder (see the class javadoc).
     */
    public static String computeFromUid(long accountId, String folderName, @Nullable Long uid,
            @Nullable Long uidValidity) {
        return sha256Hex32(accountId + "\0uid\0" + folderName + "\0" + uidValidity + "\0" + uid);
    }

    /**
     * SHA-256 of the input, rendered as the first 32 lowercase hex chars (128
     * bits).
     */
    private static String sha256Hex32(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
