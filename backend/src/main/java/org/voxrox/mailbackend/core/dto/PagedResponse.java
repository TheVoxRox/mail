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
 * content + navigation metadata.
 */
public record PagedResponse<T>(List<T> content, int page, int size, int totalPages, long totalElements, boolean first,
        boolean last) {

    public static <T> PagedResponse<T> from(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalPages(),
                page.getTotalElements(), page.isFirst(), page.isLast());
    }
}
