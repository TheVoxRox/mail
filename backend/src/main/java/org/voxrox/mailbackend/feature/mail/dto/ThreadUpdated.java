package org.voxrox.mailbackend.feature.mail.dto;

/**
 * SSE notification emitted when the membership of a thread changes — a new
 * message landed in the thread or a late-arriving parent caused two orphan
 * chains to merge.
 *
 * <p>
 * Per the threading design (see {@code backend/docs/THREADING_DESIGN.md}) the
 * payload is intentionally minimal: only {@code threadId} and {@code
 * accountId}. The client refetches the affected thread (or the summary listing)
 * to get the new state. A larger payload (inline {@code
 * MailSummaryResponse}) was rejected to avoid split-brain state on the client
 * and stay consistent with the {@code send_completed} / {@code send_failed}
 * pattern.
 */
public record ThreadUpdated(String type, String threadId, Long accountId) implements SseEvent {

    public static final String TYPE = "thread_updated";

    public static ThreadUpdated of(String threadId, Long accountId) {
        return new ThreadUpdated(TYPE, threadId, accountId);
    }
}
