package org.voxrox.mailbackend.feature.mail.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.voxrox.mailbackend.feature.mail.entity.MessageEntity;
import org.voxrox.mailbackend.feature.mail.repository.MessageRepository;

import module java.base;

@Service
public class MessageService {

    private final MessageRepository messageRepository;

    public MessageService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public Page<MessageEntity> search(Long accountId, String query, int page, int size) {
        if (query == null || query.isBlank())
            return Page.empty();

        String sanitized = query.replaceAll("[^\\p{L}\\p{N}\\s]", " ").trim();
        if (sanitized.isEmpty())
            return Page.empty();

        String ftsQuery = sanitized.replaceAll("\\s+", "* ") + "*";
        return messageRepository.fullTextSearchSummaries(ftsQuery, accountId, PageRequest.of(page, size));
    }

    @Transactional(readOnly = true)
    public Optional<MessageEntity> getByStableId(String stableId) {
        return messageRepository.findByStableId(stableId);
    }

    @Transactional
    public void deleteByStableId(String stableId) {
        messageRepository.deleteByStableId(stableId);
    }
}
