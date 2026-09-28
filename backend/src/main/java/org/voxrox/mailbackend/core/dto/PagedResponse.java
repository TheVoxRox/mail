package org.voxrox.mailbackend.core.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable JSON representation of a page. Spring serializes {@link Page} via
 * {@code PageImpl} and logs a startup warning that the format is not stable
 * across versions. Controllers returning paginated content should return this
 * wrapper — that locks the API contract regardless of the Spring Data version.
 *
 * The fields mirror what the frontend typically needs for pagination UI:
 * content + navigation metadata. {@code olderOnServer} is true only for a
 * {@link WindowedPage} whose listing holds more than can be browsed; the pager
 * then ends at {@code totalPages} while {@code totalElements} keeps the whole.
 */
public record PagedResponse<T>(List<T> content, int page, int size, int totalPages, long totalElements, boolean first,
        boolean last, boolean olderOnServer) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        boolean olderOnServer = page instanceof WindowedPage<T> windowed && windowed.isOlderOnServer();
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalPages(),
                page.getTotalElements(), page.isFirst(), page.isLast(), olderOnServer);
    }
}
