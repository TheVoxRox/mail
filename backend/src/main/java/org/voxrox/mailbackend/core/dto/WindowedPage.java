package org.voxrox.mailbackend.core.dto;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * A page of a listing that can be browsed only so deep, although the whole is
 * longer. The total stays the size of the whole; the page count, and with it
 * {@link #hasNext()} and {@link #isLast()}, stops at {@code depth}.
 *
 * <p>
 * The folder listings use it for a folder larger than the local window
 * (IMAP/SMTP audit B1-9): the count says how much mail the folder holds, and
 * the pager ends where the local mirror does, because a page below it would be
 * downloaded by the request that asks for it and pruned again after the next
 * sync. {@link PagedResponse#from} reports the difference as
 * {@code olderOnServer}. {@link #map} returns a plain {@code PageImpl} and
 * drops the depth, so map the content before building one of these.
 */
public class WindowedPage<T> extends PageImpl<T> {

    private final long depth;

    public WindowedPage(List<T> content, Pageable pageable, long total, long depth) {
        super(content, pageable, total);
        this.depth = depth;
    }

    @Override
    public int getTotalPages() {
        long browsable = Math.min(getTotalElements(), depth);
        return getSize() == 0 ? 1 : (int) Math.ceil((double) browsable / getSize());
    }

    /** Whether the listing holds more than can be browsed. */
    public boolean isOlderOnServer() {
        return getTotalElements() > depth;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof WindowedPage<?> other && depth == other.depth && super.equals(obj);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), depth);
    }
}
