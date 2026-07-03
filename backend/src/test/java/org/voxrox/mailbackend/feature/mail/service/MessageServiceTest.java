package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.voxrox.mailbackend.feature.mail.dto.MailSummaryResponse;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link MessageService}.
 *
 * Main focus: full-text search ({@code search}) — input sanitization (strips
 * everything except letters/digits/whitespace as protection against FTS5
 * injection and DoS via special syntax), building the prefix-match query for
 * FTS5 ({@code "foo bar"} -> {@code "foo* bar*"}), and the two-step id → DTO
 * load that keeps {@code @Lob} bodies out of the search path while preserving
 * the FTS result order.
 */
@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    private static final Long ACCOUNT_ID = 5L;

    @Mock
    private MessageRepository messageRepository;

    @InjectMocks
    private MessageService service;

    private static MailSummaryResponse summary(long id) {
        return new MailSummaryResponse(id, "stable-" + id, "INBOX", "subject-" + id, "sender-" + id, null,
                LocalDateTime.now(), false, false, false, false, null, id);
    }

    @Nested
    @DisplayName("search — full-text search")
    class Search {

        @Test
        @DisplayName("Empty query returns an empty page without hitting the DB")
        void shouldReturnEmptyPageForNullQuery() {
            Page<MailSummaryResponse> result = service.search(ACCOUNT_ID, null, 0, 20);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("Whitespace-only query returns an empty page without hitting the DB")
        void shouldReturnEmptyPageForBlankQuery() {
            Page<MailSummaryResponse> result = service.search(ACCOUNT_ID, "   ", 0, 20);

            assertThat(result.getContent()).isEmpty();
            verifyNoInteractions(messageRepository);
        }

        @Test
        @DisplayName("Query with only special characters is emptied by sanitization -> empty page, DB not called")
        void shouldReturnEmptyPageWhenSanitizedQueryIsEmpty() {
            // Everything is non-alphanumeric -> sanitization erases everything ->
            // empty string. Behaves like an empty query.
            Page<MailSummaryResponse> result = service.search(ACCOUNT_ID, "!@#$%^&*()", 0, 20);

            assertThat(result.getContent()).isEmpty();
            verify(messageRepository, never()).fullTextSearchIds(any(String.class), any(Long.class),
                    any(Pageable.class));
        }

        @Test
        @DisplayName("Single-word query -> prefix match: 'faktura' -> 'faktura*'")
        void shouldBuildPrefixQueryForSingleWord() {
            when(messageRepository.fullTextSearchIds(eq("faktura*"), eq(ACCOUNT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<Number>(List.of(11L)));
            when(messageRepository.findSummariesByIds(List.of(11L))).thenReturn(List.of(summary(11L)));

            Page<MailSummaryResponse> result = service.search(ACCOUNT_ID, "faktura", 0, 20);

            assertThat(result.getContent()).hasSize(1);
            verify(messageRepository).fullTextSearchIds(eq("faktura*"), eq(ACCOUNT_ID), eq(PageRequest.of(0, 20)));
        }

        @Test
        @DisplayName("Multi-word query -> each token gets a prefix star: 'novak faktura' -> 'novak* faktura*'")
        void shouldBuildPrefixQueryForMultipleWords() {
            when(messageRepository.fullTextSearchIds(any(String.class), eq(ACCOUNT_ID), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.search(ACCOUNT_ID, "novak faktura", 0, 10);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageRepository).fullTextSearchIds(captor.capture(), eq(ACCOUNT_ID), any(Pageable.class));
            assertThat(captor.getValue()).isEqualTo("novak* faktura*");
        }

        @Test
        @DisplayName("Special characters (FTS5 operators) are stripped: 'foo AND \"bar\"*' -> 'foo* AND* bar*'")
        void shouldStripSpecialCharactersBeforeBuildingQuery() {
            // Sanitization replaces [^\p{L}\p{N}\s] with a space -> 'foo AND bar '.
            // After trim and whitespace split -> tokens 'foo', 'AND', 'bar' -> each
            // prefixed.
            // AND stays as a token (it is alphanumeric), but it goes in without quotes,
            // so FTS5 interprets it as an operator — for this test we verify only the
            // sanitization output format.
            when(messageRepository.fullTextSearchIds(any(String.class), eq(ACCOUNT_ID), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.search(ACCOUNT_ID, "foo AND \"bar\"*", 0, 10);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageRepository).fullTextSearchIds(captor.capture(), eq(ACCOUNT_ID), any(Pageable.class));
            assertThat(captor.getValue()).isEqualTo("foo* AND* bar*");
        }

        @Test
        @DisplayName("Diacritics (Czech glyphs) are preserved: 'Novák' -> 'Novák*'")
        void shouldPreserveUnicodeLetters() {
            when(messageRepository.fullTextSearchIds(any(String.class), eq(ACCOUNT_ID), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.search(ACCOUNT_ID, "Novák žluťoučký", 0, 10);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(messageRepository).fullTextSearchIds(captor.capture(), eq(ACCOUNT_ID), any(Pageable.class));
            // \p{L} in sanitization preserves all Unicode letters including diacritics.
            assertThat(captor.getValue()).isEqualTo("Novák* žluťoučký*");
        }

        @Test
        @DisplayName("Pagination propagates into Pageable: page=2, size=50")
        void shouldPropagatePagination() {
            when(messageRepository.fullTextSearchIds(any(String.class), eq(ACCOUNT_ID), any(Pageable.class)))
                    .thenReturn(Page.empty());

            service.search(ACCOUNT_ID, "test", 2, 50);

            verify(messageRepository).fullTextSearchIds(eq("test*"), eq(ACCOUNT_ID), eq(PageRequest.of(2, 50)));
        }

        @Test
        @DisplayName("Summaries come back in the FTS id order, not the IN-clause order")
        void shouldRestoreFtsOrder() {
            // FTS says newest-first: 30, 10, 20 — returned as Integers, the way the
            // SQLite driver boxes small ids. The IN load returns them in an
            // arbitrary (here ascending) order — the service must normalize the
            // boxes to Long and re-sort.
            when(messageRepository.fullTextSearchIds(eq("test*"), eq(ACCOUNT_ID), any(Pageable.class)))
                    .thenReturn(new PageImpl<Number>(List.of(30, 10, 20), PageRequest.of(0, 20), 3));
            when(messageRepository.findSummariesByIds(List.of(30L, 10L, 20L)))
                    .thenReturn(List.of(summary(10L), summary(20L), summary(30L)));

            Page<MailSummaryResponse> result = service.search(ACCOUNT_ID, "test", 0, 20);

            assertThat(result.getContent()).extracting(MailSummaryResponse::id).containsExactly(30L, 10L, 20L);
            assertThat(result.getTotalElements()).isEqualTo(3);
        }
    }
}
