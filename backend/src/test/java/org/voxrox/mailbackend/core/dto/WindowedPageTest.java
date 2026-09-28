package org.voxrox.mailbackend.core.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class WindowedPageTest {

    @Test
    @DisplayName("A listing shallower than the depth pages like a plain page")
    void shallowListingIsAPlainPage() {
        WindowedPage<String> windowed = new WindowedPage<>(List.of("a"), PageRequest.of(1, 20), 45, 300);
        PageImpl<String> plain = new PageImpl<>(List.of("a"), PageRequest.of(1, 20), 45);

        assertThat(windowed.getTotalPages()).isEqualTo(plain.getTotalPages()).isEqualTo(3);
        assertThat(windowed.hasNext()).isEqualTo(plain.hasNext()).isTrue();
        assertThat(windowed.isOlderOnServer()).isFalse();
        assertThat(PagedResponse.from(windowed).olderOnServer()).isFalse();
    }

    @Test
    @DisplayName("The count stays the whole's, and the pages stop at the depth, rounded up to a partial page")
    void deepListingStopsAtTheDepth() {
        WindowedPage<String> lastBrowsable = new WindowedPage<>(List.of("a"), PageRequest.of(9, 30), 1790, 300);
        WindowedPage<String> partial = new WindowedPage<>(List.of("a"), PageRequest.of(0, 70), 1790, 300);

        assertThat(lastBrowsable.getTotalElements()).isEqualTo(1790);
        assertThat(lastBrowsable.getTotalPages()).isEqualTo(10);
        assertThat(lastBrowsable.isLast()).isTrue();
        assertThat(partial.getTotalPages()).isEqualTo(5);

        PagedResponse<String> response = PagedResponse.from(lastBrowsable);
        assertThat(response.totalElements()).isEqualTo(1790);
        assertThat(response.totalPages()).isEqualTo(10);
        assertThat(response.last()).isTrue();
        assertThat(response.olderOnServer()).isTrue();
    }
}
