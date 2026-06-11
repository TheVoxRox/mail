package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.exception.ResourceNotFoundException;
import org.voxrox.mailbackend.feature.account.entity.AccountEntity;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link MailContentService}.
 *
 * Mocks: - {@link ImapFolderExecutor} — IMAP communication -
 * {@link MessageRepository} — DB access - {@link MessageContentPersister} —
 * cache writer with @Transactional(REQUIRES_NEW).
 *
 * Covers: - Cache hit: content already in DB -> returned directly, no IMAP call
 * - Cache miss: content missing -> fetched from IMAP, saved, returned - Entity
 * not found -> ResourceNotFoundException - IMAP message null (deleted on the
 * server) -> ResourceNotFoundException - Blank content -> behaves like a cache
 * miss.
 */
@ExtendWith(MockitoExtension.class)
class MailContentServiceTest {

    private static final Long MESSAGE_ID = 1L;
    private static final Long ACCOUNT_ID = 42L;
    private static final String FOLDER_NAME = "INBOX";
    private static final long UID = 500L;
    private static final String CACHED_CONTENT = "<p>Cached content</p>";
    private static final String FETCHED_CONTENT = "<p>Content fetched from IMAP</p>";

    @Mock
    private ImapFolderExecutor folderExecutor;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private MessageContentPersister contentPersister;

    private MailContentService service;

    @BeforeEach
    void setUp() {
        service = new MailContentService(folderExecutor, messageRepository, contentPersister);
    }

    // ---- helpers ----

    /**
     * Creates a MessageEntity with the given parameters and optional content.
     */
    private MessageEntity createMessageEntity(String content) {
        AccountEntity account = new AccountEntity();
        account.setId(ACCOUNT_ID);

        MessageEntity entity = new MessageEntity();
        entity.setId(MESSAGE_ID);
        entity.setAccount(account);
        entity.setFolderName(FOLDER_NAME);
        entity.setUid(UID);
        entity.setContent(content);
        return entity;
    }

    @Nested
    @DisplayName("getOrFetchMessageContent")
    class GetOrFetchMessageContent {

        @Nested
        @DisplayName("Cache hit — content is already in DB")
        class CacheHit {

            @Test
            @DisplayName("Returns the cached content without calling IMAP")
            void shouldReturnCachedContentWithoutImapCall() {
                // Arrange: entity with non-empty content
                MessageEntity entity = createMessageEntity(CACHED_CONTENT);
                when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

                // Act
                String result = service.getOrFetchMessageContent(MESSAGE_ID);

                // Assert: returns directly from DB, IMAP not called
                assertThat(result).isEqualTo(CACHED_CONTENT);
                verify(folderExecutor, never()).executeReadOnly(anyLong(), anyString(), any());
                verify(contentPersister, never()).updateLocalCache(anyLong(), anyString());
            }
        }

        @Nested
        @DisplayName("Cache miss — content missing, fetched from IMAP")
        class CacheMiss {

            @Test
            @DisplayName("Fetches content from IMAP, saves to cache and returns it")
            void shouldFetchFromImapSaveToCacheAndReturn() throws Exception {
                // Arrange: entity without content
                MessageEntity entity = createMessageEntity(null);
                when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

                // Because MimePartExtractor and HtmlSanitizer are static utilities, we let
                // executeReadOnly return FETCHED_CONTENT directly — we verify the logic
                // around the cache-miss path, not MIME parsing.
                when(folderExecutor.executeReadOnly(eq(ACCOUNT_ID), eq(FOLDER_NAME), any()))
                        .thenReturn(FETCHED_CONTENT);
                when(contentPersister.updateLocalCache(MESSAGE_ID, FETCHED_CONTENT)).thenReturn(FETCHED_CONTENT);

                // Act
                String result = service.getOrFetchMessageContent(MESSAGE_ID);

                // Assert
                assertThat(result).isEqualTo(FETCHED_CONTENT);
                verify(folderExecutor).executeReadOnly(eq(ACCOUNT_ID), eq(FOLDER_NAME), any());
                verify(contentPersister).updateLocalCache(MESSAGE_ID, FETCHED_CONTENT);
            }

            @Test
            @DisplayName("Blank content behaves like a cache miss")
            void blankContentShouldBeTreatedAsCacheMiss() {
                // Arrange: entity with blank content (whitespace only)
                MessageEntity entity = createMessageEntity("   ");
                when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

                when(folderExecutor.executeReadOnly(eq(ACCOUNT_ID), eq(FOLDER_NAME), any()))
                        .thenReturn(FETCHED_CONTENT);
                when(contentPersister.updateLocalCache(MESSAGE_ID, FETCHED_CONTENT)).thenReturn(FETCHED_CONTENT);

                // Act
                String result = service.getOrFetchMessageContent(MESSAGE_ID);

                // Assert: blank content did not pass the fast path -> IMAP is called
                assertThat(result).isEqualTo(FETCHED_CONTENT);
                verify(folderExecutor).executeReadOnly(eq(ACCOUNT_ID), eq(FOLDER_NAME), any());
                verify(contentPersister).updateLocalCache(MESSAGE_ID, FETCHED_CONTENT);
            }
        }

        @Nested
        @DisplayName("Error cases")
        class ErrorCases {

            @Test
            @DisplayName("Entity not found in DB -> ResourceNotFoundException")
            void shouldThrowWhenEntityNotFound() {
                // Arrange: the message does not exist in the DB
                when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.empty());

                // Act & Assert
                assertThatThrownBy(() -> service.getOrFetchMessageContent(MESSAGE_ID))
                        .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining(String.valueOf(MESSAGE_ID));

                verify(folderExecutor, never()).executeReadOnly(anyLong(), anyString(), any());
            }

            @Test
            @DisplayName("IMAP message null (deleted on the server) -> ResourceNotFoundException")
            void shouldThrowWhenImapMessageIsNull() {
                // Arrange: entity without content; IMAP returns null (message deleted)
                MessageEntity entity = createMessageEntity(null);
                when(messageRepository.findById(MESSAGE_ID)).thenReturn(Optional.of(entity));

                // The lambda inside executeReadOnly returns null -> the message does not exist
                // on the server
                when(folderExecutor.executeReadOnly(eq(ACCOUNT_ID), eq(FOLDER_NAME), any())).thenReturn(null);

                // Act & Assert
                assertThatThrownBy(() -> service.getOrFetchMessageContent(MESSAGE_ID))
                        .isInstanceOf(ResourceNotFoundException.class).hasMessageContaining(String.valueOf(UID));

                verify(contentPersister, never()).updateLocalCache(anyLong(), anyString());
            }
        }
    }
}
