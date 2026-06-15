package org.voxrox.mailbackend.feature.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

/**
 * Unit tests for {@link MessageContentPersister#updateLocalCache} — persists
 * the fetched body when the row still exists, and otherwise returns the content
 * unchanged without a write (the row may have been deleted between fetch and
 * cache write).
 */
@ExtendWith(MockitoExtension.class)
class MessageContentPersisterTest {

    @Mock
    private MessageRepository messageRepository;

    private MessageContentPersister persister;

    @BeforeEach
    void setUp() {
        persister = new MessageContentPersister(messageRepository);
    }

    @Test
    @DisplayName("Persists the content and returns it when the message exists")
    void persistsWhenMessageExists() {
        MessageEntity entity = new MessageEntity();
        when(messageRepository.findById(1L)).thenReturn(Optional.of(entity));

        String result = persister.updateLocalCache(1L, "<p>body</p>");

        assertThat(result).isEqualTo("<p>body</p>");
        assertThat(entity.getContent()).isEqualTo("<p>body</p>");
        verify(messageRepository).save(entity);
    }

    @Test
    @DisplayName("Returns the content without writing when the message is gone")
    void returnsContentWithoutSavingWhenMessageMissing() {
        when(messageRepository.findById(1L)).thenReturn(Optional.empty());

        String result = persister.updateLocalCache(1L, "<p>body</p>");

        assertThat(result).isEqualTo("<p>body</p>");
        verify(messageRepository, never()).save(any());
    }
}
